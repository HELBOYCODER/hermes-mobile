package com.ersaz.hermesmobile

import com.ersaz.hermesmobile.sandbox.TarExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/** Extract the real Alpine minirootfs shipped in the APK and check the layout PRoot needs. */
class TarExtractorTest {
    private val jvmFs = object : TarExtractor.Fs {
        override fun symlink(target: String, path: String) {
            Files.createSymbolicLink(Paths.get(path), Paths.get(target))
        }
        override fun chmod(path: String, mode: Int) {
            val perms = mutableSetOf<PosixFilePermission>()
            val all = PosixFilePermission.values() // OWNER_READ..OTHERS_EXECUTE, high bit first
            for (i in 0 until 9) if (mode and (1 shl (8 - i)) != 0) perms += all[i]
            Files.setPosixFilePermissions(Paths.get(path), perms)
        }
    }

    @Test
    fun extractsAlpineRootfs() {
        val asset = File("src/main/assets/sandbox/alpine-rootfs.bin")
        assumeTrue("run scripts/fetch_sandbox.sh first", asset.exists())
        val dest = Files.createTempDirectory("rootfs").toFile()
        var entries = 0
        asset.inputStream().use { TarExtractor.extract(it, dest, jvmFs) { entries++ } }

        assertTrue("busybox present", File(dest, "bin/busybox").isFile)
        assertTrue("busybox executable", File(dest, "bin/busybox").canExecute())
        val sh = Paths.get(dest.path, "bin/sh")
        assertTrue("/bin/sh is a symlink", Files.isSymbolicLink(sh))
        assertEquals("/bin/busybox", Files.readSymbolicLink(sh).toString())
        assertTrue("apk present", File(dest, "sbin/apk").exists())
        assertTrue("alpine-release", File(dest, "etc/alpine-release").readText().startsWith("3."))
        assertTrue("many entries", entries > 400)
        dest.deleteRecursively()
    }
}
