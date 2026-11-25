// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check;

/** One contradiction in a plan: the id of the rule it breaks and what was found. */
public record Violation(String ruleId, String detail) {}
