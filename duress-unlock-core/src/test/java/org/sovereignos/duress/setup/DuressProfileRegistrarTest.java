package org.sovereignos.duress.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.SecureRandom;
import org.sovereignos.duress.credential.DuressCredentialMatcher;
import org.sovereignos.duress.credential.DuressMatchResult;
import org.sovereignos.duress.state.DuressProfileState;
import org.junit.jupiter.api.Test;

class DuressProfileRegistrarTest {

    @Test
    void registeringWithDistinctCredentialsProducesARegisteredState() {
        DuressProfileState state = DuressProfileRegistrar.register(
                "profile-1", "5678".toCharArray(), "1234".toCharArray(), new SecureRandom());

        DuressProfileState.Registered registered =
                assertInstanceOf(DuressProfileState.Registered.class, state);
        assertEquals("profile-1", registered.profileId());
    }

    @Test
    void registeredStateCanBeVerifiedByTheMatcher() {
        DuressProfileState state = DuressProfileRegistrar.register(
                "profile-1", "5678".toCharArray(), "1234".toCharArray(), new SecureRandom());

        DuressMatchResult result = DuressCredentialMatcher.matches(state, "5678".toCharArray());

        assertEquals(DuressMatchResult.DECOY_MATCH, result);
    }

    @Test
    void registeringWithTheSameCredentialAsRealThrows() {
        assertThrows(IllegalArgumentException.class, () -> DuressProfileRegistrar.register(
                "profile-1", "1234".toCharArray(), "1234".toCharArray(), new SecureRandom()));
    }
}
