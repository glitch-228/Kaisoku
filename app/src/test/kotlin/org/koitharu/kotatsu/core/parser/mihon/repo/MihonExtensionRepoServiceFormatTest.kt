package org.koitharu.kotatsu.core.parser.mihon.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPOutputStream

/**
 * Byte-level dispatch + format tolerance of [MihonExtensionRepoService]'s index reader.
 * Network-free: exercises the byte-sniffing branch by handcrafting what the fetch would return.
 */
class MihonExtensionRepoServiceFormatTest {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun detectsGzipMagicAndDecompresses() {
        val plain = """{"name":"Keiyoushi","extensionList":{"extensions":[]}}""".encodeToByteArray()
        val gzipped = java.io.ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { it.write(plain) }
            bos.toByteArray()
        }
        // Gzip starts with 0x1f 0x8b
        assertEquals(0x1f.toByte(), gzipped[0])
        assertEquals(0x8b.toByte(), gzipped[1])
        val unpacked = java.util.zip.GZIPInputStream(gzipped.inputStream()).use { it.readBytes() }
        assertEquals(plain.decodeToString(), unpacked.decodeToString())
    }

    @Test
    fun detectsJsonObjectByte() {
        val open: Byte = '{'.code.toByte()
        assertEquals(123.toByte(), open)
    }

    @Test
    fun detectsJsonArrayByte() {
        val open: Byte = '['.code.toByte()
        assertEquals(91.toByte(), open)
    }

    @Test
    fun parsesLegacyFlatIndex() {
        val body = """
            [{
              "name": "Tachiyomi: Example",
              "pkg": "eu.kanade.tachiyomi.extension.en.example",
              "apk": "tachiyomi-en.example-v1.4.7.apk",
              "lang": "en",
              "code": 7,
              "version": "1.4.7",
              "nsfw": 0,
              "sources": []
            }]
        """.trimIndent()
        val parsed = json.decodeFromString<List<MihonExtensionIndexEntryDto>>(body)
        assertEquals(1, parsed.size)
        assertEquals("eu.kanade.tachiyomi.extension.en.example", parsed[0].pkg)
        assertEquals(7L, parsed[0].code)
        assertEquals("1.4.7", parsed[0].version)
    }

    @Test
    fun parsesKeiyoushiJsonStore() {
        val body = """
            {
              "name": "Keiyoushi",
              "badgeLabel": "KEI",
              "signingKey": "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
              "contact": { "website": "https://keiyoushi.github.io" },
              "extensionList": {
                "extensions": [{
                  "name": "AHottie",
                  "packageName": "eu.kanade.tachiyomi.extension.all.ahottie",
                  "resources": {
                    "apkUrl": "https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/apk/tachiyomi-all.ahottie-v1.6.4.apk",
                    "iconUrl": "https://raw.githubusercontent.com/keiyoushi/extensions-source@main/src/all/ahottie/res/mipmap-xhdpi/ic_launcher.png",
                    "jarUrl": "https://raw.githubusercontent.com/keiyoushi/extensions/repo/jar/tachiyomi-all.ahottie-v1.6.4.jar"
                  },
                  "extensionLib": "1.6",
                  "versionCode": "4",
                  "versionName": "1.6.4",
                  "contentWarning": "CONTENT_WARNING_NSFW",
                  "sources": [{
                    "id": "6289731484943315811",
                    "name": "AHottie",
                    "language": "all",
                    "homeUrl": "https://ahottie.top"
                  }]
                }]
              }
            }
        """.trimIndent()
        val parsed = json.decodeFromString<NetworkExtensionStoreJson>(body)
        assertEquals("Keiyoushi", parsed.name)
        assertEquals(1, parsed.extensionList?.extensions?.size)
        val ext = parsed.extensionList!!.extensions[0]
        assertEquals("eu.kanade.tachiyomi.extension.all.ahottie", ext.packageName)
        assertEquals("4", ext.versionCode)
        assertEquals("1.6", ext.extensionLib)
        assertEquals(NetworkExtensionStore.ContentWarning.NSFW, ext.contentWarning)
        assertEquals("6289731484943315811", ext.sources[0].id)
        assertEquals("all", ext.sources[0].language)
        assertTrue(ext.resources.jarUrl != null)
    }

    @Test
    fun mapsStoreExtensionAvailability() {
        val repo = MihonExtensionRepo(
            baseUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
            name = "Keiyoushi",
            shortName = null,
            website = "https://keiyoushi.github.io",
            signingKeyFingerprint = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
            isStoreFormat = true,
        )
        val store = NetworkExtensionStore(
            extensionList = NetworkExtensionStore.ExtensionList(
                extensions = listOf(
                    NetworkExtensionStore.Extension(
                        name = "MangaDex",
                        packageName = "eu.kanade.tachiyomi.extension.all.mangadex",
                        resources = NetworkExtensionStore.Resources(
                            apkUrl = "https://cdn.example/apk/tachiyomi-all.mangadex-v1.6.10.apk",
                            iconUrl = "https://cdn.example/icon/eu.kanade.tachiyomi.extension.all.mangadex.png",
                        ),
                        extensionLib = "1.6",
                        versionCode = 10,
                        versionName = "1.6.10",
                        contentWarning = NetworkExtensionStore.ContentWarning.SAFE,
                        sources = listOf(
                            NetworkExtensionStore.Source(
                                id = 1,
                                name = "MangaDex",
                                language = "all",
                                homeUrl = "https://mangadex.org",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val mapped = store.extensionList!!.extensions.mapNotNull { it.toAvailableExtension(repo) }
        assertEquals(1, mapped.size)
        val ext = mapped[0]
        assertEquals("MangaDex", ext.name)
        assertEquals("1.6.10", ext.versionName)
        assertEquals(1.6, ext.libVersion, 0.001)
        assertEquals(10L, ext.versionCode)
        assertFalse(ext.isNsfw)
        assertEquals("all", ext.lang)
        assertEquals(
            "https://cdn.example/apk/tachiyomi-all.mangadex-v1.6.10.apk",
            repoServiceGetApkUrl(repo, ext),
        )
        assertEquals(
            "https://cdn.example/icon/eu.kanade.tachiyomi.extension.all.mangadex.png",
            ext.iconUrl,
        )
    }

    // Mirrors MihonExtensionRepoService.getApkUrl without an OkHttpClient.
    private fun repoServiceGetApkUrl(repo: MihonExtensionRepo, ext: MihonAvailableExtension): String {
        val apkName = ext.apkName
        return if (apkName.startsWith("http://") || apkName.startsWith("https://")) apkName else "${repo.baseUrl}/apk/$apkName"
    }

    @Test
    fun mapsLegacyExtensionAvailability() {
        val dto = MihonExtensionIndexEntryDto(
            name = "Tachiyomi: Asura Scans",
            pkg = "eu.kanade.tachiyomi.extension.en.asurascans",
            apk = "tachiyomi-en.asurascans-v1.4.40.apk",
            lang = "en",
            code = 40,
            version = "1.4.40",
            nsfw = 0,
            sources = listOf(),
        )
        val repo = MihonExtensionRepo(
            baseUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
            name = "Keiyoushi",
            shortName = null,
            website = "https://keiyoushi.github.io",
            signingKeyFingerprint = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
        )
        val ext = repo.run { toLegacyAvailableExtension(dto, this) }
        assertEquals(1.4, ext.libVersion, 0.001)
        assertEquals(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/apk/tachiyomi-en.asurascans-v1.4.40.apk",
            repoServiceGetApkUrl(repo, ext),
        )
    }

    private fun toLegacyAvailableExtension(dto: MihonExtensionIndexEntryDto, repo: MihonExtensionRepo): MihonAvailableExtension {
        val libVersion = org.koitharu.kotatsu.core.parser.mihon.MihonExtensionPackageUtil
            .parseLibVersion(dto.version)!!
        return MihonAvailableExtension(
            repo = repo,
            name = dto.name.removePrefix("Tachiyomi: ").trim(),
            pkgName = dto.pkg,
            versionName = dto.version,
            versionCode = dto.code,
            libVersion = libVersion,
            lang = dto.lang,
            isNsfw = dto.nsfw == 1,
            sources = dto.sources.orEmpty().map { s ->
                MihonAvailableExtensionSource(s.id, s.lang, s.name, s.baseUrl)
            },
            apkName = dto.apk,
            iconUrl = "${repo.baseUrl}/icon/${dto.pkg}.png",
        )
    }

    @Test
    fun tombstoneDetection() {
        val rows = listOf(
            MihonExtensionIndexEntryDto(
                name = "Outdated App",
                pkg = "eu.kanade.tachiyomi.extension.all.keiyoushi",
                apk = "x.apk",
                lang = "all",
                code = 1,
                version = "1.4.1",
                nsfw = 0,
            ),
            MihonExtensionIndexEntryDto(
                name = "Update to Mihon 0.20.1+",
                pkg = "eu.kanade.tachiyomi.extension.all.mihon",
                apk = "x.apk",
                lang = "all",
                code = 1,
                version = "1.4.1",
                nsfw = 0,
            ),
        )
        assertTrue(rows.isLegacyOutdatedPlaceholderIndex())

        val real = rows + MihonExtensionIndexEntryDto(
            name = "Tachiyomi: M",
            pkg = "eu.kanade.tachiyomi.extension.all.mangadex",
            apk = "x.apk",
            lang = "all",
            code = 1,
            version = "1.4.1",
            nsfw = 0,
        )
        assertFalse(real.isLegacyOutdatedPlaceholderIndex())
        assertFalse(emptyList<MihonExtensionIndexEntryDto>().isLegacyOutdatedPlaceholderIndex())
    }

    @Test
    fun legacyRetirementFallsBackToModernJsonAndProtobufIndexesWithoutPointer() {
        assertEquals(
            listOf("https://repo.example/extensions/index.json", "https://repo.example/extensions/index.pb"),
            legacyModernIndexCandidates("https://repo.example/extensions"),
        )
    }

    @Test
    fun explicitMigrationPointerIsTriedBeforeModernIndexConventions() {
        assertEquals(
            listOf(
                "https://repo.example/extensions/index-v2.json",
                "https://repo.example/extensions/index.json",
                "https://repo.example/extensions/index.pb",
            ),
            legacyModernIndexCandidates(
                "https://repo.example/extensions/",
                "https://repo.example/extensions/index-v2.json",
            ),
        )
    }

    @Test
    fun relativeIndexPointersResolveInsideRepositoryRootWithoutTrailingSlash() {
        assertEquals(
            "https://repo.example/extensions/index-v2/index.pb",
            resolveRepoIndexUrlFromBase(
                "https://repo.example/extensions",
                "index-v2/index.pb",
            ),
        )
    }
}
