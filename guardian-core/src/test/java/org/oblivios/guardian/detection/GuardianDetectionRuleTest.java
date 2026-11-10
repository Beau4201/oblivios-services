// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.oblivios.guardian.contracts.GuardianEventV1;
import org.oblivios.guardian.contracts.GuardianIncidentV1;
import org.oblivios.guardian.remediation.RemediationActionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GuardianDetectionRuleTest {
    @ParameterizedTest
    @MethodSource("matchingEvents")
    void exactCanonicalEventsProduceApprovedIncidents(
        GuardianEventV1 event, String ruleId, String severity, int confidence,
        RemediationActionId action, String fact) {
        GuardianDetectionRule rule = GuardianDetectionRule.matching(event).orElseThrow();
        GuardianIncidentV1 incident = rule.toIncident(event);

        assertEquals("guardian-v1-" + ruleId.toLowerCase(Locale.ROOT) + "-" + event.eventId(),
            incident.incidentId());
        assertEquals(List.of(event.eventId()), incident.eventIds());
        assertEquals(severity, incident.severity());
        assertEquals(confidence, incident.confidence());
        assertEquals(List.of(fact), incident.explanationFacts());
        assertEquals(action.name(), incident.recommendedActionId());
        assertEquals(event.occurredAt(), incident.createdAt());
        assertEquals("OPEN", incident.state());
    }

    @Test
    void networkRuleRejectsNoneCategoryAndZeroTransfer() {
        assertTrue(GuardianDetectionRule.matching(networkEvent("NONE", 10, 0)).isEmpty());
        assertTrue(GuardianDetectionRule.matching(networkEvent("WIFI", 0, 0)).isEmpty());
    }

    @Test
    void rulesRejectChangedPolicyState() {
        assertTrue(GuardianDetectionRule.matching(event(
            "BACKGROUND_LOCATION", "LOCATION_PRECISE", "BACKGROUND", "DENIED", "NONE", 0, 0))
            .isEmpty());
    }

    @Test
    void rulesRejectUnknownEventType() {
        assertTrue(GuardianDetectionRule.matching(event(
            "UNKNOWN", "LOCATION_PRECISE", "BACKGROUND", "ALLOWED", "NONE", 0, 0)).isEmpty());
    }

    private static Stream<Arguments> matchingEvents() {
        return Stream.of(
            Arguments.of(
                event("BACKGROUND_LOCATION", "LOCATION_PRECISE", "BACKGROUND", "ALLOWED", "NONE", 0, 0),
                "BACKGROUND_LOCATION_V1", "SUSPICIOUS", 80,
                RemediationActionId.REVOKE_BACKGROUND_LOCATION, "background precise location allowed"),
            Arguments.of(
                networkEvent("WIFI", 10, 0),
                "BACKGROUND_NETWORK_V1", "UNUSUAL", 65,
                RemediationActionId.BLOCK_BACKGROUND_NETWORK, "background network transfer allowed"),
            Arguments.of(
                event("OVERLAY_PERMISSION", "SYSTEM_ALERT_WINDOW", "FOREGROUND", "ALLOWED", "NONE", 0, 0),
                "OVERLAY_PERMISSION_V1", "SUSPICIOUS", 75,
                RemediationActionId.REMOVE_OVERLAY_PERMISSION, "overlay permission allowed"),
            Arguments.of(
                event("REPEATED_BACKGROUND_EXECUTION", "APPLICATION_EXECUTION", "BACKGROUND", "ALLOWED", "NONE", 0, 0),
                "REPEATED_BACKGROUND_EXECUTION_V1", "PROBABLY_HARMFUL", 85,
                RemediationActionId.FREEZE_APPLICATION, "repeated background execution allowed"),
            Arguments.of(
                event("BACKGROUND_LOCATION", "LOCATION_APPROXIMATE", "BACKGROUND", "ALLOWED", "NONE", 0, 0),
                "BACKGROUND_LOCATION_APPROXIMATE_V1", "UNUSUAL", 55,
                RemediationActionId.REVOKE_BACKGROUND_LOCATION, "background approximate location allowed"));
    }

    private static GuardianEventV1 networkEvent(String category, long bytesSent, long bytesReceived) {
        return event("BACKGROUND_NETWORK", "NETWORK", "BACKGROUND", "ALLOWED", category,
            bytesSent, bytesReceived);
    }

    private static GuardianEventV1 event(String eventType, String resource, String foregroundState,
                                         String policyState, String networkCategory, long bytesSent,
                                         long bytesReceived) {
        return new GuardianEventV1(1, "event-1", Instant.parse("2026-07-19T12:00:00Z"),
            "org.example.app", 10123, eventType, resource, foregroundState, policyState,
            networkCategory, bytesSent, bytesReceived);
    }
}
