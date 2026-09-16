package nu.staldal.pw.autofill

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

class FillDiagnosticsTest {

    @Before fun reset() = FillDiagnostics.setEnabled(false)

    @After fun tearDown() = FillDiagnostics.setEnabled(false)

    private fun record(host: String) =
        FillDiagnostics.record("com.example.browser", FillOutcome.NO_MATCHING_ENTRY, "https", host, 2)

    @Test fun recordsNothingUntilSwitchedOn() {
        record("example.com")
        assertTrue(FillDiagnostics.records.value.isEmpty())
        assertFalse(FillDiagnostics.enabled)

        FillDiagnostics.setEnabled(true)
        record("example.com")
        assertEquals(1, FillDiagnostics.records.value.size)
    }

    @Test fun switchingOffForgetsWhatWasRecorded() {
        FillDiagnostics.setEnabled(true)
        record("example.com")
        FillDiagnostics.setEnabled(false)
        assertTrue(FillDiagnostics.records.value.isEmpty())
    }

    @Test fun keepsTheMostRecentRequestsFirst() {
        FillDiagnostics.setEnabled(true)
        repeat(FillDiagnostics.CAPACITY + 3) { record("host-$it.example") }
        val records = FillDiagnostics.records.value
        assertEquals(FillDiagnostics.CAPACITY, records.size)
        assertEquals("host-${FillDiagnostics.CAPACITY + 2}.example", records.first().host)
        assertEquals("host-3.example", records.last().host)
    }

    @Test fun clearingLeavesRecordingOn() {
        FillDiagnostics.setEnabled(true)
        record("example.com")
        FillDiagnostics.clear()
        assertTrue(FillDiagnostics.records.value.isEmpty())
        assertTrue(FillDiagnostics.enabled)
        record("example.com")
        assertEquals(1, FillDiagnostics.records.value.size)
    }

    @Test fun aRecordCarriesTheDecisionAndNothingFromTheForm() {
        FillDiagnostics.setEnabled(true)
        record("example.com")
        val record = FillDiagnostics.records.value.single()
        assertEquals("com.example.browser", record.browserPackage)
        assertEquals(FillOutcome.NO_MATCHING_ENTRY, record.outcome)
        assertEquals("https", record.scheme)
        assertEquals("example.com", record.host)
        assertEquals(2, record.classifiedFields)
        // Named rather than counted, so that adding somewhere to put a field
        // value or an entry name has to be a deliberate edit to this list.
        assertEquals(
            setOf("browserPackage", "outcome", "scheme", "host", "classifiedFields"),
            FillRecord::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet(),
        )
    }

    /** Every refusal the selector can reach has something to show for it. */
    @Test fun everySelectorRefusalMapsToAnOutcome() {
        for (refusal in FormRefusal.entries) {
            val outcome = FillDiagnostics.outcomeOf(refusal)
            assertTrue(outcome.description.isNotBlank())
            assertFalse(outcome == FillOutcome.OFFERED)
        }
    }
}
