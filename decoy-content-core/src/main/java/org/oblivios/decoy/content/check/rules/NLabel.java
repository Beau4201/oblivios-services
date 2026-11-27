// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.surface.Contact;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.world.Habits.LabelHabit;
import org.oblivios.decoy.content.world.WorldPerson;

/**
 * {@code N-LABEL}: a saved label that does not follow the persona's labelling habit, one that repeats another, or a
 * contact saved under another label than its person's. A parent or grandparent is saved by the word for them. Under
 * the habit of full names a given name alone follows no habit; under the habit of given names a full name stands only
 * where two bear the given name.
 */
public final class NLabel implements OriginRule {
    @Override
    public String id() {
        return "N-LABEL";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        List<WorldPerson> saved = story.world().people().stream().filter(w -> w.phone() != null).toList();
        Map<String, Integer> givens = new HashMap<>();
        saved.forEach(w -> givens.merge(first(w.given()), 1, Integer::sum));
        Set<String> labels = new HashSet<>();
        Map<String, WorldPerson> byId = new HashMap<>();
        for (WorldPerson w : saved) {
            byId.put(w.id(), w);
            if (!labels.add(w.label().toLowerCase(Locale.ROOT))) {
                out.add(new Violation(id(), "two people are saved as '" + w.label() + "'"));
            }
            if (!follows(w, story.world().habits().labels(), givens.get(first(w.given())) > 1)) {
                out.add(new Violation(id(), "'" + w.label() + "' does not follow the habit "
                        + story.world().habits().labels() + " (" + w.id() + ")"));
            }
        }
        for (Contact c : v.people().contacts()) {
            WorldPerson w = byId.get(c.id());
            if (w != null && !w.label().equals(c.name())) {
                out.add(new Violation(id(), "the contact '" + c.name() + "' is saved as '" + w.label() + "' in the world"));
            }
        }
    }

    private static String first(String name) {
        return name.split("\\s+")[0].toLowerCase(Locale.ROOT);
    }

    private static boolean follows(WorldPerson w, LabelHabit habit, boolean sharedGiven) {
        String label = w.label().toLowerCase(Locale.ROOT);
        String given = w.given().toLowerCase(Locale.ROOT);
        String full = w.usedFamily().isBlank() ? given : given + " " + w.usedFamily().toLowerCase(Locale.ROOT);
        boolean elder = w.relation() == Relation.PARENT || w.relation() == Relation.GRANDPARENT;
        if (elder && !w.addressedAs().equals(w.given())) {
            String word = w.addressedAs().toLowerCase(Locale.ROOT);
            return label.equals(word) || label.equals(word + " " + given)
                    || label.equals(word + " " + w.usedFamily().toLowerCase(Locale.ROOT));
        }
        if (label.matches(java.util.regex.Pattern.quote(full) + "( \\d+)?")) {
            return habit == LabelHabit.FULL || sharedGiven || label.equals(given);
        }
        return habit == LabelHabit.GIVEN && label.equals(given);
    }
}
