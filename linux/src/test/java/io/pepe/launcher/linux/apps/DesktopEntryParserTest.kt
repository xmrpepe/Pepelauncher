package io.pepe.launcher.linux.apps

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class DesktopEntryParserTest {

    private val mousepad = """
        [Desktop Entry]
        Name=Mousepad
        Name[de]=Mousepad (de)
        Name[pt_BR]=Mousepad (pt_BR)
        Name[sr@latin]=Mousepad (sr@latin)
        Comment=Simple Text Editor
        Exec=mousepad %U
        Icon=org.xfce.mousepad
        Terminal=false
        StartupNotify=true
        Type=Application
        Categories=GTK;Utility;TextEditor;
        MimeType=text/plain;

        [Desktop Action window]
        Name=New Window
        Exec=mousepad --new-window %U
    """.trimIndent()

    @Test
    fun `parses a typical application`() {
        val e = DesktopEntryParser.parse("org.xfce.mousepad", mousepad, Locale.ENGLISH, "/usr/share/applications/org.xfce.mousepad.desktop")!!
        assertThat(e.name).isEqualTo("Mousepad")
        assertThat(e.command).containsExactly("mousepad")
        assertThat(e.icon).isEqualTo("org.xfce.mousepad")
        assertThat(e.terminal).isFalse()
        assertThat(e.categories).containsExactly("GTK", "Utility", "TextEditor").inOrder()
        assertThat(e.comment).isEqualTo("Simple Text Editor")
        assertThat(e.isVisible()).isTrue()
    }

    @Test
    fun `actions and other groups do not override the main entry`() {
        val e = DesktopEntryParser.parse("m", mousepad, Locale.ENGLISH)!!
        assertThat(e.exec).isEqualTo("mousepad %U")
    }

    @Test
    fun `localised names follow the spec's matching order`() {
        fun nameFor(locale: Locale) = DesktopEntryParser.parse("m", mousepad, locale)!!.name
        assertThat(nameFor(Locale.GERMANY)).isEqualTo("Mousepad (de)")         // de_DE → de
        assertThat(nameFor(Locale("pt", "BR"))).isEqualTo("Mousepad (pt_BR)")
        assertThat(nameFor(Locale("pt", "PT"))).isEqualTo("Mousepad")          // no pt, no pt_PT
        assertThat(nameFor(Locale("sr", "RS", "latin"))).isEqualTo("Mousepad (sr@latin)")
        assertThat(nameFor(Locale.FRENCH)).isEqualTo("Mousepad")
    }

    @Test
    fun `strips file and url field codes, expands the others`() {
        assertThat(DesktopEntryParser.parseExec("foo %f %F %u %U %d %D %n %N %v %m"))
            .containsExactly("foo")
        assertThat(DesktopEntryParser.parseExec("foo %i --title=%c", name = "Foo", icon = "foo-icon"))
            .containsExactly("foo", "--icon", "foo-icon", "--title=Foo").inOrder()
        assertThat(DesktopEntryParser.parseExec("foo %i")).containsExactly("foo")
        assertThat(DesktopEntryParser.parseExec("foo %k", desktopFile = "/a/foo.desktop"))
            .containsExactly("foo", "/a/foo.desktop").inOrder()
        assertThat(DesktopEntryParser.parseExec("printf 100%%")).containsExactly("printf", "100%").inOrder()
    }

    @Test
    fun `handles quoting and escapes`() {
        assertThat(DesktopEntryParser.parseExec("""sh -c "echo \"hi \${'$'}USER\" \\ \`x\`" %U"""))
            .containsExactly("sh", "-c", """echo "hi ${'$'}USER" \ `x`""").inOrder()
        assertThat(DesktopEntryParser.parseExec("""env "A=b c" app""")).containsExactly("env", "A=b c", "app").inOrder()
        assertThat(DesktopEntryParser.parseExec("""app """"")).containsExactly("app", "").inOrder()
        assertThat(DesktopEntryParser.parseExec("  app   --flag  ")).containsExactly("app", "--flag").inOrder()
    }

    @Test
    fun `string escapes are decoded before exec parsing`() {
        val text = "[Desktop Entry]\nType=Application\nName=A\\sB\nExec=app\\s--x\n"
        val e = DesktopEntryParser.parse("a", text, Locale.ENGLISH)!!
        assertThat(e.name).isEqualTo("A B")
        assertThat(e.command).containsExactly("app", "--x").inOrder()
    }

    @Test
    fun `visibility honours NoDisplay, Hidden, OnlyShowIn and NotShowIn`() {
        fun entry(extra: String) = DesktopEntryParser.parse(
            "x", "[Desktop Entry]\nType=Application\nName=X\nExec=x\n$extra\n", Locale.ENGLISH
        )!!
        assertThat(entry("NoDisplay=true").isVisible()).isFalse()
        assertThat(entry("Hidden=true").isVisible()).isFalse()
        assertThat(entry("OnlyShowIn=GNOME;KDE;").isVisible()).isFalse()
        assertThat(entry("OnlyShowIn=GNOME;pepelauncher;").isVisible()).isTrue()
        assertThat(entry("NotShowIn=pepelauncher;").isVisible()).isFalse()
        assertThat(entry("NotShowIn=GNOME;").isVisible()).isTrue()
        assertThat(entry("NoDisplay=false").isVisible()).isTrue()
    }

    @Test
    fun `terminal apps, working directory and wm class`() {
        val e = DesktopEntryParser.parse(
            "htop",
            "[Desktop Entry]\nType=Application\nName=htop\nExec=htop\nTerminal=true\nPath=/tmp\nStartupWMClass=Htop\nTryExec=htop\n",
            Locale.ENGLISH
        )!!
        assertThat(e.terminal).isTrue()
        assertThat(e.workingDirectory).isEqualTo("/tmp")
        assertThat(e.startupWmClass).isEqualTo("Htop")
        assertThat(e.tryExec).isEqualTo("htop")
    }

    @Test
    fun `rejects non-applications and incomplete entries`() {
        assertThat(DesktopEntryParser.parse("l", "[Desktop Entry]\nType=Link\nName=L\nURL=https://x\n")).isNull()
        assertThat(DesktopEntryParser.parse("d", "[Desktop Entry]\nType=Directory\nName=D\n")).isNull()
        assertThat(DesktopEntryParser.parse("n", "[Desktop Entry]\nType=Application\nExec=x\n")).isNull()
        assertThat(DesktopEntryParser.parse("e", "[Desktop Entry]\nType=Application\nName=E\n")).isNull()
        assertThat(DesktopEntryParser.parse("g", "[Something Else]\nType=Application\nName=G\nExec=g\n")).isNull()
        assertThat(DesktopEntryParser.parse("empty", "")).isNull()
    }

    @Test
    fun `lists support escaped separators`() {
        assertThat(DesktopEntryParser.list("a;b\\;c;;d")).containsExactly("a", "b;c", "d").inOrder()
        assertThat(DesktopEntryParser.list(null)).isEmpty()
    }

    @Test
    fun `desktop file ids follow the spec`() {
        assertThat(DesktopEntryParser.idFor("org.xfce.mousepad.desktop")).isEqualTo("org.xfce.mousepad")
        assertThat(DesktopEntryParser.idFor("kde4/kate.desktop")).isEqualTo("kde4-kate")
    }

    @Test
    fun `comments and blank lines are ignored, last duplicate wins`() {
        val e = DesktopEntryParser.parse(
            "c", "# c\n\n[Desktop Entry]\n# Name=Wrong\nType=Application\nName=First\nName=Second\nExec=c\n", Locale.ENGLISH
        )!!
        assertThat(e.name).isEqualTo("Second")
    }
}
