// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.guardian.remediation;

import org.sovereignos.guardian.contracts.GuardianRemediationV1;

public record CataloguedRemediation(
    GuardianRemediationV1 remediation,
    boolean undoSupported) {
}
