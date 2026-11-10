// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.contracts;

import java.util.List;

public record GuardianRemediationV1(
    String actionId,
    List<String> preconditions,
    String effect,
    String compatibilityImpact,
    String rollback,
    String verification,
    String auditEvent) {
}
