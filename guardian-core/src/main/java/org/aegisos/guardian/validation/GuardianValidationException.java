package org.aegisos.guardian.validation;

public final class GuardianValidationException extends RuntimeException {
    public GuardianValidationException(String message) {
        super(message);
    }

    public GuardianValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
