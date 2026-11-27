// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.check.Checks;
import org.oblivios.decoy.content.check.ExaminerRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.surface.MediaItem;

/**
 * {@code T-ONE-PLACE}: two photos further apart than {@value #FASTEST_KMH} km/h allows over the time between them. A
 * distance under {@value #NEAR_METRES} m is the error of a position. A calendar entry has no place in the view, so it
 * is not judged.
 */
public final class TOnePlace implements ExaminerRule {
    static final int FASTEST_KMH = 150;
    static final int NEAR_METRES = 1000;

    @Override
    public String id() {
        return "T-ONE-PLACE";
    }

    @Override
    public void check(PhoneViews v, Persona persona, CountryPack pack, Instant now, List<Violation> out) {
        List<MediaItem> placed = v.media().items().stream().filter(i -> i.lat() != null && i.lon() != null)
                .sorted(Comparator.comparing(MediaItem::taken)).toList();
        for (int i = 1; i < placed.size(); i++) {
            MediaItem a = placed.get(i - 1);
            MediaItem b = placed.get(i);
            double metres = Checks.metres(a.lat(), a.lon(), b.lat(), b.lon());
            double seconds = Duration.between(a.taken(), b.taken()).toMillis() / 1000.0;
            if (metres > NEAR_METRES && metres > seconds * FASTEST_KMH / 3.6) {
                out.add(new Violation(id(), a.name() + " and " + b.name() + " are " + Math.round(metres / 1000) + " km and "
                        + Math.round(seconds / 60) + " min apart"));
            }
        }
    }
}
