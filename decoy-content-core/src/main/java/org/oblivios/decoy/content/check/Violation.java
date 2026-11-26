// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check;

import java.util.List;
import org.oblivios.decoy.content.origin.Origin;

/** One contradiction in a plan: the id of the rule it breaks, what was found, and the origins of the facts that clash. */
public record Violation(String ruleId, String detail, List<Origin> facts) {
    public Violation {
        facts = List.copyOf(facts);
    }

    public Violation(String ruleId, String detail) {
        this(ruleId, detail, List.of());
    }
}
