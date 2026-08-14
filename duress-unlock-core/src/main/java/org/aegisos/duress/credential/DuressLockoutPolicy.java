package org.aegisos.duress.credential;

public final class DuressLockoutPolicy {

    private DuressLockoutPolicy() {
    }

    public static boolean shouldCountAsFailedAttempt(DuressMatchResult result) {
        return result == DuressMatchResult.NO_MATCH;
    }
}
