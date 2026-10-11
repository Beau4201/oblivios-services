// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.duress.credential;

import java.security.MessageDigest;
import org.sovereignos.duress.state.DuressProfileState;

/**
 * Compares an entered unlock credential against the device's registered
 * duress (decoy) profile, if any.
 *
 * <p><strong>{@link #matches(DuressProfileState, char[])} MUST be called
 * unconditionally on every unlock attempt</strong>, regardless of whether
 * {@code DuressProfileStateStore.load()} returned {@link
 * DuressProfileState.Off} or {@link DuressProfileState.Registered}. This
 * method always performs a PBKDF2 hash of the entered credential — against
 * the real salt/hash when registered, or against a fixed dummy salt/hash of
 * identical length when not (see {@link #effectiveSalt} / {@link
 * #effectiveHash}) — specifically so that an unregistered device pays the
 * same computational cost as a registered one on every attempt.
 *
 * <p>Guarding this call with something like
 * {@code if (state instanceof DuressProfileState.Registered r) { ... }}
 * before calling {@code matches()} would skip the PBKDF2 computation on the
 * {@code Off} path and reintroduce the exact timing side-channel this class
 * exists to eliminate: an attacker could measure unlock response time to
 * learn whether a decoy profile is registered at all. Always call this
 * method first, unconditionally, and branch on its {@link DuressMatchResult}
 * return value instead.
 */
public final class DuressCredentialMatcher {

    // Fixed-length placeholders so an unregistered device pays the exact
    // same PBKDF2 + comparison cost as a registered one on every attempt —
    // otherwise the response time itself would reveal whether a decoy
    // profile exists at all (see the design's timing-side-channel risk).
    private static final byte[] DUMMY_SALT = new byte[DuressCredentialHasher.SALT_LENGTH_BYTES];
    private static final byte[] DUMMY_HASH = new byte[32];

    private DuressCredentialMatcher() {
    }

    public static DuressMatchResult matches(DuressProfileState state, char[] enteredCredential) {
        byte[] salt = effectiveSalt(state);
        byte[] expectedHash = effectiveHash(state);
        byte[] computedHash = DuressCredentialHasher.hash(enteredCredential, salt);
        boolean isRegistered = state instanceof DuressProfileState.Registered;
        boolean digestsEqual = MessageDigest.isEqual(computedHash, expectedHash);
        return (isRegistered && digestsEqual) ? DuressMatchResult.DECOY_MATCH : DuressMatchResult.NO_MATCH;
    }

    static byte[] effectiveSalt(DuressProfileState state) {
        if (state instanceof DuressProfileState.Registered registered) {
            return registered.salt();
        }
        return DUMMY_SALT;
    }

    static byte[] effectiveHash(DuressProfileState state) {
        if (state instanceof DuressProfileState.Registered registered) {
            return registered.credentialHash();
        }
        return DUMMY_HASH;
    }
}
