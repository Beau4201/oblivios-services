// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.evidence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class GuardianEvidenceRetentionPeriodTest {
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Test
    void sevenDayWindowExcludesObservationsOlderThanSevenDays() {
        Instant withinWindow = NOW.minusSeconds(6L * 24 * 3600);
        Instant outsideWindow = NOW.minusSeconds(8L * 24 * 3600);

        assertTrue(GuardianEvidenceRetentionPeriod.SEVEN_DAYS.isWithinWindow(withinWindow, NOW));
        assertFalse(GuardianEvidenceRetentionPeriod.SEVEN_DAYS.isWithinWindow(outsideWindow, NOW));
    }

    @Test
    void thirtyAndNinetyDayWindowsExcludeObservationsOlderThanTheirBound() {
        Instant outside30 = NOW.minusSeconds(31L * 24 * 3600);
        Instant outside90 = NOW.minusSeconds(91L * 24 * 3600);

        assertFalse(GuardianEvidenceRetentionPeriod.THIRTY_DAYS.isWithinWindow(outside30, NOW));
        assertFalse(GuardianEvidenceRetentionPeriod.NINETY_DAYS.isWithinWindow(outside90, NOW));
    }

    @Test
    void untilDeletedNeverExcludesAnyObservation() {
        Instant longAgo = NOW.minusSeconds(3650L * 24 * 3600);

        assertTrue(GuardianEvidenceRetentionPeriod.UNTIL_DELETED.isWithinWindow(longAgo, NOW));
    }
}
