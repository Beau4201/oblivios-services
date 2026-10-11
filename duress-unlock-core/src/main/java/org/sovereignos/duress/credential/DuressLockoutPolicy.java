// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.duress.credential;

/**
 * Decides whether an unlock attempt that did not match the device's real
 * credential should be counted toward a failed-attempt / auto-wipe counter.
 */
public final class DuressLockoutPolicy {

    private DuressLockoutPolicy() {
    }

    /**
     * Whether {@code result} should be counted as a failed unlock attempt.
     *
     * <p><strong>Must only be consulted after the real-credential check has
     * already failed.</strong> The intended flow is: check the real
     * credential first; if it matches, unlock normally and never call this
     * method. Only if the real credential does <em>not</em> match should the
     * decoy matcher be checked and this method consulted to decide whether
     * to count the attempt as failed.
     *
     * <p>Calling this method on a {@link DuressMatchResult} from every
     * unlock attempt unconditionally — including attempts where the real
     * credential was actually correct — would misread {@link
     * DuressMatchResult#NO_MATCH} as "wrong password" even when the real
     * password was right, incorrectly incrementing a failed-attempt /
     * auto-wipe counter on legitimate real unlocks. That is a real
     * data-loss risk, not a cosmetic bug.
     */
    public static boolean shouldCountAsFailedAttempt(DuressMatchResult result) {
        return result == DuressMatchResult.NO_MATCH;
    }
}
