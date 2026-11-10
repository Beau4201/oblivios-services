// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.audit;

import java.time.Instant;
import org.oblivios.guardian.contracts.GuardianIncidentV1;
import org.oblivios.guardian.platform.GatewayExecution;
import org.oblivios.guardian.platform.PlatformSnapshot;
import org.oblivios.guardian.platform.VerificationResult;
import org.oblivios.guardian.remediation.RemediationActionId;
import org.oblivios.guardian.remediation.RemediationOutcome;

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
