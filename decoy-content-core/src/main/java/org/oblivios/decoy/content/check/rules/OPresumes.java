// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.util.List;
import org.oblivios.decoy.content.check.Checks;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.persona.PersonaProfile.Fact;
import org.oblivios.decoy.content.story.Place;
import org.oblivios.decoy.content.story.PlaceKind;

/**
 * {@code O-PRESUMES}: a place of the world presumes what the persona lacks: a work place for a persona without
 * {@code WORKS}. The photos tagged within {@value #PLACE_METRES} m of it are named in the same violation.
 */
public final class OPresumes implements OriginRule {
    static final double PLACE_METRES = 250;

    @Override
    public String id() {
        return "O-PRESUMES";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        if (story.persona().facts().contains(Fact.WORKS)) {
            return;
        }
        for (Place p : story.places()) {
            if (p.kind() != PlaceKind.WORK) {
                continue;
            }
            long photos = v.media().items().stream().filter(i -> i.lat() != null && i.lon() != null
                    && Checks.metres(i.lat(), i.lon(), p.lat(), p.lon()) <= PLACE_METRES).count();
            out.add(new Violation(id(), "work place '" + p.name() + "' for a persona without work (" + story.persona().job() + "), "
                    + photos + " photos tagged there"));
        }
    }
}
