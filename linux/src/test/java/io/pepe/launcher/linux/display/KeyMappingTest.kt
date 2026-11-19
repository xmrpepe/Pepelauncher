package io.pepe.launcher.linux.display

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeyMappingTest {
    @Test
    fun `letters, digits and function keys follow the evdev layout`() {
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_Q)).isEqualTo(16)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_A)).isEqualTo(30)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_Z)).isEqualTo(44)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_M)).isEqualTo(50)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_1)).isEqualTo(2)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_9)).isEqualTo(10)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_0)).isEqualTo(11)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_F1)).isEqualTo(59)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_F10)).isEqualTo(68)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_F12)).isEqualTo(88)
    }

    @Test
    fun `editing, navigation and modifier keys`() {
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_ENTER)).isEqualTo(28)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_DEL)).isEqualTo(14)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_FORWARD_DEL)).isEqualTo(111)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_DPAD_UP)).isEqualTo(103)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_DPAD_LEFT)).isEqualTo(105)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_CTRL_LEFT)).isEqualTo(29)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_SHIFT_LEFT)).isEqualTo(42)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_ALT_RIGHT)).isEqualTo(100)
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_BACK)).isEqualTo(1) // Escape
        assertThat(KeyMapping.toEvdev(KeyEvent.KEYCODE_VOLUME_UP)).isEqualTo(-1)
    }
}
