package org.sovereignos.guardian.reference;

import java.util.LinkedHashMap;
import java.util.Map;
import org.sovereignos.guardian.application.GuardianApplicationResult;
import org.sovereignos.guardian.application.GuardianApplicationService;
import org.sovereignos.guardian.audit.InMemoryGuardianAuditStore;
import org.sovereignos.guardian.detection.GuardianDetectionService;
import org.sovereignos.guardian.detection.InMemoryGuardianDetectionCommitStore;
import org.sovereignos.guardian.overview.InMemoryGuardianOverviewStore;
import org.sovereignos.guardian.remediation.GuardianRemediationService;
import org.sovereignos.guardian.remediation.RemediationCatalog;
import org.sovereignos.guardian.validation.GuardianEventParser;

public final class GuardianReferenceScenario {
    private static final String REFERENCE_PACKAGE = "org.sovereignos.reference.target";

    private final GuardianApplicationService service;

    private GuardianReferenceScenario(GuardianApplicationService service) {
        this.service = service;
    }

    public static GuardianReferenceScenario create() {
        return new GuardianReferenceScenario(createService());
    }

    public GuardianApplicationResult detectBackgroundLocation() {
        return service.ingest(backgroundLocationRawEvent());
    }

    public GuardianApplicationResult approve(String incidentId, String actionId) {
        return service.approve(incidentId, actionId);
    }

    public GuardianApplicationResult undo(String auditId) {
        return service.undo(auditId);
    }

    public GuardianApplicationResult overview() {
        return service.overview();
    }

    private static GuardianApplicationService createService() {
        InMemoryGuardianDetectionCommitStore incidentStore = new InMemoryGuardianDetectionCommitStore();
        InMemoryGuardianOverviewStore overviewStore = new InMemoryGuardianOverviewStore();
        GuardianRemediationService remediation = new GuardianRemediationService(
            new RemediationCatalog(),
            new SimulatedGuardianPlatformGateway(),
            new InMemoryGuardianAuditStore(),
            incidentStore,
            overviewStore);
        return new GuardianApplicationService(
            new GuardianEventParser(), new GuardianDetectionService(incidentStore), remediation, overviewStore);
    }

    private static Map<String, Object> backgroundLocationRawEvent() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("schemaVersion", 1);
        raw.put("eventId", "reference-background-location");
        raw.put("occurredAt", "2026-07-19T12:00:00Z");
        raw.put("packageName", REFERENCE_PACKAGE);
        raw.put("uid", 10123);
        raw.put("eventType", "BACKGROUND_LOCATION");
        raw.put("resource", "LOCATION_PRECISE");
        raw.put("foregroundState", "BACKGROUND");
        raw.put("policyState", "ALLOWED");
        raw.put("networkCategory", "NONE");
        raw.put("bytesSent", 0L);
        raw.put("bytesReceived", 0L);
        return raw;
    }
}
