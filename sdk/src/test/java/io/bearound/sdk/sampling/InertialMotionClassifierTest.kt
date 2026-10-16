package io.bearound.sdk.sampling

import com.google.gson.JsonParser
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class InertialMotionClassifierTest {
    private val openedAt = 10_000L
    private val closedAt = 13_000L

    private fun samples(count: Int = 50, spanMs: Long = 2_450L, amplitude: Double = 0.0): List<InertialSample> =
        List(count) { index ->
            InertialSample(openedAt + index * spanMs / (count - 1), 0.0, 0.0,
                1.0 + if (index % 2 == 0) amplitude else -amplitude)
        }

    private fun classify(
        samples: List<InertialSample> = samples(),
        opened: Long = openedAt,
        closed: Long = closedAt,
        completed: Long = closed
    ) = InertialMotionClassifier.classify(samples, 17L, opened, closed, completed)

    private fun excursions(count: Int, amplitude: Double = 0.2): List<InertialSample> =
        samples().mapIndexed { index, sample ->
            if (index < count) sample.copy(zG = 1.0 + if (index % 2 == 0) amplitude else -amplitude) else sample
        }

    @Test
    fun `matches all ten shared fixtures and preserves their original timestamps`() {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/inertial-fixtures.json")).use { it.readBytes() }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) }
        assertEquals("a181a979fdaddd4532fb01a544fddd210811a37bad6b3b0287827b95416f808d", sha256)
        val cases = JsonParser.parseString(fixture.toString(Charsets.UTF_8)).asJsonObject.getAsJsonArray("cases")
        assertEquals(10, cases.size())
        cases.forEach { entry ->
            val case = entry.asJsonObject
            val id = case.get("id").asString
            val expected = case.get("expected").asString
            val input = case.getAsJsonArray("samples").map { row ->
                val values = row.asJsonArray
                InertialSample(openedAt + values[0].asLong, values[1].asDouble,
                    values[2].asDouble, values[3].asDouble)
            }
            val result = classify(input)
            if (expected == "invalid") {
                assertNull(id, result)
            } else {
                assertEquals(id, InertialClassification.valueOf(expected.uppercase()), result?.classification)
                assertEquals(id, input.first().monotonicAtMs, result?.startedAtMonotonicMs)
                assertEquals(id, input.last().monotonicAtMs, result?.endedAtMonotonicMs)
                assertEquals(id, input.size, result?.sampleCount)
            }
        }
    }

    @Test
    fun `returns source sample bounds rather than capture or completion timestamps`() {
        val input = samples().map { it.copy(monotonicAtMs = it.monotonicAtMs + 100L) }
        val result = checkNotNull(classify(input, completed = closedAt + 250L))

        assertEquals(17L, result.generation)
        assertEquals(openedAt + 100L, result.startedAtMonotonicMs)
        assertEquals(openedAt + 2_550L, result.endedAtMonotonicMs)
        assertEquals(50, result.sampleCount)
    }

    @Test
    fun `rejects empty and subminimum sample counts without truncating`() {
        assertNull(classify(emptyList()))
        assertNull(classify(samples(count = 29)))
    }

    @Test
    fun `accepts exactly thirty samples covering exactly two seconds`() {
        assertEquals(InertialClassification.QUIET, classify(samples(count = 30, spanMs = 2_000L))?.classification)
    }

    @Test
    fun `accepts the maximum count and rejects one extra sample`() {
        assertNotNull(classify(samples(count = 128)))
        assertNull(classify(samples(count = 129)))
    }

    @Test
    fun `rejects a sample span one millisecond below the minimum`() {
        assertNull(classify(samples(spanMs = 1_999L)))
    }

    @Test
    fun `accepts a three second capture and rejects a longer capture`() {
        assertNotNull(classify(samples(count = 61, spanMs = 3_000L)))
        assertNull(classify(closed = closedAt + 1L))
    }

    @Test
    fun `rejects negative opening and inverted capture or completion bounds`() {
        assertNull(classify(opened = -1L))
        assertNull(classify(closed = openedAt - 1L))
        assertNull(classify(completed = closedAt - 1L))
    }

    @Test
    fun `rejects samples preceding capture opening or following capture closure`() {
        val beforeOpening = samples().toMutableList()
        beforeOpening[0] = beforeOpening[0].copy(monotonicAtMs = openedAt - 1L)
        assertNull(classify(beforeOpening))

        val afterClosure = samples(count = 61, spanMs = 3_000L).toMutableList()
        afterClosure[afterClosure.lastIndex] = afterClosure.last().copy(monotonicAtMs = closedAt + 1L)
        assertNull(classify(afterClosure))
    }

    @Test
    fun `rejects future samples without replacing their timestamp with receive time`() {
        val input = samples(count = 61, spanMs = 3_000L)
        assertNull(classify(input, completed = closedAt - 1L))
    }

    @Test
    fun `accepts completion exactly five seconds after the original final sample`() {
        val input = samples()
        assertNotNull(classify(input, completed = input.last().monotonicAtMs + 5_000L))
    }

    @Test
    fun `rejects stale completion one millisecond beyond the allowed age`() {
        val input = samples()
        assertNull(classify(input, completed = input.last().monotonicAtMs + 5_001L))
    }

    @Test
    fun `rejects duplicate timestamps anywhere in a window`() {
        listOf(1, 25, 49).forEach { index ->
            val input = samples().toMutableList()
            input[index] = input[index].copy(monotonicAtMs = input[index - 1].monotonicAtMs)
            assertNull("duplicate at $index", classify(input))
        }
    }

    @Test
    fun `rejects descending timestamps rather than sorting the samples`() {
        val input = samples().toMutableList()
        val previous = input[24]
        input[24] = input[25]
        input[25] = previous

        assertNull(classify(input))
    }

    @Test
    fun `accepts gaps of exactly two hundred fifty milliseconds`() {
        val input = samples(count = 30, spanMs = 2_000L).toMutableList()
        for (index in 15..input.lastIndex) {
            input[index] = input[index].copy(monotonicAtMs = input[index].monotonicAtMs + 181L)
        }

        assertEquals(250L, input[15].monotonicAtMs - input[14].monotonicAtMs)
        assertNotNull(classify(input))
    }

    @Test
    fun `rejects a gap one millisecond above the limit instead of filtering it`() {
        val input = samples(count = 30, spanMs = 2_000L).toMutableList()
        for (index in 15..input.lastIndex) {
            input[index] = input[index].copy(monotonicAtMs = input[index].monotonicAtMs + 182L)
        }

        assertEquals(251L, input[15].monotonicAtMs - input[14].monotonicAtMs)
        assertNull(classify(input))
    }

    @Test
    fun `rejects nonfinite values on every axis`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            listOf<(InertialSample) -> InertialSample>(
                { it.copy(xG = invalid) }, { it.copy(yG = invalid) }, { it.copy(zG = invalid) }
            ).forEach { replace ->
                val input = samples().toMutableList()
                input[25] = replace(input[25])
                assertNull(classify(input))
            }
        }
    }

    @Test
    fun `rejects a nonfinite vector magnitude from finite overflowing axes`() {
        val input = samples().toMutableList()
        input[25] = input[25].copy(xG = Double.MAX_VALUE)

        assertNull(classify(input))
    }

    @Test
    fun `centers an even sample window using the average of the two middle magnitudes`() {
        val result = checkNotNull(classify(samples(amplitude = 0.06)))

        assertEquals(0.06, result.dynamicRmsG, 0.000000001)
        assertEquals(1.0, result.excursionRatio, 0.0)
        assertEquals(InertialClassification.ACTIVE, result.classification)
    }

    @Test
    fun `uses median rather than mean to center a minority of excursions`() {
        val input = samples().mapIndexed { index, sample ->
            if (index < 10) sample.copy(zG = 1.2) else sample
        }
        val result = checkNotNull(classify(input))

        assertEquals(kotlin.math.sqrt(0.04 * 0.2), result.dynamicRmsG, 0.000000001)
        assertEquals(0.2, result.excursionRatio, 0.0)
        assertEquals(InertialClassification.ACTIVE, result.classification)
    }

    @Test
    fun `requires both active rms and the exact minimum excursion fraction`() {
        assertEquals(InertialClassification.ACTIVE, classify(excursions(10))?.classification)
        val sparse = checkNotNull(classify(excursions(9)))
        assertEquals(0.18, sparse.excursionRatio, 0.0)
        assertEquals(InertialClassification.AMBIGUOUS, sparse.classification)
        assertEquals(InertialClassification.AMBIGUOUS, classify(samples(amplitude = 0.04))?.classification)
    }

    @Test
    fun `includes exact active and quiet rms thresholds`() {
        assertEquals(InertialClassification.ACTIVE, classify(samples(amplitude = 0.06))?.classification)
        assertEquals(InertialClassification.QUIET, classify(samples(amplitude = 0.02))?.classification)
        assertEquals(InertialClassification.AMBIGUOUS, classify(samples(amplitude = 0.03))?.classification)
    }

    @Test
    fun `applies one micro g tolerance to the active rms comparison`() {
        assertEquals(InertialClassification.ACTIVE, classify(samples(amplitude = 0.0599995))?.classification)
        assertEquals(InertialClassification.AMBIGUOUS, classify(samples(amplitude = 0.0599985))?.classification)
    }

    @Test
    fun `applies one micro g tolerance to the quiet rms comparison`() {
        assertEquals(InertialClassification.QUIET, classify(samples(amplitude = 0.0200005))?.classification)
        assertEquals(InertialClassification.AMBIGUOUS, classify(samples(amplitude = 0.0200015))?.classification)
    }

    @Test
    fun `applies one micro g tolerance to individual excursion membership`() {
        fun input(boundary: Double) = excursions(8).mapIndexed { index, sample ->
            when (index) {
                8 -> sample.copy(zG = 1.0 + boundary)
                9 -> sample.copy(zG = 1.0 - boundary)
                else -> sample
            }
        }

        val insideTolerance = checkNotNull(classify(input(0.0399995)))
        assertEquals(0.2, insideTolerance.excursionRatio, 0.0)
        assertEquals(InertialClassification.ACTIVE, insideTolerance.classification)
        val outsideTolerance = checkNotNull(classify(input(0.0399985)))
        assertEquals(0.16, outsideTolerance.excursionRatio, 0.0)
        assertEquals(InertialClassification.AMBIGUOUS, outsideTolerance.classification)
    }

    @Test
    fun `does not mutate the caller sample collection`() {
        val input = samples(amplitude = 0.08).toMutableList()
        val original = input.toList()

        classify(input)

        assertEquals(original, input)
    }
}
