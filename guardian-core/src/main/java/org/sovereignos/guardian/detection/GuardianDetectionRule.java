package org.sovereignos.guardian.detection;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.sovereignos.guardian.contracts.GuardianEventV1;
import org.sovereignos.guardian.contracts.GuardianIncidentV1;
import org.sovereignos.guardian.remediation.RemediationActionId;

public enum GuardianDetectionRule {
    BACKGROUND_LOCATION_V1(
        "BACKGROUND_LOCATION", "LOCATION_PRECISE", "BACKGROUND", "ALLOWED",
        "SUSPICIOUS", 80, RemediationActionId.REVOKE_BACKGROUND_LOCATION,
        "background precise location allowed"),
    BACKGROUND_LOCATION_APPROXIMATE_V1(
        "BACKGROUND_LOCATION", "LOCATION_APPROXIMATE", "BACKGROUND", "ALLOWED",
        "UNUSUAL", 55, RemediationActionId.REVOKE_BACKGROUND_LOCATION,
        "background approximate location allowed"),
    BACKGROUND_NETWORK_V1(
        "BACKGROUND_NETWORK", "NETWORK", "BACKGROUND", "ALLOWED",
        "UNUSUAL", 65, RemediationActionId.BLOCK_BACKGROUND_NETWORK,
        "background network transfer allowed"),
    OVERLAY_PERMISSION_V1(
        "OVERLAY_PERMISSION", "SYSTEM_ALERT_WINDOW", null, "ALLOWED",
        "SUSPICIOUS", 75, RemediationActionId.REMOVE_OVERLAY_PERMISSION,
        "overlay permission allowed"),
    REPEATED_BACKGROUND_EXECUTION_V1(
        "REPEATED_BACKGROUND_EXECUTION", "APPLICATION_EXECUTION", "BACKGROUND", "ALLOWED",
        "PROBABLY_HARMFUL", 85, RemediationActionId.FREEZE_APPLICATION,
        "repeated background execution allowed");

    private final String eventType;
    private final String resource;
    private final String foregroundState;
    private final String policyState;
    private final String severity;
    private final int confidence;
    private final RemediationActionId action;
    private final String fact;

    GuardianDetectionRule(String eventType, String resource, String foregroundState, String policyState,
                          String severity, int confidence, RemediationActionId action, String fact) {
        this.eventType = eventType;
        this.resource = resource;
        this.foregroundState = foregroundState;
        this.policyState = policyState;
        this.severity = severity;
        this.confidence = confidence;
        this.action = action;
        this.fact = fact;
    }

    public static Optional<GuardianDetectionRule> matching(GuardianEventV1 event) {
        for (GuardianDetectionRule rule : values()) {
            if (rule.matches(event)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    public GuardianIncidentV1 toIncident(GuardianEventV1 event) {
        return new GuardianIncidentV1(
            1,
            "guardian-v1-" + name().toLowerCase(Locale.ROOT) + "-" + event.eventId(),
            List.of(event.eventId()),
            severity,
            confidence,
            List.of(fact),
            action.name(),
            event.occurredAt(),
            "OPEN");
    }

    private boolean matches(GuardianEventV1 event) {
        return eventType.equals(event.eventType())
            && resource.equals(event.resource())
            && (foregroundState == null || foregroundState.equals(event.foregroundState()))
            && policyState.equals(event.policyState())
            && (!this.equals(BACKGROUND_NETWORK_V1)
                || (!"NONE".equals(event.networkCategory())
                    && (event.bytesSent() > 0 || event.bytesReceived() > 0)));
    }
}
