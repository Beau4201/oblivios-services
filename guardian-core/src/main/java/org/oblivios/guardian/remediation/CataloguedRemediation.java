// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.remediation;

import org.oblivios.guardian.contracts.GuardianRemediationV1;

public record CataloguedRemediation(
    GuardianRemediationV1 remediation,
    boolean undoSupported) {
}
