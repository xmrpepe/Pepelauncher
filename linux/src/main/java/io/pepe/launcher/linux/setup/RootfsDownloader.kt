package io.pepe.launcher.linux.setup

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the rootfs tarball. Interrupted downloads resume from `<target>.part` with an HTTP
 * Range request, so setup can be retried after a failure without starting over.
 *
 * The finished file is only moved to [target] once its MD5 (published by Arch Linux ARM next to
 * the tarball as `<url>.md5`) — and, if configured, a user-supplied SHA-256 — match.
 */
class RootfsDownloader(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {

    class ChecksumMismatch(message: String) : IOException(message)
    class Cancelled : IOException("cancelled")

    fun interface Progress {
        fun onProgress(downloaded: Long, total: Long)
    }

    /** Returns [target], downloading it first if needed. */
    fun download(
        url: String,
        target: File,
        expectedSha256: String? = null,
        progress: Progress = Progress { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): File {
        target.parentFile?.mkdirs()
        val expectedMd5 = fetchMd5(url)

        if (target.exists() && verify(target, expectedMd5, expectedSha256)) return target
        target.delete()

        val part = File(target.parentFile, target.name + ".part")
        fetch(url, part, progress, isCancelled)

        if (!verify(part, expectedMd5, expectedSha256)) {
            part.delete()
            throw ChecksumMismatch("Checksum mismatch for ${target.name}; the download was discarded")
        }
        if (!part.renameTo(target)) throw IOException("Could not move ${part.name} into place")
        return target
    }

    private fun verify(file: File, md5: String, sha256: String?): Boolean {
        if (!md5.equals(digest(file, "MD5"), ignoreCase = true)) return false
        if (!sha256.isNullOrBlank() && !sha256.equals(digest(file, "SHA-256"), ignoreCase = true)) {
            return false
        }
        return true
    }

    /** `<hash>  <filename>` → `<hash>`. */
    fun fetchMd5(url: String): String {
        val text = connect("$url.md5", rangeStart = 0).let { (conn, _) ->
            try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        return parseChecksumFile(text) ?: throw IOException("Unreadable checksum file at $url.md5")
    }

    private fun fetch(url: String, part: File, progress: Progress, isCancelled: () -> Boolean) {
        val existing = if (part.exists()) part.length() else 0L
        val (conn, code) = connect(url, rangeStart = existing)
        try {
            val append = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false
                // Range starts at or past the end: the part file is already complete.
                416 -> return
                else -> throw IOException("HTTP $code for $url")
            }
            val offset = if (append) existing else 0L
            val total = conn.contentLengthLong.let { if (it >= 0) it + offset else -1L }
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    copy(input, out, offset, total, progress, isCancelled)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun copy(
        input: InputStream,
        out: FileOutputStream,
        offset: Long,
        total: Long,
        progress: Progress,
        isCancelled: () -> Boolean,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var done = offset
        var lastReport = 0L
        progress.onProgress(done, total)
        while (true) {
            if (isCancelled()) throw Cancelled()
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            done += read
            if (done - lastReport >= REPORT_EVERY) {
                lastReport = done
                progress.onProgress(done, total)
            }
        }
        out.fd.sync()
        progress.onProgress(done, total)
    }

    /**
     * Follows redirects by hand: HttpURLConnection refuses to follow http↔https redirects, and
     * Arch Linux ARM's geo-redirector sends them.
     */
    private fun connect(url: String, rangeStart: Long): Pair<HttpURLConnection, Int> {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            val conn = open(current).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "pepelauncher")
                if (rangeStart > 0) setRequestProperty("Range", "bytes=$rangeStart-")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw IOException("Redirect without Location from $current")
                conn.disconnect()
                current = URL(current, location)
                return@repeat
            }
            return conn to code
        }
        throw IOException("Too many redirects for $url")
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val REPORT_EVERY = 512L * 1024
        private const val TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 6

        fun parseChecksumFile(text: String): String? =
            text.trim().split(Regex("\\s+")).firstOrNull()
                ?.takeIf { it.matches(Regex("[0-9a-fA-F]{32,128}")) }

        fun digest(file: File, algorithm: String): String {
            val md = MessageDigest.getInstance(algorithm)
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
