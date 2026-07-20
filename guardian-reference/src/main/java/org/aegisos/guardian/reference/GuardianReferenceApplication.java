package org.aegisos.guardian.reference;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aegisos.guardian.application.GuardianApplicationResult;
import org.aegisos.guardian.application.GuardianApplicationService;
import org.aegisos.guardian.audit.InMemoryGuardianAuditStore;
import org.aegisos.guardian.detection.GuardianDetectionService;
import org.aegisos.guardian.detection.InMemoryGuardianDetectionCommitStore;
import org.aegisos.guardian.remediation.GuardianRemediationService;
import org.aegisos.guardian.remediation.RemediationCatalog;
import org.aegisos.guardian.remediation.RemediationOutcome;
import org.aegisos.guardian.validation.GuardianEventParser;

public final class GuardianReferenceApplication {
    private static final String REFERENCE_PACKAGE = "org.aegisos.reference.target";

    private GuardianReferenceApplication() { }

    public static void main(String[] args) {
        System.exit(run(args, new PrintWriter(System.out, true)));
    }

    static int run(String[] args, PrintWriter out) {
        if (args.length != 1 || !"background-location".equals(args[0])) {
            out.println("Usage: background-location");
            return 2;
        }

        out.println("LOCAL REFERENCE SIMULATION — NOT ANDROID ENFORCEMENT");
        try {
            GuardianApplicationService service = createService();
            GuardianApplicationResult.DetectedIncident detected = requireDetected(
                service.ingest(backgroundLocationRawEvent()));
            GuardianApplicationResult.RemediationCompleted approved = requireRemediation(
                service.approve(
                    detected.incident().incidentId(), detected.incident().recommendedActionId()));
            GuardianApplicationResult.UndoCompleted undone = requireUndo(service.undo(approved.auditId()));
            out.println("DETECTED " + detected.incident().severity());
            out.println(detected.incident().recommendedActionId());
            out.println(approved.status());
            out.println(undone.status());
            return approved.status() == RemediationOutcome.Status.VERIFIED_SUCCESS
                && undone.status() == RemediationOutcome.Status.UNDONE ? 0 : 1;
        } catch (ReferenceScenarioFailure failure) {
            out.println(failure.getMessage());
            return 1;
        }
    }

    private static GuardianApplicationService createService() {
        InMemoryGuardianDetectionCommitStore incidentStore = new InMemoryGuardianDetectionCommitStore();
        GuardianRemediationService remediation = new GuardianRemediationService(
            new RemediationCatalog(),
            new SimulatedGuardianPlatformGateway(),
            new InMemoryGuardianAuditStore(),
            incidentStore);
        return new GuardianApplicationService(
            new GuardianEventParser(), new GuardianDetectionService(incidentStore), remediation);
    }

    private static GuardianApplicationResult.DetectedIncident requireDetected(GuardianApplicationResult result) {
        if (result instanceof GuardianApplicationResult.DetectedIncident detected) {
            return detected;
        }
        throw new ReferenceScenarioFailure("DETECTION_FAILED");
    }

    private static GuardianApplicationResult.RemediationCompleted requireRemediation(
        GuardianApplicationResult result) {
        if (result instanceof GuardianApplicationResult.RemediationCompleted remediation) {
            return remediation;
        }
        throw new ReferenceScenarioFailure("REMEDIATION_FAILED");
    }

    private static GuardianApplicationResult.UndoCompleted requireUndo(GuardianApplicationResult result) {
        if (result instanceof GuardianApplicationResult.UndoCompleted undo) {
            return undo;
        }
        throw new ReferenceScenarioFailure("UNDO_FAILED");
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

    private static final class ReferenceScenarioFailure extends RuntimeException {
        private ReferenceScenarioFailure(String message) {
            super(message);
        }
    }
}
