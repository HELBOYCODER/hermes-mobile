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
    fun bundledRouterContainsServerAndArm64RuntimeWithoutUserCredentials() {
        val asset = File("src/main/assets/sandbox/router-runtime.bin")
        assertTrue("Router must be bundled in release builds", asset.exists())
        val dest = Files.createTempDirectory("hermes-router").toFile()
        try {
            asset.inputStream().use { TarExtractor.extract(it, dest, jvmFs) }
            assertTrue(File(dest, "usr/lib/node_modules/9router/app/custom-server.js").isFile)
            val native = File(dest, "usr/lib/hermes-9router-runtime/node_modules/better-sqlite3/prebuilds/linuxmusl-arm64.node")
            val header = native.inputStream().use { input -> ByteArray(20).also { assertEquals(20, input.read(it)) } }
            assertEquals(0x7f, header[0].toInt())
            assertEquals(183, (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8))
            assertTrue(File(dest, "usr/lib/hermes-9router-runtime/node_modules/sql.js/dist/sql-wasm.wasm").isFile)
            assertTrue("No user router database in application payload", !File(dest, "root/.9router/db.json").exists())
        } finally { dest.deleteRecursively() }
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
