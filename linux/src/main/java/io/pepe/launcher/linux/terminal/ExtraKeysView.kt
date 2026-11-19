package io.pepe.launcher.linux.terminal

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The row of keys a phone keyboard lacks: Esc, Ctrl, Alt, Tab, arrows and a few symbols.
 * Ctrl and Alt are sticky for one key press (tap) or locked (long-press).
 */
class ExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    sealed class Key(val label: String) {
        class Code(label: String, val keyCode: Int) : Key(label)
        class Text(label: String, val text: String) : Key(label)
        class Modifier(label: String, val type: ModifierType) : Key(label)
    }

    enum class ModifierType { CTRL, ALT }

    private enum class State { OFF, ONCE, LOCKED }

    interface Listener {
        fun onKeyCode(keyCode: Int, ctrl: Boolean, alt: Boolean)
        fun onText(text: String, ctrl: Boolean, alt: Boolean)
    }

    var listener: Listener? = null

    private val states = mutableMapOf(ModifierType.CTRL to State.OFF, ModifierType.ALT to State.OFF)
    private val modifierViews = mutableMapOf<ModifierType, TextView>()

    val keys: List<Key> = listOf(
        Key.Code("ESC", KeyEvent.KEYCODE_ESCAPE),
        Key.Modifier("CTRL", ModifierType.CTRL),
        Key.Modifier("ALT", ModifierType.ALT),
        Key.Code("TAB", KeyEvent.KEYCODE_TAB),
        Key.Text("-", "-"),
        Key.Text("/", "/"),
        Key.Text("|", "|"),
        Key.Code("HOME", KeyEvent.KEYCODE_MOVE_HOME),
        Key.Code("↑", KeyEvent.KEYCODE_DPAD_UP),
        Key.Code("END", KeyEvent.KEYCODE_MOVE_END),
        Key.Code("←", KeyEvent.KEYCODE_DPAD_LEFT),
        Key.Code("↓", KeyEvent.KEYCODE_DPAD_DOWN),
        Key.Code("→", KeyEvent.KEYCODE_DPAD_RIGHT),
    )

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.BLACK)
        keys.forEach { key -> addView(buildButton(key), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)) }
    }

    /** Consumed by the terminal for the next key from the soft or hardware keyboard. */
    fun readModifier(type: ModifierType): Boolean {
        val state = states.getValue(type)
        if (state == State.ONCE) setState(type, State.OFF)
        return state != State.OFF
    }

    private fun buildButton(key: Key): TextView = TextView(context).apply {
        text = key.label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = android.graphics.Typeface.MONOSPACE
        isClickable = true
        isFocusable = false
        setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onTap(key)
        }
        if (key is Key.Modifier) {
            modifierViews[key.type] = this
            setOnLongClickListener {
                setState(key.type, if (states[key.type] == State.LOCKED) State.OFF else State.LOCKED)
                true
            }
        }
    }

    private fun onTap(key: Key) {
        when (key) {
            is Key.Modifier -> setState(
                key.type,
                if (states.getValue(key.type) == State.OFF) State.ONCE else State.OFF
            )

            is Key.Code -> listener?.onKeyCode(
                key.keyCode, readModifier(ModifierType.CTRL), readModifier(ModifierType.ALT)
            )

            is Key.Text -> listener?.onText(
                key.text, readModifier(ModifierType.CTRL), readModifier(ModifierType.ALT)
            )
        }
    }

    private fun setState(type: ModifierType, state: State) {
        states[type] = state
        modifierViews[type]?.apply {
            setBackgroundColor(
                when (state) {
                    State.OFF -> Color.TRANSPARENT
                    State.ONCE -> 0xFF3A5A40.toInt()
                    State.LOCKED -> 0xFF6A994E.toInt()
                }
            )
        }
    }
}
