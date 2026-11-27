// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.util.List;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.data.NameData;
import org.oblivios.decoy.content.world.WorldPerson;

/** {@code N-GENDER}: a person bears a given name that the name data gives to the other sex. */
public final class NGender implements OriginRule {
    @Override
    public String id() {
        return "N-GENDER";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        for (WorldPerson w : DrawnNames.of(story)) {
            if (NameData.load(w.background()).genderOf(w.given()).map(g -> g != w.gender()).orElse(false)) {
                out.add(new Violation(id(), "'" + w.given() + "' is a name of the other sex than " + w.gender() + " (" + w.id() + ")"));
            }
        }
    }
}
