package dev.localspoof.android

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class MobileUpdateCatalogTest {
    private fun catalog(): JsonObject = Json.parseToJsonElement("""{"schemaVersion":1,"version":"0.9.0","notes":"Changes","pub_date":"2026-10-05T00:00:00Z","platforms":{"windows-x86_64":{"url":"https://github.com/EvanGUTK/localspoof-releases/releases/download/v0.9.0/LocalSpoof_0.9.0_x64-setup.exe","signature":"test-minisign"}},"releases":[{"platform":"android","version":"0.9.0","versionCode":9,"minOS":29,"protocolMajor":1,"protocolMin":6,"protocolMax":6,"url":"https://github.com/EvanGUTK/localspoof-releases/releases/download/v0.9.0/localspoof-0.9.0.apk","size":1234,"sha256":"${"a".repeat(64)}","signerSha256":"${MobileUpdateCatalog.SIGNER}"},{"platform":"windows-x86_64","version":"0.9.0","minOS":19041,"protocolMajor":1,"protocolMin":6,"protocolMax":6,"url":"https://github.com/EvanGUTK/localspoof-releases/releases/download/v0.9.0/LocalSpoof_0.9.0_x64-setup.exe","size":2000,"sha256":"${"b".repeat(64)}","signature":"test-minisign"}]}""").jsonObject
    private fun bytes(root: JsonObject = catalog()) = root.toString().toByteArray()
    private fun altered(field: String, value: JsonElement): ByteArray {
        val root = catalog(); val releases = root.getValue("releases").jsonArray
        return bytes(JsonObject(root + ("releases" to JsonArray(listOf(JsonObject(releases[0].jsonObject + (field to value)), releases[1])))))
    }
    @Test fun exactSharedCatalogSupportsCurrentAndNextVersionWhileRejectingDowngradesAndIncompatiblePeers() {
        val release = MobileUpdateCatalog.parseVerified(bytes())
        assertEquals(9L, release.versionCode); assertEquals("Changes", release.notes)
        assertTrue(MobileUpdateCatalog.compatible(release, 8, 29))
        assertFalse(MobileUpdateCatalog.compatible(release, 9, 36)); assertFalse(MobileUpdateCatalog.compatible(release, 10, 36))
        assertFalse(MobileUpdateCatalog.compatible(release, 8, 28)); assertFalse(MobileUpdateCatalog.compatible(release, 8, 36, 1, 5))
        assertFalse(MobileUpdateCatalog.compatible(release, 8, 36, 2, 6))
    }
    @Test fun rawPublisherSignatureCoversExactBytesAndRejectsTamperingWrongKeyAndOversize() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val raw = bytes(); val signed = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(raw); sign() }
        val detached = Base64.getEncoder().encode(signed)
        MobileUpdateCatalog.verifyDetached(raw, detached, pair.public)
        assertTrue(runCatching { MobileUpdateCatalog.verifyDetached(raw + ' '.code.toByte(), detached, pair.public) }.isFailure)
        assertTrue(runCatching { MobileUpdateCatalog.verifyDetached(raw, "bad".toByteArray(), pair.public) }.isFailure)
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertTrue(runCatching { MobileUpdateCatalog.verifyDetached(raw, detached, other.public) }.isFailure)
        assertTrue(runCatching { MobileUpdateCatalog.verifyDetached(ByteArray(65537), detached, pair.public) }.isFailure)
        assertTrue(runCatching { MobileUpdateCatalog.verifyDetached(raw, ByteArray(2049), pair.public) }.isFailure)
    }
    @Test fun signaturesAreNotAnExcuseToAcceptUnexpectedSchemaPlatformMetadataOrURLs() {
        listOf(altered("signerSha256", JsonPrimitive("c".repeat(64))), altered("size", JsonPrimitive(MobileUpdateCatalog.MAX_APK + 1)),
            altered("versionCode", JsonPrimitive("9")), altered("platform", JsonPrimitive("ios")), altered("extra", JsonPrimitive(true)),
            altered("url", JsonPrimitive("https://example.com/localspoof-0.9.0.apk")), altered("protocolMin", JsonPrimitive(7))).forEach {
            assertTrue(runCatching { MobileUpdateCatalog.parseVerified(it) }.isFailure)
        }
        val root = catalog(); val platforms = root.getValue("platforms").jsonObject
        val bad = JsonObject(root + ("platforms" to JsonObject(platforms + ("windows-x86_64" to JsonObject(platforms.getValue("windows-x86_64").jsonObject + ("signature" to JsonPrimitive("different")))))))
        assertTrue(runCatching { MobileUpdateCatalog.parseVerified(bytes(bad)) }.isFailure)
        assertTrue(runCatching { MobileUpdateCatalog.parseVerified(bytes() + byteArrayOf(0xc3.toByte())) }.isFailure)
    }
    @Test fun latestRedirectAllowsOnlyTaggedSameRepositoryCatalogAndReviewedAssetHosts() {
        val latest = "https://github.com/EvanGUTK/localspoof-releases/releases/latest/download/catalog.json"
        assertTrue(MobileUpdateCatalog.redirectAllowed(latest, "https://github.com/EvanGUTK/localspoof-releases/releases/download/v0.9.0/catalog.json"))
        assertTrue(MobileUpdateCatalog.redirectAllowed(latest, "https://release-assets.githubusercontent.com/github-production-release-asset/12/file?token=temporary"))
        listOf("https://github.com/Other/repo/releases/download/v0.9.0/catalog.json", "https://github.com/EvanGUTK/localspoof-releases/releases/download/v0.9.0/catalog.json.sig",
            "http://release-assets.githubusercontent.com/github-production-release-asset/file", "https://release-assets.githubusercontent.com:443/github-production-release-asset/file",
            "https://user@release-assets.githubusercontent.com/github-production-release-asset/file", "https://127.0.0.1/file", "https://example.com/file").forEach { assertFalse(MobileUpdateCatalog.redirectAllowed(latest, it)) }
        assertFalse(MobileUpdateCatalog.artifactUrl("https://github.com/EvanGUTK/localspoof-releases/releases/download/v0.9.0/%6cocalspoof-0.9.0.apk", "0.9.0"))
        assertFalse(MobileUpdateCatalog.artifactUrl("https://github.com/EvanGUTK/localspoof-releases/releases/download/../localspoof-0.9.0.apk", "0.9.0"))
    }
    @Test fun packageUpgradeRejectsOtherPackagesReplaySignerRotationMultipleSignersAndWrongSdk() {
        val release = MobileUpdateCatalog.parseVerified(bytes())
        val installed = MobileApkIdentity(MobileUpdateCatalog.PACKAGE, "0.8.0", 8, 29, listOf(MobileUpdateCatalog.SIGNER))
        val archive = installed.copy(version = "0.9.0", code = 9)
        mobileValidateUpgrade(installed, archive, release, 29)
        listOf(archive.copy(packageName = "other.app"), archive.copy(code = 8), archive.copy(version = "0.10.0"), archive.copy(minSdk = 36),
            archive.copy(signers = emptyList()), archive.copy(signers = listOf("wrong")), archive.copy(signers = listOf(MobileUpdateCatalog.SIGNER, "other"))).forEach {
            assertTrue(runCatching { mobileValidateUpgrade(installed, it, release, 29) }.isFailure)
        }
        assertTrue(runCatching { mobileValidateUpgrade(installed.copy(signers = listOf("debug")), archive, release, 36) }.isFailure)
    }

    @Test fun interruptedInstallRecoveryRequiresConfirmedOsAbsenceAndBoundsCleanupAttempts() {
        val retained = mutableListOf(1)
        assertTrue(mobileReconcileUpdateSessions({ retained }) { error("OS session still committed") })
        assertTrue(mobileReconcileUpdateSessions({ error("Unknown OS state") }) {})
        assertFalse(mobileReconcileUpdateSessions({ retained.toList() }) { retained.remove(it) })
        val many = (1..17).toMutableList(); var attempts = 0
        assertTrue(mobileReconcileUpdateSessions({ many.toList() }) { attempts++; many.remove(it) })
        assertEquals(16, attempts)
        assertFalse(mobileReconcileUpdateSessions({ many.toList() }) { many.remove(it) })
    }

}
