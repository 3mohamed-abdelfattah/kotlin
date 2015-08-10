package org.jetbrains.kotlin.js.qunit

import kotlin.test.*
import org.openqa.selenium.By
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebElement

private val TIMEOUT_PER_TEST: Long = 1000

/**
 * Helper class to find QUnit tests using Selenium
 */
public class SeleniumQUnit(val driver: WebDriver) {

    /**
     * Returns all the test cases found in the current driver's page
     */
    public fun findTests(): List<WebElement> {
        val qunitContainer = driver.findElement(By.id("qunit"))!!

        var current = ""

        var end = System.currentTimeMillis() + TIMEOUT_PER_TEST
        while (true) {
            val prev = current
            current = qunitContainer.getAttribute("class")
            val now = System.currentTimeMillis()

            if (current != prev) {
                end = now + TIMEOUT_PER_TEST
                continue
            }

            if (now >= end) break

            Thread.sleep(100)
        }

        val success = qunitContainer.getAttribute("class") == "done"
        assertTrue(success, """Test "$current" works too long (broken after $TIMEOUT_PER_TEST milliseconds)""")

        var resultsElement = driver.findElement(By.id("qunit-tests"))
        assertNotNull(resultsElement, "No qunit test elements could be found in ${driver.getCurrentUrl()}")

        return resultsElement!!.findElements(By.xpath("li"))?.filterNotNull() ?: arrayListOf<WebElement>()
    }

    public fun findTestName(element: WebElement): String {
        fun defaultName(name: String?) = name ?: "unknown test name for $element"
        try {
            val testNameElement = element.findElement(By.xpath("descendant::*[@class = 'test-name']"))
            return defaultName(testNameElement!!.getText())
        } catch (e: Exception) {
            return defaultName(element.getAttribute("id"))
        }
    }

    public fun runTest(element: WebElement): Unit {
        var result: String = ""
        result = element.getAttribute("class") ?: "no result"
        if ("pass" != result) {
            val testName = "${findTestName(element)} result: $result"
            val failMessages =
                try {
                    element.findElements(By.xpath("descendant::li[@class!='pass']/*[@class = 'test-message']"))
                            .map { "$testName. ${it.getText()}" }
                } catch (e: Exception) {
                    listOf("test result for test case $testName")
                }

            for (message in failMessages)
                println("FAILED: $message")
            fail(failMessages.join("\n"))
        }
    }
}
