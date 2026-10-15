package com.shike.timetable.ui

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SchoolWebViewTest {
    @Test fun completePageLoaderOnlyHandlesMainFrameSchoolGets() {
        assertTrue(needsSchoolPageLoader(SCHOOL_ENTRY_URL, "GET", true))
        assertTrue(needsSchoolPageLoader(SCHOOL_TABLE_URL, "GET", true))
        assertFalse(needsSchoolPageLoader(SCHOOL_TABLE_URL, "POST", true))
        assertFalse(needsSchoolPageLoader(SCHOOL_TABLE_URL, "GET", false))
        assertFalse(needsSchoolPageLoader("https://idas.uestc.edu.cn/authserver/login", "GET", true))
        assertFalse(needsSchoolPageLoader("https://eams.uestc.edu.cn.attacker.invalid/eams/", "GET", true))
        assertFalse(needsSchoolPageLoader("http://eams.uestc.edu.cn/eams/", "GET", true))
    }
    @Test fun desktopAgentKeepsTheInstalledChromeVersionAndRemovesMobileWebViewIdentity() {
        val native = "Mozilla/5.0 (Linux; Android 14; Fixture Phone Build/DEMO; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/123.0.6312.99 Mobile Safari/537.36"
        val desktop = desktopSchoolUserAgent(native)
        assertTrue(desktop.contains("(Windows NT 10.0; Win64; x64)"))
        assertTrue(desktop.contains("Chrome/123.0.6312.99"))
        assertFalse(desktop.contains("Android"))
        assertFalse(desktop.contains("Mobile"))
        assertFalse(desktop.contains("; wv"))
        assertFalse(desktop.contains("Version/4.0"))
        assertEquals(desktop, desktopSchoolUserAgent(desktop))
    }

    @Test fun officialHttpsEntryAndSchoolAuthenticationDomainsAreAllowed() {
        listOf(
            SCHOOL_ENTRY_URL,
            "https://eams.uestc.edu.cn/eams/courseTableForStd!courseTable.action",
            "https://idas.uestc.edu.cn/authserver/login?service=https%3A%2F%2Feams.uestc.edu.cn%2Feams%2F",
            "https://idas.uestc.edu.cn/authserver/login?ticket=ST-fixture",
            "https://uestc.edu.cn/",
            "https://EAMS.UESTC.EDU.CN:443/eams/",
        ).forEach { assertTrue(it, isAllowedSchoolNavigation(it)) }
    }

    @Test fun insecureSchemesCredentialsPortsAndLookalikeHostsAreRejected() {
        listOf(
            "http://eams.uestc.edu.cn/eams/",
            "file:///eams/index.html",
            "content://school/eams/",
            "javascript:alert(1)",
            "data:text/html,fixture",
            "intent://eams.uestc.edu.cn/",
            "https://uestc.edu.cn.attacker.invalid/eams/",
            "https://fake-uestc.edu.cn/eams/",
            "https://uestc.edu.cn@attacker.invalid/eams/",
            "https://fixture:secret@eams.uestc.edu.cn/eams/",
            "https://eams.uestc.edu.cn:444/eams/",
            "https://eams.uestc.edu.cn\\@attacker.invalid/",
            "https://eams.uestc.edu.cn/eams/\n",
            "//eams.uestc.edu.cn/eams/",
            "",
        ).forEach { assertFalse(it, isAllowedSchoolNavigation(it)) }
    }

    @Test fun captureIsLimitedToTheEamsOriginAndDirectory() {
        assertTrue(canReadEamsPage(SCHOOL_ENTRY_URL))
        assertTrue(canReadEamsPage("https://eams.uestc.edu.cn/eams/courseTableForStd!courseTable.action?semester.id=123"))
        listOf(
            "https://idas.uestc.edu.cn/eams/",
            "https://eams.uestc.edu.cn/authserver/login",
            "https://eams.uestc.edu.cn/eams-other/",
            "https://eams.uestc.edu.cn/",
            "http://eams.uestc.edu.cn/eams/",
            "https://eams.uestc.edu.cn.attacker.invalid/eams/",
        ).forEach { assertFalse(it, canReadEamsPage(it)) }
    }

    @Test fun fileByteLimitAcceptsExactBoundaryAndRejectsOneExtraByte() {
        val exact = ByteArray(MAX_SCHOOL_FILE_BYTES) { 'a'.code.toByte() }
        assertEquals(MAX_SCHOOL_FILE_BYTES, readSchoolFile(ByteArrayInputStream(exact)).length)
        val problem = assertThrows(IllegalArgumentException::class.java) {
            readSchoolFile(ByteArrayInputStream(ByteArray(MAX_SCHOOL_FILE_BYTES + 1) { 'a'.code.toByte() }))
        }
        assertTrue(problem.message.orEmpty().contains("2 MB"))
    }

    @Test fun emptyFilesAreRejected() {
        val problem = assertThrows(IllegalArgumentException::class.java) {
            readSchoolFile(ByteArrayInputStream(ByteArray(0)))
        }
        assertTrue(problem.message.orEmpty().contains("为空"))
    }

    @Test fun utf8AndUtf8BomKeepChineseCourseText() {
        val text = "<html><td>测试课程 · 周一 · 测试教室</td></html>"
        assertEquals(text, readSchoolFile(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(text, readSchoolFile(ByteArrayInputStream(bom + text.toByteArray(Charsets.UTF_8))))
    }

    @Test fun declaredGbkHtmlAndLegacyGb2312HtmlAreDecoded() {
        listOf("GBK", "gb2312", "gb18030").forEach { label ->
            val text = "<html><meta charset='$label'><td>测试教师 测试课程(FAKE101);(连1-15,测试教室)</td></html>"
            assertEquals(text, readSchoolFile(ByteArrayInputStream(text.toByteArray(Charset.forName("GB18030")))))
        }
    }

    @Test fun utf16BomIsDecodedWithoutKeepingTheBom() {
        val text = "测试课程"
        val little = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val big = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        assertEquals(text, readSchoolFile(ByteArrayInputStream(little)))
        assertEquals(text, readSchoolFile(ByteArrayInputStream(big)))
    }

    @Test fun malformedUtf8IsRejectedInsteadOfSilentlyChangingText() {
        val problem = assertThrows(IllegalArgumentException::class.java) {
            readSchoolFile(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
        }
        assertTrue(problem.message.orEmpty().contains("编码"))
    }
}
