// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.evidence;

import java.time.Duration;
import java.time.Instant;

/**
 * The owner-selected local retention period for {@code GuardianIncidentEvidenceV1}.
 * {@link #UNTIL_DELETED} retains evidence until the owner explicitly clears it.
 */
public enum GuardianEvidenceRetentionPeriod {
    SEVEN_DAYS(Duration.ofDays(7)),
    THIRTY_DAYS(Duration.ofDays(30)),
    NINETY_DAYS(Duration.ofDays(90)),
    UNTIL_DELETED(null);

    private final Duration window;

    GuardianEvidenceRetentionPeriod(Duration window) {
        this.window = window;
    }

    public boolean isWithinWindow(Instant observedAt, Instant now) {
        if (window == null) {
            return true;
        }
        return !observedAt.isBefore(now.minus(window));
    }
}
