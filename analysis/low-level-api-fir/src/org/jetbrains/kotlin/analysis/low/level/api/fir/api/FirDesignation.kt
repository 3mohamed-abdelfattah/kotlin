/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir.api

import org.jetbrains.kotlin.analysis.low.level.api.fir.project.structure.llFirModuleData
import org.jetbrains.kotlin.analysis.low.level.api.fir.providers.nullableJavaSymbolProvider
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirLibraryOrLibrarySourceResolvableModuleSession
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.FirElementFinder
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.getContainingFile
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.isScriptDependentDeclaration
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.unwrapCopy
import org.jetbrains.kotlin.analysis.project.structure.DanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.project.structure.KtDanglingFileModule
import org.jetbrains.kotlin.analysis.utils.errors.requireIsInstance
import org.jetbrains.kotlin.analysis.utils.errors.unexpectedElementError
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.synthetic.FirSyntheticProperty
import org.jetbrains.kotlin.fir.declarations.synthetic.FirSyntheticPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.diagnostics.ConeDestructuringDeclarationsOnTopLevel
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirScriptSymbol
import org.jetbrains.kotlin.fir.utils.exceptions.withFirEntry
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.utils.exceptions.ExceptionAttachmentBuilder
import org.jetbrains.kotlin.utils.exceptions.checkWithAttachment
import org.jetbrains.kotlin.utils.exceptions.errorWithAttachment
import org.jetbrains.kotlin.utils.exceptions.requireWithAttachment

/**
 * This class describes where locates [target] element and its essential [path].
 *
 * Usually a resolver uses [path] to resolve [target] in the proper context.
 *
 * @see org.jetbrains.kotlin.analysis.low.level.api.fir.api.targets.LLFirResolveTarget
 */
class FirDesignation(
    /**
     * The path to [target] element.
     *
     * ### Contracts:
     * * Can contain [FirFile] only in the first position
     * * Can contain [FirScript] only in the first or second position
     *
     * @see file
     * @see fileOrNull
     * @see script
     * @see scriptOrNull
     */
    val path: List<FirDeclaration>,
    val target: FirElementWithResolveState,
) {
    init {
        for ((index, declaration) in path.withIndex()) {
            when (declaration) {
                is FirFile -> requireWithAttachment(
                    index == 0,
                    { "${FirFile::class.simpleName} can be only in the first position of the path, but actual is '$index'" },
                ) {
                    withFirDesignationEntry("designation", this@FirDesignation)
                }

                is FirScript -> requireWithAttachment(
                    index == 0 || index == 1 && path.first() is FirFile,
                    { "${FirScript::class.simpleName} can be only in the first or second position of the path, but actual is '$index'" },
                ) {
                    withFirDesignationEntry("designation", this@FirDesignation)
                }

                is FirRegularClass -> {}
                else -> errorWithAttachment("Unexpected declaration type: ${declaration::class.simpleName}") {
                    withFirDesignationEntry("designation", this@FirDesignation)
                }
            }
        }
    }

    val file: FirFile
        get() = fileOrNull ?: errorWithAttachment("File is not found") {
            withFirDesignationEntry("designation", this@FirDesignation)
        }

    val fileOrNull: FirFile? get() = path.firstOrNull() as? FirFile

    val script: FirScript
        get() = scriptOrNull ?: errorWithAttachment("Script is not found") {
            withFirDesignationEntry("designation", this@FirDesignation)
        }

    val scriptOrNull: FirScript? get() = path.getOrNull(0) as? FirScript ?: path.getOrNull(1) as? FirScript

    /**
     * This property exists only for compatibility and should be dropped after KT-65345
     */
    val classPath: List<FirRegularClass> get() = path.filterIsInstance<FirRegularClass>()

    /**
     * This constructor exists only for compatibility and should be dropped after KT-65345
     */
    constructor(
        firFile: FirFile,
        path: List<FirRegularClass>,
        target: FirElementWithResolveState,
    ) : this(listOf(firFile) + path, target)
}

fun ExceptionAttachmentBuilder.withFirDesignationEntry(name: String, designation: FirDesignation) {
    withEntryGroup(name) {
        for ((index, declaration) in designation.path.withIndex()) {
            withFirEntry("path$index", declaration)
        }

        withFirEntry("target", designation.target)
    }
}

fun FirDesignation.toSequence(includeTarget: Boolean): Sequence<FirElementWithResolveState> = sequence {
    yieldAll(path)
    if (includeTarget) yield(target)
}

private fun collectDesignationPath(target: FirElementWithResolveState): List<FirRegularClass>? {
    when (target) {
        is FirSimpleFunction,
        is FirProperty,
        is FirField,
        is FirConstructor,
        is FirEnumEntry,
        is FirPropertyAccessor,
        -> {
            requireIsInstance<FirCallableDeclaration>(target)
            // We shouldn't try to build a designation path for such fake declarations as they
            // do not depend on outer classes during resolution
            if (target.isCopyCreatedInScope) return emptyList()

            if (target.symbol.callableId.isLocal || target.status.visibility == Visibilities.Local) {
                return null
            }

            val containingClassId = target.containingClassLookupTag()?.classId ?: return emptyList()
            return collectDesignationPathWithContainingClass(target, containingClassId)
        }

        is FirClassLikeDeclaration -> {
            if (target.isLocal) {
                return null
            }

            val containingClassId = target.symbol.classId.outerClassId ?: return emptyList()
            return collectDesignationPathWithContainingClass(target, containingClassId)
        }

        is FirDanglingModifierList -> {
            val containingClassId = target.containingClass()?.classId ?: return emptyList()
            return collectDesignationPathWithContainingClass(target, containingClassId)
        }

        is FirAnonymousInitializer -> {
            val containingClassId = (target.containingDeclarationSymbol as? FirClassSymbol<*>)?.classId
            if (containingClassId == null || containingClassId.isLocal) return null
            return collectDesignationPathWithContainingClass(target, containingClassId)
        }

        is FirErrorProperty -> {
            return if (target.diagnostic == ConeDestructuringDeclarationsOnTopLevel) emptyList() else null
        }

        is FirScript, is FirCodeFragment -> {
            return emptyList()
        }

        else -> {
            return null
        }
    }
}

private fun collectDesignationPathWithContainingClassByFirFile(
    firFile: FirFile,
    containingClassId: ClassId,
    target: FirDeclaration,
): List<FirRegularClass>? = FirElementFinder.findClassPathToDeclaration(
    firFile = firFile,
    declarationContainerClassId = containingClassId,
    targetMemberDeclaration = target,
)

private fun collectDesignationPathWithContainingClass(target: FirDeclaration, containingClassId: ClassId): List<FirRegularClass>? {
    if (containingClassId.isLocal) {
        return null
    }

    val firFile = target.getContainingFile()
    if (firFile != null && firFile.packageFqName == containingClassId.packageFqName) {
        val designationPath = collectDesignationPathWithContainingClassByFirFile(firFile, containingClassId, target)
        if (designationPath != null) {
            return designationPath
        }
    }

    return collectDesignationPathWithContainingClassFallback(target, containingClassId)
}

private fun collectDesignationPathWithContainingClassFallback(target: FirDeclaration, containingClassId: ClassId): List<FirRegularClass>? {
    val useSiteSession = getTargetSession(target)

    fun resolveChunk(classId: ClassId): FirRegularClass {
        val declaration = if (useSiteSession is LLFirLibraryOrLibrarySourceResolvableModuleSession) {
            useSiteSession.symbolProvider.getClassLikeSymbolByClassId(classId)?.fir
        } else {
            useSiteSession.firProvider.getFirClassifierByFqName(classId)
                ?: useSiteSession.nullableJavaSymbolProvider?.getClassLikeSymbolByClassId(classId)?.fir
                ?: findKotlinStdlibClass(classId, target)
        }

        checkWithAttachment(
            declaration is FirRegularClass,
            message = { "'FirRegularClass' expected as a containing declaration, got '${declaration?.javaClass?.name}'" },
            buildAttachment = {
                withEntry("chunk", "$classId in $containingClassId")
                withFirEntry("target", target)
                if (declaration != null) {
                    withFirEntry("foundDeclaration", declaration)
                }
            }
        )

        return declaration
    }

    val chunks = generateSequence(containingClassId) { it.outerClassId }.toList()

    if (chunks.any { it.shortClassName.isSpecial }) {
        val fallbackResult = collectDesignationPathWithTreeTraversal(target)
        if (fallbackResult != null) {
            return patchDesignationPathIfNeeded(target, fallbackResult)
        }
    }

    val result = chunks
        .dropWhile { it.shortClassName.isSpecial }
        .map { resolveChunk(it) }
        .asReversed()

    return patchDesignationPathIfNeeded(target, result)
}

/*
    This implementation is certainly inefficient, however there seem to be no better way to implement designation collection for
    anonymous outer classes unless FIR tree gets a way to get an element parent.
 */
private fun collectDesignationPathWithTreeTraversal(target: FirDeclaration): List<FirRegularClass>? {
    val containingFile = target.getContainingFile() ?: return null

    val path = ArrayDeque<FirElement>()
    path.addLast(containingFile)

    var result: List<FirRegularClass>? = null

    val visitor = object : FirVisitorVoid() {
        override fun visitElement(element: FirElement) {
            if (result != null) {
                return
            } else if (element === target) {
                result = path.filterIsInstance<FirRegularClass>()
            } else {
                try {
                    path.addLast(element)
                    element.acceptChildren(this)
                } finally {
                    path.removeLast()
                }
            }
        }
    }

    containingFile.accept(visitor)
    return result
}

private fun getTargetSession(target: FirDeclaration): FirSession {
    if (target is FirCallableDeclaration) {
        val containingSymbol = target.containingClassLookupTag()?.toSymbol(target.moduleData.session)
        if (containingSymbol != null) {
            // Synthetic declarations might have a call site session
            return containingSymbol.moduleData.session
        }
    }

    return target.moduleData.session
}

private fun findKotlinStdlibClass(classId: ClassId, target: FirDeclaration): FirRegularClass? {
    if (!classId.packageFqName.startsWith(StandardNames.BUILT_INS_PACKAGE_NAME)) {
        return null
    }

    val firFile = target.getContainingFile() ?: return null
    return FirElementFinder.findClassifierWithClassId(firFile, classId) as? FirRegularClass
}

fun FirElementWithResolveState.collectDesignation(firFile: FirFile): FirDesignation =
    tryCollectDesignation(firFile) ?: errorWithAttachment("No designation of local declaration") {
        withFirEntry("firFile", firFile)
    }

fun FirElementWithResolveState.collectDesignation(): FirDesignation =
    tryCollectDesignation()
        ?: errorWithAttachment("No designation of local declaration") {
            withFirEntry("FirDeclaration", this@collectDesignation)
        }

fun FirElementWithResolveState.collectDesignationWithFile(): FirDesignation =
    tryCollectDesignationWithFile()
        ?: errorWithAttachment("No designation of local declaration") {
            withFirEntry("FirDeclaration", this@collectDesignationWithFile)
        }

fun FirElementWithResolveState.tryCollectDesignation(firFile: FirFile): FirDesignation? =
    collectDesignationPath(this)?.let {
        FirDesignation(firFile, it, this)
    }

fun FirElementWithResolveState.tryCollectDesignation(): FirDesignation? =
    collectDesignationPath(this)?.let {
        FirDesignation(it, this)
    }

fun FirElementWithResolveState.tryCollectDesignationWithFile(): FirDesignation? {
    return when (this) {
        is FirScript, is FirCodeFragment, is FirFileAnnotationsContainer -> {
            val firFile = getContainingFile() ?: return null
            FirDesignation(firFile, path = emptyList(), this)
        }

        is FirSyntheticProperty, is FirSyntheticPropertyAccessor -> unexpectedElementError<FirElementWithResolveState>(this)
        is FirDeclaration -> {
            val scriptDesignation = scriptDesignation()
            if (scriptDesignation != null) return scriptDesignation

            val path = collectDesignationPath(this) ?: return null
            val firFile = path.lastOrNull()?.getContainingFile() ?: getContainingFile() ?: return null
            FirDesignation(firFile, path, this)
        }

        else -> unexpectedElementError<FirElementWithResolveState>(this)
    }
}

private fun FirDeclaration.scriptDesignation(): FirDesignation? {
    return when {
        this is FirAnonymousInitializer -> {
            val firScriptSymbol = (containingDeclarationSymbol as? FirScriptSymbol) ?: return null
            val firFile = firScriptSymbol.fir.getContainingFile() ?: return null
            FirDesignation(firFile, path = emptyList(), firScriptSymbol.fir)
        }
        isScriptDependentDeclaration -> {
            val firFile = getContainingFile() ?: return null
            val firScript = firFile.declarations.singleOrNull() as? FirScript ?: return null
            FirDesignation(firFile, path = emptyList(), firScript)
        }
        else -> null
    }
}

internal fun patchDesignationPathIfNeeded(target: FirElementWithResolveState, targetPath: List<FirRegularClass>): List<FirRegularClass> {
    return patchDesignationPathForCopy(target, targetPath) ?: targetPath
}

private fun patchDesignationPathForCopy(target: FirElementWithResolveState, targetPath: List<FirRegularClass>): List<FirRegularClass>? {
    val targetModule = target.llFirModuleData.ktModule

    if (targetModule is KtDanglingFileModule && targetModule.resolutionMode == DanglingFileResolutionMode.IGNORE_SELF) {
        val targetPsiFile = targetModule.file

        val contextModule = targetModule.contextModule
        val contextResolveSession = contextModule.getFirResolveSession(contextModule.project)

        return buildList {
            for (targetPathClass in targetPath) {
                val targetPathPsi = targetPathClass.psi as? KtDeclaration ?: return null
                val originalPathPsi = targetPathPsi.unwrapCopy(targetPsiFile) ?: return null
                val originalPathClass = originalPathPsi.getOrBuildFirSafe<FirRegularClass>(contextResolveSession) ?: return null
                add(originalPathClass)
            }
        }
    }

    return targetPath
}