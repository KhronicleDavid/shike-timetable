package com.shike.timetable.online

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DistributionTest {
    private val assetUrl = "https://github.com/${Distribution.REPOSITORY}/releases/download/v1.11.0/shike-1.11.0-compact.apk"
    private fun catalog() = JSONObject(File("../distribution/themes.json").readText())
    private fun release() = JSONObject("""{"tag_name":"v1.11.0","body":"更新说明\n新的功能","draft":false,"prerelease":false,"assets":[]}""").apply {
        getJSONArray("assets").put(JSONObject().put("name", "shike-1.11.0-compact.apk").put("state", "uploaded")
            .put("browser_download_url", assetUrl).put("size", 123456).put("digest", "sha256:"+"a".repeat(64)))
    }
    private fun manifest() = JSONObject("""{"schemaVersion":1,"tag":"v1.11.0","versionCode":14,"versionName":"1.11.0","minSdk":26,"assetName":"shike-1.11.0-compact.apk"}""")
    @Test fun repositoryCatalogMatchesEveryPublishedSourceTheme() {
        val themes = Distribution.themes(catalog().toString())
        assertTrue(themes.map { it.id }.containsAll(setOf("himari", "sagiri", "sylvia")))
        themes.forEach { item ->
            val source = JSONObject(File("../theme-packs/${item.id}/theme.json").readText())
            assertEquals(source.getInt("version"), item.version)
            assertEquals(source.getString("name"), item.name)
            assertTrue(item.artifact.bytes in 1..Distribution.MAX_THEME)
        }
    }
    @Test fun integerCodeAndReleaseNotesAreUsedWithoutLexicalVersionComparison() {
        val update = Distribution.update(manifest().toString(), Distribution.release(release().toString()))
        assertEquals(14, update.versionCode); assertEquals("1.11.0", update.versionName)
        assertEquals("更新说明\n新的功能", update.notes); assertEquals(assetUrl, update.artifact.url)
    }
    @Test fun releaseCannotBeDraftPrereleaseIncompleteOrMissingDigest() {
        listOf(release().put("draft", true), release().put("prerelease", true), release().apply { getJSONArray("assets").getJSONObject(0).put("state", "new") },
            release().apply { getJSONArray("assets").getJSONObject(0).put("digest", JSONObject.NULL) }).forEach {
            assertThrows(Exception::class.java) { Distribution.release(it.toString()) }
        }
    }
    @Test fun appManifestMustMatchReleaseAndActualApkAsset() {
        val released = Distribution.release(release().toString())
        listOf(manifest().put("tag", "v1.9.0"), manifest().put("assetName", "missing.apk"), manifest().put("versionCode", 0), manifest().put("schemaVersion", 2)).forEach {
            assertThrows(Exception::class.java) { Distribution.update(it.toString(), released) }
        }
    }
    @Test fun catalogRejectsDuplicateIdentityUnsupportedSchemaAndOversizedFiles() {
        listOf(catalog().apply { getJSONArray("themes").put(getJSONArray("themes").getJSONObject(0)) }, catalog().put("schemaVersion",2),
            catalog().apply { getJSONArray("themes").getJSONObject(0).put("bytes", Distribution.MAX_THEME+1) },
            catalog().apply { getJSONArray("themes").getJSONObject(0).put("sha256", "bad") },
            catalog().apply { getJSONArray("themes").getJSONObject(0).put("id", "../himari") }).forEach {
            assertThrows(Exception::class.java) { Distribution.themes(it.toString()) }
        }
    }
    @Test fun downloadOriginIsExactRepositoryWithNoEncodedTraversalOrQueries() {
        listOf("http://github.com/${Distribution.REPOSITORY}/releases/download/v1/a.apk", assetUrl.replace("github.com", "github.com.evil.example"),
            assetUrl.replace("KhronicleDavid", "other"), assetUrl+"?token=x", assetUrl+"#x", assetUrl.replace("v1.11.0", "%2e%2e"), assetUrl.replace("github.com", "user@github.com"), assetUrl.replace("github.com", "github.com:443")).forEach {
            assertThrows(Exception::class.java) { Distribution.releaseUrl(it) }
        }
    }
}
