// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.util.List;
import java.util.stream.Stream;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;

/** {@code R-REQUIRES}: a conversation, of the threads or of the reserve, presumes a fact the persona does not have. */
public final class RRequires implements OriginRule {
    @Override
    public String id() {
        return "R-REQUIRES";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        Stream.concat(story.conversations().stream(), story.reserve().stream()).forEach(c -> {
            if (!story.persona().facts().containsAll(c.requires())) {
                out.add(new Violation(id(), "conversation " + c.poolId() + " presumes " + c.requires() + ", and the persona does not have all of it"));
            }
        });
    }
}
