package com.pennypilot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class UpdatePackageVerifierTest {
    @get:Rule val temporary = TemporaryFolder()
    private val contents = "APK test fixture".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(contents).joinToString("") { "%02x".format(it) }

    private fun apk(): File = File(temporary.root, "updates/PennyPilot-4.apk").apply {
        parentFile!!.mkdirs()
        writeBytes(contents)
    }

    private fun verify(file: File, digest: String = hash, size: Long = contents.size.toLong()): File =
        UpdatePackageVerifier.cachedFile(temporary.root, file.path, digest, 4, size)

    private fun identity(
        archivePackage: String = "com.pennypilot.app",
        archiveVersion: String = "0.4.0",
        archiveCode: Long = 4,
        installedCode: Long = 3,
        installedSigners: Set<String> = setOf("release-certificate"),
        archiveSigners: Set<String> = setOf("release-certificate"),
    ) = UpdatePackageVerifier.identity(
        "com.pennypilot.app", "0.4.0", 4, archivePackage, archiveVersion,
        archiveCode, installedCode, installedSigners, archiveSigners,
    )

    @Test fun validPrivateCacheFilePassesHashAndSizeChecks() {
        val file = apk()
        assertEquals(file.canonicalFile, verify(file))
    }

    @Test fun rejectsWrongHashAndSize() {
        assertEquals("HASH_MISMATCH", assertThrows(UpdateFailure::class.java) { verify(apk(), digest = "a".repeat(64)) }.code)
        assertEquals("INVALID_APK", assertThrows(UpdateFailure::class.java) { verify(apk(), size = contents.size + 1L) }.code)
    }

    @Test fun rejectsFilesOutsideDedicatedCacheDirectory() {
        val external = File(temporary.root, "PennyPilot-4.apk").apply { writeBytes(contents) }
        assertEquals("INVALID_APK", assertThrows(UpdateFailure::class.java) { verify(external) }.code)
        val nested = File(temporary.root, "updates/other/PennyPilot-4.apk").apply { parentFile!!.mkdirs(); writeBytes(contents) }
        assertEquals("INVALID_APK", assertThrows(UpdateFailure::class.java) { verify(nested) }.code)
    }

    @Test fun rejectsSymbolicLinkEscapingCacheDirectory() {
        val external = File(temporary.root, "outside.apk").apply { writeBytes(contents) }
        val link = File(temporary.root, "updates/PennyPilot-4.apk")
        link.parentFile!!.mkdirs()
        Files.createSymbolicLink(link.toPath(), external.toPath())
        assertEquals("INVALID_APK", assertThrows(UpdateFailure::class.java) { verify(link) }.code)
    }

    @Test fun rejectsPartialFilesAndInvalidMetadata() {
        val partial = File(temporary.root, "updates/PennyPilot-4.apk.part").apply { parentFile!!.mkdirs(); writeBytes(contents) }
        assertEquals("INVALID_APK", assertThrows(UpdateFailure::class.java) { verify(partial) }.code)
        assertEquals("INVALID_APK", assertThrows(UpdateFailure::class.java) { verify(apk(), size = 201L * 1024 * 1024) }.code)
        assertEquals("INVALID_APK", assertThrows(UpdateFailure::class.java) { verify(apk(), digest = "") }.code)
    }

    @Test fun acceptsSameSignerUpgrade() { identity() }

    @Test fun rejectsOldAndEqualBuilds() {
        for (installed in listOf(4L, 5L)) {
            assertEquals("OLD_VERSION", assertThrows(UpdateFailure::class.java) { identity(installedCode = installed) }.code)
        }
    }

    @Test fun rejectsUnexpectedPackageOrVersion() {
        assertEquals("INVALID_APK", assertThrows(UpdateFailure::class.java) { identity(archivePackage = "other.app") }.code)
        assertEquals("INVALID_APK", assertThrows(UpdateFailure::class.java) { identity(archiveVersion = "0.5.0") }.code)
        assertEquals("INVALID_APK", assertThrows(UpdateFailure::class.java) { identity(archiveCode = 5) }.code)
    }

    @Test fun rejectsDifferentMissingOrAdditionalSignersWithDownloadAdvice() {
        for (signers in listOf(setOf("debug-certificate"), emptySet(), setOf("release-certificate", "extra-certificate"))) {
            val error = assertThrows(UpdateFailure::class.java) { identity(archiveSigners = signers) }
            assertEquals("SIGNATURE_MISMATCH", error.code)
            assertTrue(error.message!!.contains("签名校验失败"))
            assertTrue(error.message!!.contains("官方发布页面"))
        }
        assertEquals("SIGNATURE_MISMATCH", assertThrows(UpdateFailure::class.java) { identity(installedSigners = emptySet(), archiveSigners = emptySet()) }.code)
    }
}
