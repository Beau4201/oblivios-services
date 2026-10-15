// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.duress.credential;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DuressLockoutPolicyTest {

    @Test
    void aDecoyMatchNeverCountsAsAFailedAttempt() {
        assertFalse(DuressLockoutPolicy.shouldCountAsFailedAttempt(DuressMatchResult.DECOY_MATCH));
    }

    @Test
    void aGenuineMismatchCountsAsAFailedAttempt() {
        assertTrue(DuressLockoutPolicy.shouldCountAsFailedAttempt(DuressMatchResult.NO_MATCH));
    }
}
