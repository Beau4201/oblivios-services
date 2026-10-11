// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardian.audit;

import java.time.Instant;
import org.sovereignos.guardian.contracts.GuardianIncidentV1;
import org.sovereignos.guardian.platform.GatewayExecution;
import org.sovereignos.guardian.platform.PlatformSnapshot;
import org.sovereignos.guardian.platform.VerificationResult;
import org.sovereignos.guardian.remediation.RemediationActionId;
import org.sovereignos.guardian.remediation.RemediationOutcome;

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
