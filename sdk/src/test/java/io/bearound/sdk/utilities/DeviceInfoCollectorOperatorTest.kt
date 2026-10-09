package io.bearound.sdk.utilities

import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DeviceInfoCollectorOperatorTest {

    private lateinit var context: Context
    private val shadow
        get() = shadowOf(context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `a ready SIM and a registered network record all three fields`() {
        shadow.setSimState(TelephonyManager.SIM_STATE_READY)
        shadow.setSimOperator("72410")
        shadow.setSimOperatorName("Example Mobile")
        shadow.setNetworkOperator("724005")

        val info = collect()

        assertEquals("72410", info.simMccMnc)
        assertEquals("Example Mobile", info.simOperatorName)
        assertEquals("724005", info.networkMccMnc)
    }

    @Test
    fun `an absent SIM omits the SIM fields and keeps the network code`() {
        shadow.setSimState(TelephonyManager.SIM_STATE_ABSENT)
        shadow.setSimOperator("72410")
        shadow.setSimOperatorName("Example Mobile")
        shadow.setNetworkOperator("72411")

        val info = collect()

        assertNull(info.simMccMnc)
        assertNull(info.simOperatorName)
        assertEquals("72411", info.networkMccMnc)
    }

    @Test
    fun `a malformed code is omitted`() {
        shadow.setSimState(TelephonyManager.SIM_STATE_READY)
        shadow.setSimOperator("ABC")
        shadow.setNetworkOperator("1234")

        val info = collect()

        assertNull(info.simMccMnc)
        assertNull(info.networkMccMnc)
    }

    @Test
    fun `an unregistered network omits the network code`() {
        shadow.setSimState(TelephonyManager.SIM_STATE_READY)
        shadow.setNetworkOperator("")

        assertNull(collect().networkMccMnc)
    }

    @Test
    fun `the operator name is trimmed and capped at 64 characters`() {
        shadow.setSimState(TelephonyManager.SIM_STATE_READY)
        shadow.setSimOperatorName("  " + "x".repeat(80) + "  ")

        assertEquals("x".repeat(64), collect().simOperatorName)
    }

    @Test
    fun `a blank operator name is omitted`() {
        shadow.setSimState(TelephonyManager.SIM_STATE_READY)
        shadow.setSimOperatorName("   ")

        assertNull(collect().simOperatorName)
    }

    private fun collect() = DeviceInfoCollector(context).getOperatorInfo()
}
