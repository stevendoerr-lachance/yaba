package dev.steven.s3control.ble

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * S2 test mode (the S2 plan A1 "Test", B2): while it is off, the S2 driver refuses every command not yet
 * confirmed on a real S2 (remote lock, mute, backup code, off-road region, alarm, a wake from anything but
 * standby), and the feature set leaves them out. It does nothing for an S3. Stored on its own, next to the other
 * BLE stores, not in the app settings.
 */
class S2Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("s2", Context.MODE_PRIVATE)
    private val _testMode = MutableStateFlow(prefs.getBoolean(KEY_TEST_MODE, false))

    val testMode: StateFlow<Boolean> = _testMode.asStateFlow()

    fun setTestMode(on: Boolean) {
        prefs.edit().putBoolean(KEY_TEST_MODE, on).apply()
        _testMode.value = on
    }

    private companion object {
        const val KEY_TEST_MODE = "testMode"
    }
}
