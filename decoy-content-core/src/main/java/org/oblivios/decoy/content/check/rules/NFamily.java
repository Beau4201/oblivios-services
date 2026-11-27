// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.world.World;
import org.oblivios.decoy.content.world.WorldPerson;

/**
 * {@code N-FAMILY}: family names that no family gives: a brother or sister with another name than the persona, a child
 * with neither parent's name, or the two lines of grandparents with one name.
 */
public final class NFamily implements OriginRule {
    @Override
    public String id() {
        return "N-FAMILY";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        World world = story.world();
        for (WorldPerson w : world.people()) {
            if (w.relation() == Relation.SIBLING && !w.family().equals(world.self().family())) {
                out.add(new Violation(id(), "a brother or sister is a '" + w.family() + "', the persona a '"
                        + world.self().family() + "' (" + w.id() + ")"));
            } else if (w.relation() == Relation.CHILD && !named(w, parents(world, w))) {
                out.add(new Violation(id(), "a child '" + w.family() + "' bears neither parent's name (" + w.id() + ")"));
            }
        }
        Set<String> lines = new HashSet<>();
        for (WorldPerson parent : parents(world, world.self())) {
            for (WorldPerson grandparent : parents(world, parent)) {
                if (grandparent.gender() == Gender.MALE && !lines.add(grandparent.family())) {
                    out.add(new Violation(id(), "both lines of grandparents are a '" + grandparent.family() + "'"));
                }
            }
        }
    }

    private static List<WorldPerson> parents(World world, WorldPerson child) {
        return child.parentIds().stream().flatMap(i -> world.person(i).stream()).toList();
    }

    private static boolean named(WorldPerson child, List<WorldPerson> parents) {
        List<String> names = parents.stream().map(WorldPerson::family).toList();
        return names.contains(child.family()) || names.size() == 2
                && (child.family().equals(names.get(0) + " " + names.get(1))
                        || child.family().equals(names.get(1) + " " + names.get(0)));
    }
}
