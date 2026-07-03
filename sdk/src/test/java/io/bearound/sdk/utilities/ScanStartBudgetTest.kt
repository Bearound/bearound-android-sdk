package io.bearound.sdk.utilities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the preventive scan-start quota guard. The OS limit is 5 starts per 30 s; the budget
 * caps the SDK at 4 to leave headroom for the host app.
 */
@RunWith(RobolectricTestRunner::class)
class ScanStartBudgetTest {

    @Before
    fun setUp() {
        ScanStartBudget.reset()
    }

    @Test
    fun `allows up to four starts in a window`() {
        repeat(4) { assertTrue("start ${it + 1} should be allowed", ScanStartBudget.tryAcquire("t")) }
    }

    @Test
    fun `denies the fifth start in the same window`() {
        repeat(4) { ScanStartBudget.tryAcquire("t") }

        assertFalse("fifth start would risk the OS quota", ScanStartBudget.tryAcquire("t"))
    }

    @Test
    fun `freeze denies every start until it elapses`() {
        ScanStartBudget.freeze(60_000L)

        assertFalse("frozen budget must deny", ScanStartBudget.tryAcquire("t"))
    }

    @Test
    fun `denied attempts do not consume budget`() {
        repeat(4) { ScanStartBudget.tryAcquire("t") }
        repeat(10) { ScanStartBudget.tryAcquire("t") } // all denied — must not extend the window

        assertFalse(ScanStartBudget.tryAcquire("t"))
    }
}
