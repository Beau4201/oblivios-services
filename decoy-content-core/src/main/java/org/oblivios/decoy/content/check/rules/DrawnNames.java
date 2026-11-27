// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.world.WorldPerson;

/** The people of a world whose given name was drawn from the name data: the rules about that data judge only them. */
final class DrawnNames {
    private DrawnNames() {}

    static List<WorldPerson> of(PlanStory story) {
        Persona p = story.persona();
        Set<String> answered = new HashSet<>();
        p.children().forEach(c -> answered.add(c.givenName().trim()));
        p.siblings().forEach(s -> answered.add(s.calledAs().trim()));
        return story.world().people().stream().filter(w -> !answered.contains(w.given())).toList();
    }
}
