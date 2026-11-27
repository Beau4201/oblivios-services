// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.world;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.oblivios.decoy.content.surface.Relation;

/** The people, pets and places of one life, its standing choices and the persona's habits. */
public record World(WorldPerson self, List<WorldPerson> people, List<WorldPet> pets, List<WorldPlace> places,
        StandingChoices choices, Habits habits) {
    /** At most this many of the closest people are proposed to the owner. */
    private static final int PROPOSED = 10;
    private static final Set<Relation> PROPOSABLE = Set.of(Relation.PARTNER, Relation.CHILD, Relation.PARENT,
            Relation.SIBLING, Relation.BEST_FRIEND, Relation.STEP_PARENT, Relation.GRANDPARENT, Relation.HOUSEMATE,
            Relation.DATE, Relation.FRIEND);

    public World {
        people = List.copyOf(people);
        pets = List.copyOf(pets);
        places = List.copyOf(places);
    }

    public Optional<WorldPerson> person(String id) {
        return self.id().equals(id) ? Optional.of(self) : people.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public Optional<WorldPlace> place(String id) {
        return places.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** The closest people of family and friends, closest first, for the owner to review. */
    public List<WorldPerson> closest() {
        return people.stream().filter(p -> PROPOSABLE.contains(p.relation()))
                .sorted(Comparator.comparingInt(WorldPerson::closeness)).limit(PROPOSED).toList();
    }
}
