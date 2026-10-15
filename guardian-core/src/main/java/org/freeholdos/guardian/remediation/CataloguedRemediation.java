// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.remediation;

import org.freeholdos.guardian.contracts.GuardianRemediationV1;

public record CataloguedRemediation(
    GuardianRemediationV1 remediation,
    boolean undoSupported) {
}
