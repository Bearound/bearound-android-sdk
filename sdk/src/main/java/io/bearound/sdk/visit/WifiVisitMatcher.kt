package io.bearound.sdk.visit

import io.bearound.sdk.models.WifiObservation

/**
 * One completed Wi-Fi observation round.
 *
 * @property at epoch millis of the round
 * @property observations access points seen in the round
 * @property conclusive true when the platform actually answered: a non-empty result that is
 *           fresh enough to describe where the device is now. An inconclusive round
 *           (empty or stale scan) proves nothing either way, so the matcher ignores it.
 */
internal data class WifiRound(
    val at: Long,
    val observations: List<WifiObservation>,
    val conclusive: Boolean
)

/** What the matcher asks the caller to do after a round. */
internal sealed class WifiVisitAction {
    abstract val environmentId: String

    /** Open the stop. [at] is the time of the first observation of the known AP. */
    data class Arrive(
        override val environmentId: String,
        val at: Long,
        val observations: List<WifiObservation>
    ) : WifiVisitAction()

    /** Close the stop. [at] is the time of the last observation of a known AP. */
    data class Depart(
        override val environmentId: String,
        val at: Long,
        val observations: List<WifiObservation>
    ) : WifiVisitAction()
}

/**
 * Matches the hashed `apId` of each round against the `knownApIds` of the cached places and
 * turns the sightings into arrivals and departures, with the same dwell the GPS detectors use.
 *
 * Pure state machine, no framework calls: the caller builds the [WifiRound]. State is
 * volatile (in memory only). Not thread safe; call it from one place.
 *
 * Per place: `Idle`, `Candidate` (seen, dwell not reached), `Open` (arrival emitted).
 */
internal class WifiVisitMatcher {

    private sealed class State {
        object Idle : State()
        data class Candidate(val first: Long, val last: Long, val observations: List<WifiObservation>) : State()
        data class Open(val last: Long, val observations: List<WifiObservation>) : State()
    }

    private val states = mutableMapOf<String, State>()

    fun onRound(round: WifiRound, places: List<PlacesConfig.Place>): List<WifiVisitAction> {
        if (!round.conclusive) return emptyList()

        val tracked = places.filter { it.knownApIds.isNotEmpty() }
        // Forget places that left the cache, so a stale stop cannot close later.
        states.keys.retainAll(tracked.map { it.environmentId }.toSet())

        val actions = mutableListOf<WifiVisitAction>()
        for (place in tracked) {
            val known = place.knownApIds.toSet()
            val matched = round.observations.filter { it.apId in known }
            val dwellMs = (place.minDwellMinutes ?: SoftFenceVisitDetector.DEFAULT_MIN_DWELL_MINUTES)
                .coerceAtLeast(1) * 60_000L
            val id = place.environmentId
            // Dated by the sightings themselves (the round may read a cache up to minutes old).
            val firstSeen = matched.minOfOrNull { it.timestamp } ?: round.at
            val lastSeen = matched.maxOfOrNull { it.timestamp } ?: round.at

            when (val state = states[id] ?: State.Idle) {
                State.Idle -> if (matched.isNotEmpty()) {
                    states[id] = State.Candidate(firstSeen, lastSeen, matched)
                }
                is State.Candidate -> if (matched.isEmpty()) {
                    states[id] = State.Idle
                } else if (round.at - state.first >= dwellMs) {
                    states[id] = State.Open(lastSeen, matched)
                    actions += WifiVisitAction.Arrive(id, state.first, matched)
                } else {
                    states[id] = state.copy(last = lastSeen, observations = matched)
                }
                is State.Open -> if (matched.isNotEmpty()) {
                    states[id] = State.Open(lastSeen, matched)
                } else if (round.at - state.last >= dwellMs) {
                    states[id] = State.Idle
                    actions += WifiVisitAction.Depart(id, state.last, state.observations)
                }
            }
        }
        return actions
    }

    /** Drops every in-memory state, e.g. when the detector is stopped. */
    fun reset() = states.clear()
}
