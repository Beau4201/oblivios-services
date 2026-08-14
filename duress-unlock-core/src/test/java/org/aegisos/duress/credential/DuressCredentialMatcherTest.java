package org.aegisos.duress.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.SecureRandom;
import org.aegisos.duress.state.DuressProfileState;
import org.junit.jupiter.api.Test;

class DuressCredentialMatcherTest {

    @Test
    void offStateNeverMatchesAnyCredential() {
        DuressMatchResult result =
                DuressCredentialMatcher.matches(new DuressProfileState.Off(), "1234".toCharArray());
        assertEquals(DuressMatchResult.NO_MATCH, result);
    }

    @Test
    void registeredStateMatchesTheCorrectCredential() {
        byte[] salt = DuressCredentialHasher.generateSalt(new SecureRandom());
        byte[] hash = DuressCredentialHasher.hash("5678".toCharArray(), salt);
        DuressProfileState state = new DuressProfileState.Registered("profile-1", hash, salt);

        DuressMatchResult result = DuressCredentialMatcher.matches(state, "5678".toCharArray());

        assertEquals(DuressMatchResult.DECOY_MATCH, result);
    }

    @Test
    void registeredStateRejectsTheWrongCredential() {
        byte[] salt = DuressCredentialHasher.generateSalt(new SecureRandom());
        byte[] hash = DuressCredentialHasher.hash("5678".toCharArray(), salt);
        DuressProfileState state = new DuressProfileState.Registered("profile-1", hash, salt);

        DuressMatchResult result = DuressCredentialMatcher.matches(state, "0000".toCharArray());

        assertEquals(DuressMatchResult.NO_MATCH, result);
    }

    @Test
    void dummySaltAndHashLengthsMatchRealOnes() {
        byte[] realSalt = DuressCredentialHasher.generateSalt(new SecureRandom());
        byte[] realHash = DuressCredentialHasher.hash("5678".toCharArray(), realSalt);

        byte[] dummySalt = DuressCredentialMatcher.effectiveSalt(new DuressProfileState.Off());
        byte[] dummyHash = DuressCredentialMatcher.effectiveHash(new DuressProfileState.Off());

        assertEquals(realSalt.length, dummySalt.length);
        assertEquals(realHash.length, dummyHash.length);
    }
}
