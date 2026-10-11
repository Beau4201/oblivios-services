// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardian.evidence;

import java.util.Objects;
import org.sovereignos.guardian.contracts.GuardianEventV1;
import org.sovereignos.guardian.contracts.GuardianIncidentV1;
import org.sovereignos.guardian.remediation.CataloguedRemediation;
import org.sovereignos.guardian.remediation.StoredGuardianIncident;

/**
 * Derives owner-visible incident evidence from the existing detection and
 * remediation data model only: {@link StoredGuardianIncident}, the
 * {@link GuardianEventV1} that triggered it, and the {@link CataloguedRemediation}
 * registered for its recommended action. It never reads raw event payloads,
 * coordinates, personal content, or technical enforcement detail because none of
 * those inputs expose such fields.
 */
public final class GuardianIncidentEvidenceDeriver {
    private final AppLabelResolver resolver;

    public GuardianIncidentEvidenceDeriver(AppLabelResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public GuardianIncidentEvidenceObservation derive(
        StoredGuardianIncident storedIncident, GuardianEventV1 event, CataloguedRemediation remediation) {
        Objects.requireNonNull(storedIncident, "storedIncident");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(remediation, "remediation");

        GuardianIncidentV1 incident = storedIncident.incident();
        ResolvedAppIdentity identity = resolver.resolve(storedIncident.packageName());
        return new GuardianIncidentEvidenceObservation(
            incident.recommendedActionId(),
            storedIncident.packageName(),
            identity.appLabel(),
            identity.iconKey(),
            event.resource(),
            String.join("; ", incident.explanationFacts()),
            remediation.remediation().compatibilityImpact(),
            incident.recommendedActionId(),
            incident.createdAt());
    }
}
