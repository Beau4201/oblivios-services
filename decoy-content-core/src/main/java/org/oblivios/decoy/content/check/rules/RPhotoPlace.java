// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.oblivios.decoy.content.check.Checks;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.PlanStory.Span;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.story.Place;
import org.oblivios.decoy.content.surface.MediaItem;

/**
 * {@code R-PHOTO-PLACE}: a photo's GPS tag lies more than {@value #PLACE_RADIUS_METRES} m from every place of its day:
 * the places of a span away on the days of the span, the places at home otherwise. The generator jitters by about 22 m.
 */
public final class RPhotoPlace implements OriginRule {
    static final double PLACE_RADIUS_METRES = 250;

    @Override
    public String id() {
        return "R-PHOTO-PLACE";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        ZoneId zone = story.zone();
        for (MediaItem i : v.media().items()) {
            if (i.lat() == null || i.lon() == null) {
                continue;
            }
            LocalDate day = LocalDate.ofInstant(i.taken(), zone);
            List<Place> valid = story.places();
            for (Span span : story.away()) {
                if (!day.isBefore(LocalDate.ofInstant(span.from(), zone)) && !day.isAfter(LocalDate.ofInstant(span.to(), zone))) {
                    valid = span.places();
                    break;
                }
            }
            double nearest = Double.MAX_VALUE;
            for (Place place : valid) {
                nearest = Math.min(nearest, Checks.metres(i.lat(), i.lon(), place.lat(), place.lon()));
            }
            if (nearest > PLACE_RADIUS_METRES) {
                out.add(new Violation(id(), i.name() + " on " + day + " lies " + Math.round(nearest) + " m from every place of that day"));
            }
        }
    }
}
