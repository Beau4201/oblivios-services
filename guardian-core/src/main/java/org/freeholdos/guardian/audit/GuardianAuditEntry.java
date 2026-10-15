// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.audit;

import java.time.Instant;
import org.freeholdos.guardian.contracts.GuardianIncidentV1;
import org.freeholdos.guardian.platform.GatewayExecution;
import org.freeholdos.guardian.platform.PlatformSnapshot;
import org.freeholdos.guardian.platform.VerificationResult;
import org.freeholdos.guardian.remediation.RemediationActionId;
import org.freeholdos.guardian.remediation.RemediationOutcome;

public record GuardianAuditEntry(
    String auditId,
    Instant recordedAt,
    GuardianIncidentV1 incident,
    RemediationActionId actionId,
    PlatformSnapshot before,
    GatewayExecution execution,
    VerificationResult verification,
    GatewayExecution rollback,
    boolean verifiedSuccess,
    RemediationOutcome.Status outcome) {
}
