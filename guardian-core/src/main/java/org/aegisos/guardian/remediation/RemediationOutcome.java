package org.aegisos.guardian.remediation;

import org.aegisos.guardian.platform.PlatformSnapshot;

public record RemediationOutcome(
    Status status,
    String auditId,
    String detail,
    PlatformSnapshot finalState) {
    public enum Status {
        PRECONDITION_FAILED,
        VERIFIED_SUCCESS,
        VERIFICATION_FAILED_ROLLED_BACK,
        VERIFICATION_FAILED_ROLLBACK_FAILED,
        UNDONE,
        UNDO_FAILED,
        UNDO_NOT_AVAILABLE
    }

    public boolean verifiedSuccess() {
        return status == Status.VERIFIED_SUCCESS;
    }
}
