package io.bearound.sdk.background

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.bearound.sdk.models.PeriodicReconciliationDefaults
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.utilities.SDKConfigStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PeriodicReconciliationSchedulingTest {

    private lateinit var context: Context
    private lateinit var scheduler: BackgroundScheduler

    private fun config(
        enabled: Boolean = true,
        intervalMs: Long = PeriodicReconciliationDefaults.DEFAULT_INTERVAL_MILLIS
    ) = SDKConfiguration(
        businessToken = "test-token",
        appId = "io.test",
        periodicReconciliationEnabled = enabled,
        periodicReconciliationIntervalMillis = intervalMs
    )

    private fun uniqueWorkInfos(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(BeaconSyncWorker.WORK_NAME)
            .get()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val wmConfig = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, wmConfig)
        // The scheduler is a process-wide singleton with a lazy WorkManager handle —
        // in Robolectric each test gets a fresh Application (and a fresh test
        // WorkManager), so the singleton from the previous test would enqueue into an
        // orphaned instance. (Reflection on the companion field broke on the RELEASE
        // unit-test variant — hence the explicit test hook.)
        BackgroundScheduler._resetForTesting()
        scheduler = BackgroundScheduler.getInstance(context)
        // Persisted config is the scheduler's source of truth.
        SDKConfigStorage.clearConfiguration(context)
    }

    @Test
    fun `scheduling enqueues exactly one unique periodic work`() {
        SDKConfigStorage.saveConfiguration(context, config())
        scheduler.schedulePeriodicSync()

        val infos = uniqueWorkInfos()
        assertEquals(1, infos.size)
        assertTrue(infos[0].state == WorkInfo.State.ENQUEUED || infos[0].state == WorkInfo.State.RUNNING)
    }

    @Test
    fun `repeated scheduling does not accumulate requests`() {
        SDKConfigStorage.saveConfiguration(context, config())
        repeat(5) { scheduler.schedulePeriodicSync() }

        assertEquals(1, uniqueWorkInfos().size)
    }

    @Test
    fun `interval change updates the same unique work instead of duplicating`() {
        SDKConfigStorage.saveConfiguration(context, config(intervalMs = 20L * 60L * 1000L))
        scheduler.schedulePeriodicSync()

        SDKConfigStorage.saveConfiguration(context, config(intervalMs = 60L * 60L * 1000L))
        scheduler.refreshPeriodicReconciliation(config(intervalMs = 60L * 60L * 1000L))

        assertEquals(1, uniqueWorkInfos().size)
    }

    @Test
    fun `disabling cancels the unique periodic work`() {
        SDKConfigStorage.saveConfiguration(context, config())
        scheduler.schedulePeriodicSync()
        assertEquals(1, uniqueWorkInfos().size)

        SDKConfigStorage.saveConfiguration(context, config(enabled = false))
        scheduler.refreshPeriodicReconciliation(config(enabled = false))

        val infos = uniqueWorkInfos()
        assertTrue(infos.isEmpty() || infos.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `re-enabling schedules the unique periodic work again`() {
        SDKConfigStorage.saveConfiguration(context, config(enabled = false))
        scheduler.refreshPeriodicReconciliation(config(enabled = false))

        SDKConfigStorage.saveConfiguration(context, config(enabled = true))
        scheduler.refreshPeriodicReconciliation(config(enabled = true))

        val active = uniqueWorkInfos().filter { !it.state.isFinished }
        assertEquals(1, active.size)
    }

    @Test
    fun `scheduling with the feature disabled in storage does not enqueue`() {
        SDKConfigStorage.saveConfiguration(context, config(enabled = false))
        scheduler.schedulePeriodicSync()

        val active = uniqueWorkInfos().filter { !it.state.isFinished }
        assertTrue(active.isEmpty())
    }
}
