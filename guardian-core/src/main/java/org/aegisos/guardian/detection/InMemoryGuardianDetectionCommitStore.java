package org.aegisos.guardian.detection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.aegisos.guardian.audit.GuardianDetectionAuditEntry;
import org.aegisos.guardian.remediation.GuardianIncidentStore;
import org.aegisos.guardian.remediation.StoredGuardianIncident;

public final class InMemoryGuardianDetectionCommitStore
    implements GuardianDetectionCommitStore, GuardianIncidentStore {
    private final Map<String, StoredGuardianIncident> incidents = new LinkedHashMap<>();
    private final Map<String, GuardianDetectionAuditEntry> audits = new LinkedHashMap<>();
    private final GuardianDetectionCommitFailurePolicy failurePolicy;

    public InMemoryGuardianDetectionCommitStore() {
        this((incident, audit) -> { });
    }

    InMemoryGuardianDetectionCommitStore(GuardianDetectionCommitFailurePolicy failurePolicy) {
        this.failurePolicy = failurePolicy;
    }

    @Override
    public Optional<StoredGuardianIncident> findIncident(String incidentId) {
        return find(incidentId);
    }

    @Override
    public void commit(StoredGuardianIncident incident, GuardianDetectionAuditEntry audit) {
        if (!incident.incident().incidentId().equals(audit.incidentId())) {
            throw new IllegalArgumentException("Detection audit must identify the stored incident.");
        }
        if (!"INCIDENT_DETECTED".equals(audit.auditType())) {
            throw new IllegalArgumentException("Detection audit must record INCIDENT_DETECTED.");
        }
        failurePolicy.beforeCommit(incident, audit);
        incidents.put(incident.incident().incidentId(), incident);
        audits.put(audit.auditId(), audit);
    }

    @Override
    public StoredGuardianIncident record(StoredGuardianIncident incident) {
        incidents.put(incident.incident().incidentId(), incident);
        return incident;
    }

    @Override
    public Optional<StoredGuardianIncident> find(String incidentId) {
        return Optional.ofNullable(incidents.get(incidentId));
    }

    @Override
    public StoredGuardianIncident require(String incidentId) {
        return find(incidentId).orElseThrow(() -> new IllegalArgumentException("Unknown incident: " + incidentId));
    }

    @Override
    public boolean remove(String incidentId) {
        return incidents.remove(incidentId) != null;
    }

    public Optional<GuardianDetectionAuditEntry> findAudit(String auditId) {
        return Optional.ofNullable(audits.get(auditId));
    }

    public int incidentCount() { return incidents.size(); }

    public int auditCount() { return audits.size(); }
}
