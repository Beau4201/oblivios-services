// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.util.List;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.data.NameData;
import org.oblivios.decoy.content.world.WorldPerson;

/**
 * {@code N-COHORT}: a given name that hardly occurs among those born when the person was: its share then is under
 * {@value #LEAST} of its largest share in any period.
 */
public final class NCohort implements OriginRule {
    static final double LEAST = 0.05;

    @Override
    public String id() {
        return "N-COHORT";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        for (WorldPerson w : DrawnNames.of(story)) {
            if (NameData.load(w.background()).cohortShare(w.given(), w.gender(), w.birthDate().getYear()) < LEAST) {
                out.add(new Violation(id(), "'" + w.given() + "' hardly occurs among those born in " + w.birthDate().getYear()
                        + " (" + w.id() + ")"));
            }
        }
    }
}
