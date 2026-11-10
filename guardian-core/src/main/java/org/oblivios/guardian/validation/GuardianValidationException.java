// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.validation;

public final class GuardianValidationException extends RuntimeException {
    public GuardianValidationException(String message) {
        super(message);
    }

    public GuardianValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
