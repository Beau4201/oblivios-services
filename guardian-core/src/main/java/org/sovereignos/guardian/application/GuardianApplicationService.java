package org.sovereignos.guardian.application;

import java.util.Map;
import java.util.Objects;
import org.sovereignos.guardian.contracts.GuardianEventV1;
import org.sovereignos.guardian.detection.GuardianDetectionResult;
import org.sovereignos.guardian.detection.GuardianDetectionService;
import org.sovereignos.guardian.overview.GuardianOverviewStore;
import org.sovereignos.guardian.remediation.GuardianRemediationService;
import org.sovereignos.guardian.validation.GuardianEventParser;
import org.sovereignos.guardian.validation.GuardianValidationException;

public final class GuardianApplicationService {
    private final GuardianEventParser parser;
    private final GuardianDetectionService detector;
    private final GuardianRemediationService remediation;
    private final GuardianOverviewStore overviewStore;

    public GuardianApplicationService(
        GuardianEventParser parser,
        GuardianDetectionService detector,
        GuardianRemediationService remediation,
        GuardianOverviewStore overviewStore) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.remediation = Objects.requireNonNull(remediation, "remediation");
        this.overviewStore = Objects.requireNonNull(overviewStore, "overviewStore");
    }

    public GuardianApplicationResult ingest(Map<String, Object> raw) {
        final GuardianEventV1 event;
        try {
            event = parser.parse(raw);
        } catch (GuardianValidationException failure) {
            return new GuardianApplicationResult.InvalidEvent(failure.getMessage());
        }
        return switch (detector.evaluate(event)) {
            case GuardianDetectionResult.NoIncident noIncident ->
                new GuardianApplicationResult.NoIncident(noIncident.eventId());
            case GuardianDetectionResult.DetectedIncident detected ->
                new GuardianApplicationResult.DetectedIncident(detected.incident());
            case GuardianDetectionResult.DetectionFailed failed ->
                new GuardianApplicationResult.DetectionFailed(failed.eventId(), failed.failureCode());
        };
    }

    public GuardianApplicationResult approve(String incidentId, String actionId) {
        var outcome = remediation.approve(incidentId, actionId);
        return new GuardianApplicationResult.RemediationCompleted(
            outcome.status(), outcome.auditId(), outcome.detail());
    }

    public GuardianApplicationResult undo(String auditId) {
        var outcome = remediation.undo(auditId);
        return new GuardianApplicationResult.UndoCompleted(
            outcome.status(), outcome.auditId(), outcome.detail());
    }

    public GuardianApplicationResult overview() {
        return new GuardianApplicationResult.Overview(overviewStore.snapshot());
    }
}
