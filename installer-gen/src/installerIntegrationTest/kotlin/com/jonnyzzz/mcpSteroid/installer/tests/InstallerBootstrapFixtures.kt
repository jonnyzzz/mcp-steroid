/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer.tests

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * Shared fixtures for the two installer-bootstrap Docker tests (POSIX `install.sh` in
 * [InstallerBootstrapTest] and PowerShell `install.ps1` in [InstallerBootstrapPs1Test]). Kept
 * script-agnostic on purpose: the fake JDK tar.gz + tempdir/sha256/permissions helpers are identical
 * between the two lanes; per-test-class specifics (container images, log prefix, devrig zip shape)
 * stay local to each test.
 */

/** Nginx side-car serving the fixture zip/tar.gz over real HTTP to the install container. */
const val NGINX_IMAGE = "nginx:alpine"

/** HOME with a space catches quoting bugs in the installer scripts + downstream launcher wrappers. */
const val INSTALLER_HOME_DIR = "/home/tester one"

/** Constant baked into the generated scripts + into the content-addressed dir names the tests probe. */
const val INSTALLER_TEST_VERSION = "0.0.0-test"

fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ins ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun makeWorldReadable(dir: File) {
    dir.walkTopDown().forEach {
        it.setReadable(true, false)
        if (it.isDirectory) it.setExecutable(true, false)
    }
    dir.setExecutable(true, false)
}

fun createInstallerWorkDir(prefix: String): File {
    val d = File.createTempFile(prefix, "").let { it.delete(); File(it.absolutePath + "-dir") }
    d.mkdirs()
    return d
}

/**
 * Fake JDK tar.gz used by BOTH lanes: top dir `jdk/` (matches `javaHome="jdk"` baked into the
 * synthetic model), with an executable `bin/java` sh stub — enough that both install.sh's `-x
 * bin/java` check and install.ps1's `bin/java` fallback (see the pwsh-on-Linux branch in the
 * template) pass.
 */
fun buildFakeJdkTarGz(target: File) {
    val javaStub = "#!/bin/sh\necho 'java-stub 25'\nexit 0\n".toByteArray()
    GZIPOutputStream(FileOutputStream(target)).use { gz ->
        TarWriter(gz).use { tar ->
            tar.putDir("jdk/")
            tar.putDir("jdk/bin/")
            tar.putFile("jdk/bin/java", javaStub, mode = 0b111_101_101) // rwxr-xr-x
        }
    }
}

/**
 * Minimal POSIX (ustar) tar writer — the JDK has no built-in tar. Enough for a few small files with
 * stored unix permission bits so the unpacked `bin/java` keeps its +x bit (install.sh checks `-x`).
 */
class TarWriter(private val out: OutputStream) : AutoCloseable {
    fun putDir(name: String) = writeEntry(name, ByteArray(0), typeFlag = '5', mode = 0b111_101_101)
    fun putFile(name: String, data: ByteArray, mode: Int) = writeEntry(name, data, typeFlag = '0', mode = mode)

    private fun writeEntry(name: String, data: ByteArray, typeFlag: Char, mode: Int) {
        val header = ByteArray(512)
        putString(header, 0, name, 100)
        putOctal(header, 100, mode.toLong(), 8)
        putOctal(header, 108, 0, 8)
        putOctal(header, 116, 0, 8)
        putOctal(header, 124, data.size.toLong(), 12)
        putOctal(header, 136, 0, 12)
        header[156] = typeFlag.code.toByte()
        putString(header, 257, "ustar", 6)
        header[263] = '0'.code.toByte(); header[264] = '0'.code.toByte()
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var sum = 0
        for (b in header) sum += (b.toInt() and 0xff)
        putOctal(header, 148, sum.toLong(), 7)
        header[155] = ' '.code.toByte()
        out.write(header)
        out.write(data)
        val pad = (512 - data.size % 512) % 512
        if (pad > 0) out.write(ByteArray(pad))
    }

    private fun putString(buf: ByteArray, off: Int, s: String, max: Int) {
        val bytes = s.toByteArray()
        System.arraycopy(bytes, 0, buf, off, minOf(bytes.size, max - 1))
    }

    private fun putOctal(buf: ByteArray, off: Int, value: Long, len: Int) {
        val s = java.lang.Long.toOctalString(value).padStart(len - 1, '0')
        System.arraycopy(s.toByteArray(), 0, buf, off, len - 1)
        buf[off + len - 1] = 0
    }

    override fun close() {
        out.write(ByteArray(1024)) // two zero blocks terminate the archive
        out.flush()
    }
}
