package org.aegisos.duress.credential;

import java.security.MessageDigest;
import org.aegisos.duress.state.DuressProfileState;

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
