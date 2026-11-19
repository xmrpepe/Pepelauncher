package io.pepe.launcher.linux.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UrlFinderTest {

    @Test
    fun `finds urls in order without duplicates`() {
        val text = """
            :: Synchronizing package databases...
            see https://archlinuxarm.org/forum, or http://example.com/a?b=c&d=e.
            again https://archlinuxarm.org/forum
            ftp://ftp.example.org/pub file:///etc/pacman.conf
        """.trimIndent()

        assertThat(UrlFinder.find(text)).containsExactly(
            "https://archlinuxarm.org/forum",
            "http://example.com/a?b=c&d=e",
            "ftp://ftp.example.org/pub",
            "file:///etc/pacman.conf",
        ).inOrder()
    }

    @Test
    fun `keeps balanced parentheses but drops a closing one from prose`() {
        assertThat(UrlFinder.find("(https://en.wikipedia.org/wiki/Mako_(shark))"))
            .containsExactly("https://en.wikipedia.org/wiki/Mako_(shark)")
        assertThat(UrlFinder.find("(see https://example.com)")).containsExactly("https://example.com")
    }

    @Test
    fun `ignores bare schemes and plain text`() {
        assertThat(UrlFinder.find("http:// nothing here, example.com")).isEmpty()
    }
}
