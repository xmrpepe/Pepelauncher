package io.pepe.launcher.linux.display

import android.view.KeyEvent

/** Android key codes → Linux evdev key codes (linux/input-event-codes.h), as wl_keyboard expects. */
object KeyMapping {

    private val LETTERS = intArrayOf(
        30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, // A..M
        49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44, // N..Z
    )

    private val TABLE: Map<Int, Int> = buildMap {
        for (i in 0 until 26) put(KeyEvent.KEYCODE_A + i, LETTERS[i])
        for (i in 1..9) put(KeyEvent.KEYCODE_0 + i, 1 + i) // KEY_1 = 2 … KEY_9 = 10
        put(KeyEvent.KEYCODE_0, 11)
        for (i in 0 until 10) put(KeyEvent.KEYCODE_F1 + i, 59 + i) // F1..F10
        put(KeyEvent.KEYCODE_F11, 87)
        put(KeyEvent.KEYCODE_F12, 88)
        put(KeyEvent.KEYCODE_ESCAPE, 1)
        put(KeyEvent.KEYCODE_BACK, 1)
        put(KeyEvent.KEYCODE_MINUS, 12)
        put(KeyEvent.KEYCODE_EQUALS, 13)
        put(KeyEvent.KEYCODE_DEL, 14)
        put(KeyEvent.KEYCODE_TAB, 15)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 26)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 27)
        put(KeyEvent.KEYCODE_ENTER, 28)
        put(KeyEvent.KEYCODE_CTRL_LEFT, 29)
        put(KeyEvent.KEYCODE_SEMICOLON, 39)
        put(KeyEvent.KEYCODE_APOSTROPHE, 40)
        put(KeyEvent.KEYCODE_GRAVE, 41)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, 42)
        put(KeyEvent.KEYCODE_BACKSLASH, 43)
        put(KeyEvent.KEYCODE_COMMA, 51)
        put(KeyEvent.KEYCODE_PERIOD, 52)
        put(KeyEvent.KEYCODE_SLASH, 53)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, 54)
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 55)
        put(KeyEvent.KEYCODE_ALT_LEFT, 56)
        put(KeyEvent.KEYCODE_SPACE, 57)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 58)
        put(KeyEvent.KEYCODE_NUM_LOCK, 69)
        put(KeyEvent.KEYCODE_SCROLL_LOCK, 70)
        put(KeyEvent.KEYCODE_NUMPAD_7, 71)
        put(KeyEvent.KEYCODE_NUMPAD_8, 72)
        put(KeyEvent.KEYCODE_NUMPAD_9, 73)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 74)
        put(KeyEvent.KEYCODE_NUMPAD_4, 75)
        put(KeyEvent.KEYCODE_NUMPAD_5, 76)
        put(KeyEvent.KEYCODE_NUMPAD_6, 77)
        put(KeyEvent.KEYCODE_NUMPAD_ADD, 78)
        put(KeyEvent.KEYCODE_NUMPAD_1, 79)
        put(KeyEvent.KEYCODE_NUMPAD_2, 80)
        put(KeyEvent.KEYCODE_NUMPAD_3, 81)
        put(KeyEvent.KEYCODE_NUMPAD_0, 82)
        put(KeyEvent.KEYCODE_NUMPAD_DOT, 83)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, 96)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, 97)
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 98)
        put(KeyEvent.KEYCODE_SYSRQ, 99)
        put(KeyEvent.KEYCODE_ALT_RIGHT, 100)
        put(KeyEvent.KEYCODE_MOVE_HOME, 102)
        put(KeyEvent.KEYCODE_DPAD_UP, 103)
        put(KeyEvent.KEYCODE_PAGE_UP, 104)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 105)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 106)
        put(KeyEvent.KEYCODE_MOVE_END, 107)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 108)
        put(KeyEvent.KEYCODE_PAGE_DOWN, 109)
        put(KeyEvent.KEYCODE_INSERT, 110)
        put(KeyEvent.KEYCODE_FORWARD_DEL, 111)
        put(KeyEvent.KEYCODE_BREAK, 119)
        put(KeyEvent.KEYCODE_META_LEFT, 125)
        put(KeyEvent.KEYCODE_META_RIGHT, 126)
        put(KeyEvent.KEYCODE_MENU, 127)
    }

    /** evdev code, or -1 when the key has no Linux equivalent. */
    fun toEvdev(androidKeyCode: Int): Int = TABLE[androidKeyCode] ?: -1
}
