// duress-unlock-core/src/main/java/org/aegisos/duress/setup/DuressProfileRegistrar.java
package org.aegisos.duress.setup;

import java.security.SecureRandom;
import org.aegisos.duress.credential.DuressCredentialHasher;
import org.aegisos.duress.state.DuressProfileState;

public final class DuressProfileRegistrar {

    private DuressProfileRegistrar() {
    }

    public static DuressProfileState register(
            String profileId, char[] decoyCredential, char[] realCredential, SecureRandom random) {
        if (!DuressCredentialValidator.isValidDecoyCredential(decoyCredential, realCredential)) {
            throw new IllegalArgumentException(
                    "The decoy credential must not match the real credential");
        }
        byte[] salt = DuressCredentialHasher.generateSalt(random);
        byte[] hash = DuressCredentialHasher.hash(decoyCredential, salt);
        return new DuressProfileState.Registered(profileId, hash, salt);
    }
}
