// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.duress.setup;

import java.util.Arrays;

public final class DuressCredentialValidator {

    private DuressCredentialValidator() {
    }

    public static boolean isValidDecoyCredential(char[] candidateCredential, char[] realCredential) {
        return !Arrays.equals(candidateCredential, realCredential);
    }
}
