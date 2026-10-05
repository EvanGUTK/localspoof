package dev.localspoof.android

import kotlinx.serialization.json.*
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64

internal data class MobileUpdateRelease(val version: String, val versionCode: Long, val minOS: Int,
    val protocolMajor: Int, val protocolMin: Int, val protocolMax: Int,
    val url: String, val size: Long, val sha256: String, val signerSha256: String, val notes: String)

/** Verify the original publisher's signature over exact bounded bytes before interpreting JSON. */
internal object MobileUpdateCatalog {
    const val MAX_CATALOG = 65536
    const val MAX_SIGNATURE = 2048
    const val MAX_APK = 128L * 1024 * 1024
    const val SIGNER = "862de591bf8bf3f8ae8e3109018fb5dfed0731d775c3acef0808c0cebdf46102"
    const val PACKAGE = "dev.localspoof.android"
    const val PREFIX = "https://github.com/EvanGUTK/localspoof-releases/releases/download/"
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun certificate(bytes: ByteArray): X509Certificate {
        require(bytes.size in 1..8192 && digest(bytes) == SIGNER) { "UPDATE_CERTIFICATE" }
        return CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
    }
    fun artifactUrl(url: String, version: String, android: Boolean = true): Boolean = runCatching {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        val filename = if (android) "localspoof-$version.apk" else "LocalSpoof_${version}_x64-setup.exe"
        require(url.startsWith(PREFIX) && uri.rawPath == uri.path && !uri.path.contains(".."))
        val pieces = url.removePrefix(PREFIX).split('/')
        require(pieces.size == 2 && pieces[0] in setOf(version, "v$version") && pieces[1] == filename)
        true
    }.getOrDefault(false)
    fun redirectAllowed(initial: String, next: String): Boolean = runCatching {
        require(next.length <= 8192)
        val uri = URI(next)
        require(uri.scheme == "https" && uri.port == -1 && uri.userInfo == null && uri.fragment == null)
        val filename = URI(initial).path.substringAfterLast('/')
        val taggedCatalog = filename in setOf("catalog.json", "catalog.json.sig") && uri.host == "github.com" && uri.rawQuery == null && uri.rawPath == uri.path &&
            uri.path.matches(Regex("/EvanGUTK/localspoof-releases/releases/download/v?[0-9]+\\.[0-9]+\\.[0-9]+/" + Regex.escape(filename)))
        val asset = uri.host in setOf("objects.githubusercontent.com", "release-assets.githubusercontent.com") && uri.rawPath.startsWith("/github-production-release-asset")
        taggedCatalog || asset
    }.getOrDefault(false)
    fun parse(raw: ByteArray, detached: ByteArray, certificate: X509Certificate): MobileUpdateRelease {
        require(raw.size in 1..MAX_CATALOG && detached.size in 1..MAX_SIGNATURE)
        require(digest(certificate.encoded) == SIGNER)
        verifyDetached(raw, detached, certificate.publicKey)
        return parseVerified(raw)
    }
    internal fun verifyDetached(raw: ByteArray, detached: ByteArray, key: java.security.PublicKey) {
        require(raw.size in 1..MAX_CATALOG && detached.size in 1..MAX_SIGNATURE)
        val signatureText = detached.toString(Charsets.US_ASCII).trim()
        require(signatureText.matches(Regex("[A-Za-z0-9+/]+={0,2}")))
        val signature = Base64.getDecoder().decode(signatureText)
        require(signature.size in 256..512)
        require(Signature.getInstance("SHA256withRSA").run { initVerify(key); update(raw); verify(signature) }) { "UPDATE_CATALOG_SIGNATURE" }
    }
    internal fun parseVerified(raw: ByteArray): MobileUpdateRelease {
        require(raw.size in 1..MAX_CATALOG)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        require(!text.startsWith('\uFEFF'))
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("schemaVersion", "version", "notes", "pub_date", "platforms", "releases"))
        fun JsonObject.string(key: String, max: Int = 300): String {
            val value = getValue(key).jsonPrimitive; require(value.isString)
            return value.content.also { require(it.length in 1..max && it.none { c -> c == '\u0000' }) }
        }
        fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.let { require(!it.isString); it.long }
        require(root.number("schemaVersion") == 1L)
        val version = root.string("version").also { require(it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) }
        val notesPrimitive = root.getValue("notes").jsonPrimitive; require(notesPrimitive.isString && notesPrimitive.content.length <= 8000)
        val notes = notesPrimitive.content
        Instant.parse(root.string("pub_date"))
        val releases = root.getValue("releases").jsonArray; require(releases.size in 1..2)
        val objects = releases.map { it.jsonObject }; require(objects.map { it.string("platform") }.distinct().size == objects.size)
        require(objects.all { it.string("platform") in setOf("android", "windows-x86_64") })
        val android = objects.single { it.string("platform") == "android" }
        require(android.keys == setOf("platform", "version", "versionCode", "minOS", "protocolMajor", "protocolMin", "protocolMax", "url", "size", "sha256", "signerSha256"))
        require(android.string("version") == version && artifactUrl(android.string("url", 600), version))
        val code = android.number("versionCode").also { require(it in 1..Int.MAX_VALUE) }
        val minOS = android.number("minOS").also { require(it in 29..100) }.toInt()
        val major = android.number("protocolMajor").also { require(it in 1..100) }.toInt()
        val minimum = android.number("protocolMin").also { require(it in 0..100) }.toInt()
        val maximum = android.number("protocolMax").also { require(it in minimum.toLong()..100) }.toInt()
        val size = android.number("size").also { require(it in 1..MAX_APK) }
        val hash = android.string("sha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val signer = android.string("signerSha256").also { require(it == SIGNER) }
        val platforms = root.getValue("platforms").jsonObject
        val windows = objects.singleOrNull { it.string("platform") == "windows-x86_64" }
        if (windows == null) require(platforms.isEmpty()) else {
            require(windows.keys == setOf("platform", "version", "minOS", "protocolMajor", "protocolMin", "protocolMax", "url", "size", "sha256", "signature"))
            require(windows.string("version") == version && artifactUrl(windows.string("url", 600), version, false))
            require(windows.number("size") in 1..256L * 1024 * 1024 && windows.number("minOS") in 19041..1000000)
            require(windows.number("protocolMajor") == major.toLong() && windows.number("protocolMin") == minimum.toLong() && windows.number("protocolMax") == maximum.toLong())
            require(windows.string("sha256").matches(Regex("[a-f0-9]{64}")))
            val platform = platforms.getValue("windows-x86_64").jsonObject
            require(platforms.keys == setOf("windows-x86_64") && platform.keys == setOf("url", "signature"))
            require(platform.string("url", 600) == windows.string("url", 600) && platform.string("signature", 2048) == windows.string("signature", 2048))
        }
        return MobileUpdateRelease(version, code, minOS, major, minimum, maximum, android.string("url", 600), size, hash, signer, notes)
    }
    fun compatible(release: MobileUpdateRelease, installedCode: Long, sdk: Int, major: Int = 1, minor: Int = 6): Boolean =
        release.versionCode > installedCode && release.minOS <= sdk && release.protocolMajor == major && minor in release.protocolMin..release.protocolMax
}

internal data class MobileApkIdentity(val packageName: String, val version: String?, val code: Long, val minSdk: Int, val signers: List<String>)
internal fun mobileValidateUpgrade(installed: MobileApkIdentity, archive: MobileApkIdentity, release: MobileUpdateRelease, sdk: Int) {
    require(installed.packageName == MobileUpdateCatalog.PACKAGE && archive.packageName == installed.packageName)
    require(archive.code == release.versionCode && archive.code > installed.code && archive.version == release.version)
    require(archive.minSdk == release.minOS && archive.minSdk <= sdk)
    require(archive.signers == listOf(MobileUpdateCatalog.SIGNER) && installed.signers == listOf(MobileUpdateCatalog.SIGNER))
    require(release.signerSha256 == MobileUpdateCatalog.SIGNER)
}
