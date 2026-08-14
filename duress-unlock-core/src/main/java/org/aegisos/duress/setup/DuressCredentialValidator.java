package org.aegisos.duress.setup;

import java.util.Arrays;

public final class DuressCredentialValidator {

    private DuressCredentialValidator() {
    }

    public static boolean isValidDecoyCredential(char[] candidateCredential, char[] realCredential) {
        return !Arrays.equals(candidateCredential, realCredential);
    }
}
