package io.pepe.launcher.linux.setup

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest

class RootfsDownloaderTest {

    private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val md5 = MessageDigest.getInstance("MD5").digest(payload).joinToString("") { "%02x".format(it) }
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val dir: File = Files.createTempDirectory("dl").toFile()
    private var md5File = "$md5  rootfs.tar.gz\n"
    private val rangeHeaders = mutableListOf<String?>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/os/rootfs.tar.gz") { ex -> serveTarball(ex) }
        server.createContext("/os/rootfs.tar.gz.md5") { ex -> send(ex, 200, md5File.toByteArray()) }
        server.createContext("/redirect/rootfs.tar.gz") { ex -> redirect(ex, "/os/rootfs.tar.gz") }
        server.createContext("/redirect/rootfs.tar.gz.md5") { ex -> redirect(ex, "/os/rootfs.tar.gz.md5") }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun serveTarball(ex: HttpExchange) {
        val range = ex.requestHeaders.getFirst("Range")
        rangeHeaders += range
        if (range != null) {
            val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
            if (start >= payload.size) return send(ex, 416, ByteArray(0))
            ex.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
            send(ex, 206, payload.copyOfRange(start, payload.size))
        } else {
            send(ex, 200, payload)
        }
    }

    private fun redirect(ex: HttpExchange, to: String) {
        ex.responseHeaders.add("Location", to)
        send(ex, 302, ByteArray(0))
    }

    private fun send(ex: HttpExchange, code: Int, body: ByteArray) {
        ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
    }

    @Test
    fun `downloads and verifies md5`() {
        val target = File(dir, "rootfs.tar.gz")
        RootfsDownloader().download("$base/os/rootfs.tar.gz", target)
        assertThat(target.readBytes()).isEqualTo(payload)
        assertThat(File(dir, "rootfs.tar.gz.part").exists()).isFalse()
    }

    @Test
    fun `resumes from a partial download with a range request`() {
        val target = File(dir, "rootfs.tar.gz")
        File(dir, "rootfs.tar.gz.part").writeBytes(payload.copyOfRange(0, 120_000))

        RootfsDownloader().download("$base/os/rootfs.tar.gz", target)

        assertThat(rangeHeaders).containsExactly("bytes=120000-")
        assertThat(target.readBytes()).isEqualTo(payload)
    }

    @Test
    fun `follows redirects for the tarball and checksum`() {
        val target = File(dir, "rootfs.tar.gz")
        RootfsDownloader().download("$base/redirect/rootfs.tar.gz", target)
        assertThat(target.readBytes()).isEqualTo(payload)
    }

    @Test
    fun `discards the download when the checksum does not match`() {
        md5File = "0123456789abcdef0123456789abcdef  rootfs.tar.gz\n"
        val target = File(dir, "rootfs.tar.gz")
        assertThrows(RootfsDownloader.ChecksumMismatch::class.java) {
            RootfsDownloader().download("$base/os/rootfs.tar.gz", target)
        }
        assertThat(target.exists()).isFalse()
        assertThat(File(dir, "rootfs.tar.gz.part").exists()).isFalse()
    }

    @Test
    fun `checks an optional sha256 too`() {
        val target = File(dir, "rootfs.tar.gz")
        RootfsDownloader().download("$base/os/rootfs.tar.gz", target, expectedSha256 = sha256)
        assertThat(target.exists()).isTrue()

        target.delete()
        assertThrows(RootfsDownloader.ChecksumMismatch::class.java) {
            RootfsDownloader().download("$base/os/rootfs.tar.gz", target, expectedSha256 = "ab".repeat(32))
        }
    }

    @Test
    fun `an already verified file is not downloaded again`() {
        val target = File(dir, "rootfs.tar.gz").apply { writeBytes(payload) }
        RootfsDownloader().download("$base/os/rootfs.tar.gz", target)
        assertThat(rangeHeaders).isEmpty()
    }

    @Test
    fun `parses checksum files`() {
        assertThat(RootfsDownloader.parseChecksumFile("23eec86365b24f7913c403e8f4e8719b  x.tar.gz\n"))
            .isEqualTo("23eec86365b24f7913c403e8f4e8719b")
        assertThat(RootfsDownloader.parseChecksumFile("<html>404</html>")).isNull()
    }
}
