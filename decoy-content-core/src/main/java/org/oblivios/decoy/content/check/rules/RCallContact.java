// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;

/** {@code R-CALL-CONTACT}: a call with a number that is no contact. */
public final class RCallContact implements OriginRule {
    @Override
    public String id() {
        return "R-CALL-CONTACT";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        Set<String> numbers = new HashSet<>();
        v.people().contacts().forEach(c -> numbers.add(c.number()));
        v.people().calls().forEach(c -> {
            if (!numbers.contains(c.number())) {
                out.add(new Violation(id(), "call at " + c.at() + " to " + c.number() + ", which is no contact"));
            }
        });
    }
}
