package io.bearound.sdk.visit

import io.bearound.sdk.models.WifiObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WifiVisitMatcherTest {

    private val minute = 60_000L
    private val t0 = 1_800_000_000_000L
    private val known = "9f3a1c02b7d4e688"
    private val other = "ffffffffffffffff"
    private val env = "env-wifi"

    private fun place(dwell: Int? = 5, ids: List<String> = listOf(known, "0a1b2c3d4e5f6071")) =
        PlacesConfig.Place(
            environmentId = env,
            distanceMeters = 0.0,
            center = PlacesConfig.Coordinate(0.0, 0.0),
            radiusMeters = 60.0,
            minDwellMinutes = dwell,
            knownApIds = ids
        )

    private fun round(minutes: Long, vararg apIds: String, conclusive: Boolean = true) = WifiRound(
        at = t0 + minutes * minute,
        observations = apIds.map { WifiObservation(apId = it, timestamp = t0 + minutes * minute) },
        conclusive = conclusive
    )

    @Test
    fun `arrival after the dwell without contradiction stamps the first observation`() {
        val matcher = WifiVisitMatcher()
        val places = listOf(place())
        assertTrue(matcher.onRound(round(0, known), places).isEmpty())
        assertTrue(matcher.onRound(round(3, known), places).isEmpty())
        val actions = matcher.onRound(round(5, known), places)
        assertEquals(1, actions.size)
        val arrive = actions.single() as WifiVisitAction.Arrive
        assertEquals(env, arrive.environmentId)
        assertEquals(t0, arrive.at)
        // Already open: more sightings emit nothing.
        assertTrue(matcher.onRound(round(6, known), places).isEmpty())
    }

    @Test
    fun `default dwell applies when the place has none`() {
        val matcher = WifiVisitMatcher()
        val places = listOf(place(dwell = null))
        val dwell = SoftFenceVisitDetector.DEFAULT_MIN_DWELL_MINUTES.toLong()
        matcher.onRound(round(0, known), places)
        assertTrue(matcher.onRound(round(dwell - 1, known), places).isEmpty())
        assertEquals(1, matcher.onRound(round(dwell, known), places).size)
    }

    @Test
    fun `a conclusive round without the AP resets the candidate`() {
        val matcher = WifiVisitMatcher()
        val places = listOf(place())
        matcher.onRound(round(0, known), places)
        assertTrue(matcher.onRound(round(3, other), places).isEmpty())
        // The dwell restarts from the new first sighting at minute 6.
        assertTrue(matcher.onRound(round(6, known), places).isEmpty())
        assertTrue(matcher.onRound(round(10, known), places).isEmpty())
        val arrive = matcher.onRound(round(11, known), places).single() as WifiVisitAction.Arrive
        assertEquals(t0 + 6 * minute, arrive.at)
    }

    @Test
    fun `departure only after the window and stamped with the last observation`() {
        val matcher = WifiVisitMatcher()
        val places = listOf(place())
        matcher.onRound(round(0, known), places)
        matcher.onRound(round(5, known), places)
        matcher.onRound(round(8, known), places)
        // 4 minutes since the last sighting: shorter than the dwell, stays open.
        assertTrue(matcher.onRound(round(12, other), places).isEmpty())
        val depart = matcher.onRound(round(13, other), places).single() as WifiVisitAction.Depart
        assertEquals(env, depart.environmentId)
        assertEquals(t0 + 8 * minute, depart.at)
        assertTrue(matcher.onRound(round(20, other), places).isEmpty())
    }

    @Test
    fun `a sighting inside the window keeps the stop open`() {
        val matcher = WifiVisitMatcher()
        val places = listOf(place())
        matcher.onRound(round(0, known), places)
        matcher.onRound(round(5, known), places)
        matcher.onRound(round(9, other), places)
        matcher.onRound(round(10, known), places)
        assertTrue(matcher.onRound(round(14, other), places).isEmpty())
        val depart = matcher.onRound(round(15, other), places).single() as WifiVisitAction.Depart
        assertEquals(t0 + 10 * minute, depart.at)
    }

    @Test
    fun `an inconclusive round changes nothing`() {
        val matcher = WifiVisitMatcher()
        val places = listOf(place())
        matcher.onRound(round(0, known), places)
        // Does not contradict the candidate and does not count toward the dwell.
        assertTrue(matcher.onRound(round(3, conclusive = false), places).isEmpty())
        assertTrue(matcher.onRound(round(4, known, conclusive = false), places).isEmpty())
        assertEquals(1, matcher.onRound(round(5, known), places).size)
        // Does not close an open stop either.
        assertTrue(matcher.onRound(round(30, conclusive = false), places).isEmpty())
        assertTrue(matcher.onRound(round(31, other, conclusive = false), places).isEmpty())
        val depart = matcher.onRound(round(32, other), places).single() as WifiVisitAction.Depart
        assertEquals(t0 + 5 * minute, depart.at)
    }

    @Test
    fun `a stale scan result is inconclusive and ignored`() {
        // Callers mark results older than the collector's 5 minute age as inconclusive.
        val matcher = WifiVisitMatcher()
        val places = listOf(place())
        matcher.onRound(round(0, known), places)
        assertTrue(matcher.onRound(round(6, known, conclusive = false), places).isEmpty())
        assertEquals(1, matcher.onRound(round(7, known), places).size)
    }

    @Test
    fun `places without known APs are ignored`() {
        val matcher = WifiVisitMatcher()
        val places = listOf(place(ids = emptyList()))
        assertTrue(matcher.onRound(round(0, known), places).isEmpty())
        assertTrue(matcher.onRound(round(10, known), places).isEmpty())
    }

    @Test
    fun `missing knownApIds parses as an empty list and the field parses when present`() {
        val config = PlacesConfig.parse(VisitTestFixtures.configBody(withWifiPlace = true))
        assertEquals(emptyList<String>(), config.places.first { it.environmentId == VisitTestFixtures.ENV_ID }.knownApIds)
        assertEquals(
            listOf("9f3a1c02b7d4e688", "0a1b2c3d4e5f6071"),
            config.places.first { it.environmentId == VisitTestFixtures.WIFI_ENV_ID }.knownApIds
        )
    }
}
