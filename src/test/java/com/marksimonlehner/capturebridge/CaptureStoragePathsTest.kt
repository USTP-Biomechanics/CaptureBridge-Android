package com.marksimonlehner.capturebridge

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStoragePathsTest {
    @Test
    fun resolveDirectCaptureDirectory_rejectsTraversalNestedAndPendingNames() {
        val root = Files.createTempDirectory("capturebridge-path-test").toFile()
        try {
            assertNotNull(resolveDirectCaptureDirectory(root, "trial_20260710_120000"))
            assertNull(resolveDirectCaptureDirectory(root, "../outside"))
            assertNull(resolveDirectCaptureDirectory(root, "nested/capture"))
            assertNull(resolveDirectCaptureDirectory(root, "."))
            assertNull(resolveDirectCaptureDirectory(root, ".."))
            assertNull(resolveDirectCaptureDirectory(root, "pending_1234"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteFinalizedCaptureDirectories_preservesPendingArmDirectory() {
        val root = Files.createTempDirectory("capturebridge-delete-test").toFile()
        val pending = root.resolve("pending_abcd").apply { mkdirs() }
        val finalized = root.resolve("trial_20260710_120000").apply { mkdirs() }
        pending.resolve("armed.tmp").writeText("keep")
        finalized.resolve("capture.mp4").writeText("delete")

        try {
            assertTrue(deleteFinalizedCaptureDirectories(root))
            assertTrue(pending.isDirectory)
            assertTrue(pending.resolve("armed.tmp").isFile)
            assertFalse(finalized.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
