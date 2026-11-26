// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.util.List;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.surface.WifiNetwork;

/** {@code R-WIFI-PLACE}: a saved network sits at a place of the story; the one network of no place is the one the phone is on. */
public final class RWifiPlace implements OriginRule {
    @Override
    public String id() {
        return "R-WIFI-PLACE";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        int withoutPlace = 0;
        for (WifiNetwork w : v.network().wifi()) {
            if (w.place() == null) {
                withoutPlace++;
            } else if (!story.places().contains(w.place())) {
                out.add(new Violation(id(), "network " + w.ssid() + " sits at " + w.place().name() + ", which is no place of the story"));
            }
        }
        if (withoutPlace > 1) {
            out.add(new Violation(id(), withoutPlace + " saved networks have no place; only the current network may"));
        }
    }
}
