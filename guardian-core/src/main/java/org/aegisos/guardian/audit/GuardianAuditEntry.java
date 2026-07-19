package org.aegisos.guardian.audit;

import java.time.Instant;
import org.aegisos.guardian.contracts.GuardianIncidentV1;
import org.aegisos.guardian.platform.GatewayExecution;
import org.aegisos.guardian.platform.PlatformSnapshot;
import org.aegisos.guardian.platform.VerificationResult;
import org.aegisos.guardian.remediation.RemediationActionId;

public record GuardianAuditEntry(
    String auditId,
    Instant recordedAt,
    GuardianIncidentV1 incident,
    RemediationActionId actionId,
    PlatformSnapshot before,
    GatewayExecution execution,
    VerificationResult verification,
    GatewayExecution rollback,
    boolean verifiedSuccess) {
}
