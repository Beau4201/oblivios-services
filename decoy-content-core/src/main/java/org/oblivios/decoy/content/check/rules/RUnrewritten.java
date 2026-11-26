// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.util.List;
import java.util.stream.Stream;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;

/** {@code R-UNREWRITTEN}: a conversation, of the threads or of the reserve, that the rewriter did not rewrite. */
public final class RUnrewritten implements OriginRule {
    @Override
    public String id() {
        return "R-UNREWRITTEN";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        Stream.concat(story.conversations().stream(), story.reserve().stream()).forEach(c -> {
            if (!c.rewritten()) {
                out.add(new Violation(id(), "conversation " + c.poolId() + " was not rewritten"));
            }
        });
    }
}
