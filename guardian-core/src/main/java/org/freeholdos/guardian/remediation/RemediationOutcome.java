// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.remediation;

import org.freeholdos.guardian.platform.PlatformSnapshot;

public record RemediationOutcome(
    Status status,
    String auditId,
    String detail,
    PlatformSnapshot finalState) {
    public enum Status {
        INCIDENT_NOT_FOUND,
        INCIDENT_NOT_OPEN,
        UNKNOWN_ACTION,
        ACTION_MISMATCH,
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
