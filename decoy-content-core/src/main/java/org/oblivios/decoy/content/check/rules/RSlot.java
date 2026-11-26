// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.util.List;
import java.util.stream.Stream;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;

/**
 * {@code R-SLOT}: a message of a thread or of the reserve holds a slot that was not filled. An origin rule because the
 * reserve is no view: the roll-forward writes it later with no further check.
 */
public final class RSlot implements OriginRule {
    @Override
    public String id() {
        return "R-SLOT";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        Stream.concat(v.people().threads().stream().flatMap(t -> t.messages().stream()),
                story.reserve().stream().flatMap(c -> c.messages().stream())).forEach(m -> {
            if (m.body().contains("{")) {
                out.add(new Violation(id(), "message at " + m.at() + " holds a slot that was not filled: " + m.body()));
            }
        });
    }
}
