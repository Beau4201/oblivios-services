// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.oblivios.decoy.content.check.Checks;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.PlanStory.Span;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.origin.Sourced;
import org.oblivios.decoy.content.story.Place;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.surface.CalEvent;
import org.oblivios.decoy.content.surface.MediaItem;
import org.oblivios.decoy.content.surface.WifiNetwork;

/**
 * {@code T-AWAY}: inside a span away from home, a calendar entry that is not all-day and not of the span's event, a
 * photo within {@value #HOME_METRES} m of home, or a home network saved for the first time. Today's calendar has no
 * all-day flag, so a yearly entry counts as all-day; an entry is of the span when its origin is the span's event.
 */
public final class TAway implements OriginRule {
    static final double HOME_METRES = 1000;

    @Override
    public String id() {
        return "T-AWAY";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        Optional<Place> home = story.places().stream().filter(p -> p.kind() == PlaceKind.HOME).findFirst();
        for (Span span : story.away()) {
            for (CalEvent e : v.calendar().events()) {
                if (!e.yearly() && inside(e.start(), span) && !ofSpan(e, span)) {
                    out.add(new Violation(id(), "appointment '" + e.title() + "' at " + e.start() + " while away (" + span.what() + ")"));
                }
            }
            if (home.isPresent()) {
                for (MediaItem i : v.media().items()) {
                    if (i.lat() != null && i.lon() != null && inside(i.taken(), span)
                            && Checks.metres(i.lat(), i.lon(), home.get().lat(), home.get().lon()) < HOME_METRES) {
                        out.add(new Violation(id(), "photo " + i.name() + " taken at home while away (" + span.what() + ")"));
                    }
                }
            }
            for (WifiNetwork w : v.network().wifi()) {
                if (w.place() != null && w.place().kind() == PlaceKind.HOME && inside(w.savedAt(), span)) {
                    out.add(new Violation(id(), "home network " + w.ssid() + " first saved while away (" + span.what() + ")"));
                }
            }
        }
    }

    private static boolean inside(Instant at, Span span) {
        return at != null && !at.isBefore(span.from()) && !at.isAfter(span.to());
    }

    private static boolean ofSpan(Object item, Span span) {
        if (!(item instanceof Sourced s)) {
            return false;
        }
        Origin o = s.origin();
        return o != null && o.kind() == Origin.Kind.EVENT && o.id().equals(span.what());
    }
}
