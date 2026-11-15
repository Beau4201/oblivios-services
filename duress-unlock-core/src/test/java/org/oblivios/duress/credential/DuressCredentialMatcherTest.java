// SPDX-License-Identifier: Apache-2.0
package org.oblivios.duress.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.SecureRandom;
import org.oblivios.duress.state.DuressProfileState;
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
    void registeredStateWithAnEmptySaltIsNoMatchAndNeverThrows() {
        // A state file can decode to empty arrays (REGISTERED,<id>,,). PBKDF2 refuses an empty
        // salt, so the matcher used to throw on the unlock path instead of failing closed.
        DuressProfileState state =
                new DuressProfileState.Registered("profile-1", new byte[0], new byte[0]);

        assertEquals(DuressMatchResult.NO_MATCH,
                DuressCredentialMatcher.matches(state, "5678".toCharArray()));
    }

    @Test
    void anEmptyEnteredCredentialIsNoMatchAndNeverThrows() {
        byte[] salt = DuressCredentialHasher.generateSalt(new SecureRandom());
        byte[] hash = DuressCredentialHasher.hash("5678".toCharArray(), salt);
        DuressProfileState state = new DuressProfileState.Registered("profile-1", hash, salt);

        assertEquals(DuressMatchResult.NO_MATCH,
                DuressCredentialMatcher.matches(state, new char[0]));
        assertEquals(DuressMatchResult.NO_MATCH,
                DuressCredentialMatcher.matches(new DuressProfileState.Off(), new char[0]));
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
