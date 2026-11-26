// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.util.ArrayList;
import java.util.List;
import org.oblivios.decoy.content.check.Checks;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.story.Place;

/**
 * {@code S-PHYSICAL}: a place of the story, at home or on a trip, is named after a company of the pack's
 * {@code senders.SHOP.online}, which has no shop to visit. A company matches as whole words, ignoring case.
 */
public final class SPhysical implements OriginRule {
    @Override
    public String id() {
        return "S-PHYSICAL";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        List<String> online = Checks.packOf(story.persona()).onlineShops();
        List<Place> places = new ArrayList<>(story.places());
        story.away().forEach(s -> places.addAll(s.places()));
        for (Place p : places) {
            for (String company : online) {
                if (Checks.hasPhrase(p.name(), company)) {
                    out.add(new Violation(id(), "place '" + p.name() + "' is named after " + company + ", which sells only online"));
                }
            }
        }
    }
}
