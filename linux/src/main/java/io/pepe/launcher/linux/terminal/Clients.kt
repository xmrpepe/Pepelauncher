package io.pepe.launcher.linux.terminal

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

private const val TAG = "pepe-terminal"

/** [TerminalSessionClient] with no-op defaults; subclasses override what they need. */
internal abstract class BaseSessionClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) = Unit
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message.orEmpty()) }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message.orEmpty()) }
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message, e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag ?: TAG, "", e)
    }
}

/** [TerminalViewClient] with sensible defaults for a touch-first terminal. */
internal abstract class BaseViewClient : TerminalViewClient {
    override fun onScale(scale: Float): Float = 1f
    override fun onSingleTapUp(e: MotionEvent) = Unit
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message.orEmpty()) }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message.orEmpty()) }
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message, e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag ?: TAG, "", e)
    }
}

/** Finds http(s), ftp and file URLs in terminal text, in order of appearance, without duplicates. */
object UrlFinder {
    private val URL = Regex("""(?:https?|ftp)://[^\s<>"'`]+|file:///[^\s<>"'`]+""")
    private const val TRAILING = ".,;:!?)]}>'\""

    fun find(text: String): List<String> =
        URL.findAll(text)
            .map { trimTrailing(it.value) }
            .filter { it.substringAfter("://").isNotEmpty() }
            .distinct()
            .toList()

    private fun trimTrailing(url: String): String {
        var end = url.length
        while (end > 0 && url[end - 1] in TRAILING) {
            // Keep a closing parenthesis that has a matching opening one, as in Wikipedia URLs.
            if (url[end - 1] == ')' && url.substring(0, end).count { it == '(' } >= url.substring(0, end).count { it == ')' }) break
            end--
        }
        return url.substring(0, end)
    }
}
