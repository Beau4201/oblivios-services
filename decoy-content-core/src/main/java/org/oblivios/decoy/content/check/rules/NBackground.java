// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.util.List;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.data.NameData;
import org.oblivios.decoy.content.world.WorldPerson;

/** {@code N-BACKGROUND}: a given name that the names of the person's background do not hold for its sex. */
public final class NBackground implements OriginRule {
    @Override
    public String id() {
        return "N-BACKGROUND";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        for (WorldPerson w : DrawnNames.of(story)) {
            if (!NameData.load(w.background()).hasGiven(w.given(), w.gender())) {
                out.add(new Violation(id(), "'" + w.given() + "' is no name of background " + w.background() + " (" + w.id() + ")"));
            }
        }
    }
}
