package com.shike.timetable.online

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

class GitHubHttpTest {
    @get:Rule val temp = TemporaryFolder()
    private val url = "https://github.com/${Distribution.REPOSITORY}/releases/download/v1/test.zip"
    private val bytes = ByteArray(100_000) { (it % 253).toByte() }
    private class Response(url: URL, val body: ByteArray, val status: Int = 200, val location: String? = null, val length: Long = body.size.toLong(), val stream: InputStream = ByteArrayInputStream(body)) : HttpURLConnection(url) {
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getInputStream(): InputStream = stream
        override fun getHeaderField(name: String): String? = if (name == "Location") location else null
    }
    private fun item(size: Long = bytes.size.toLong(), sha: String = GitHubHttp.hash(bytes)) = Artifact(url,size,sha)
    @Test fun streamedDownloadVerifiesBeforePublishingFile() {
        val response = Response(URL(url),bytes); val progress = mutableListOf<Long>(); val destination = temp.newFile("success.zip")
        GitHubHttp { response }.download(item(),destination,GitHubHttp.Control(),progress::add)
        assertArrayEquals(bytes,destination.readBytes()); assertEquals(bytes.size.toLong(),progress.last())
        assertTrue(response.closed); assertFalse(java.io.File(temp.root,"success.zip.part").exists())
    }
    @Test fun shortOversizedCorruptOrDeclaredMismatchNeverLeaveAFile() {
        listOf(Response(URL(url),bytes.copyOf(100)), Response(URL(url),bytes+byteArrayOf(1),length=-1),
            Response(URL(url),bytes.copyOf().apply { this[40] = 0 }), Response(URL(url),bytes,length=2)).forEachIndexed { index,response ->
            val file = java.io.File(temp.root,"bad-$index.zip")
            assertThrows(Exception::class.java) { GitHubHttp { response }.download(item(),file,GitHubHttp.Control()) {} }
            assertFalse(file.exists()); assertFalse(java.io.File(temp.root,file.name+".part").exists()); assertTrue(response.closed)
        }
    }
    @Test fun cancellationDeletesPartialDataAndClosesConnection() {
        val control = GitHubHttp.Control(); val file = java.io.File(temp.root,"cancel.zip")
        val stream = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, off: Int, len: Int): Int { val n=super.read(buffer,off,len); control.cancel(); return n }
        }
        val response=Response(URL(url),bytes,stream=stream)
        assertThrows(CancellationException::class.java) { GitHubHttp { response }.download(item(),file,control) {} }
        assertFalse(file.exists()); assertFalse(java.io.File(temp.root,"cancel.zip.part").exists()); assertTrue(response.closed)
    }
    @Test fun redirectsOnlyGoToGithubHttpsHostsAndAreBounded() {
        listOf("http://release-assets.githubusercontent.com/asset", "https://evil.example/asset", "https://127.0.0.1/", "https://user@github.com/").forEach { target ->
            var calls=0
            assertThrows(Exception::class.java) { GitHubHttp { calls++; Response(it,byteArrayOf(),302,target) }.json(url) }
            assertEquals(1,calls)
        }
        var calls=0
        assertThrows(Exception::class.java) { GitHubHttp { calls++; Response(it,byteArrayOf(),302,url) }.json(url) }
        assertEquals(6,calls)
        calls=0
        val body="{}".toByteArray()
        val result=GitHubHttp { calls++; if(calls==1) Response(it,byteArrayOf(),302,"https://release-assets.githubusercontent.com/a") else Response(it,body) }.json(url)
        assertEquals("{}",result); assertEquals(2,calls)
    }
    @Test fun jsonIsBoundedAndManifestMustMatchReleaseDigest() {
        assertThrows(Exception::class.java) { GitHubHttp { Response(it,ByteArray(Distribution.MAX_JSON.toInt()+1),length=-1) }.json(url) }
        assertThrows(Exception::class.java) { GitHubHttp { Response(it,"{}".toByteArray()) }.json(url,expected=item()) }
        val body="{}".toByteArray()
        assertEquals("{}",GitHubHttp { Response(it,body) }.json(url,expected=Artifact(url,2,GitHubHttp.hash(body))))
    }
    @Test fun githubFailureDoesNotProduceSuccessOrPersistErrorBody() {
        listOf(403,404,429,500).forEach { status ->
            val response=Response(URL(url),"error".toByteArray(),status)
            assertThrows(Exception::class.java) { GitHubHttp { response }.json(url) }
            assertTrue(response.closed)
        }
    }
}
