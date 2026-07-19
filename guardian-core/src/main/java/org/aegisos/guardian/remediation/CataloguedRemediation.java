package org.aegisos.guardian.remediation;

import org.aegisos.guardian.contracts.GuardianRemediationV1;

public record CataloguedRemediation(
    GuardianRemediationV1 remediation,
    boolean undoSupported) {
}
