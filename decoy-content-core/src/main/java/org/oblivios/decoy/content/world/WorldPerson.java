// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.world;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.oblivios.decoy.content.country.PhoneNumber;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.persona.PersonaProfile.FriendGroup;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.surface.Relation;

/**
 * One person of the world. {@code family} is the name a person was born with, {@code usedFamily} the one they use.
 * {@code parentIds} name the parents that are in the world. {@code placeId} is where the story knows the person to
 * live, {@code null} where it does not; {@code workplaceId} is set for those who work with the persona. {@code phone}
 * is {@code null} for a person without one, whose {@code label} is empty. {@code closeness} 0 is the closest.
 */
public record WorldPerson(String id, Relation relation, String given, String family, String usedFamily, Gender gender,
        LocalDate birthDate, String background, List<String> parentIds, String partnerId, boolean atHome, String placeId,
        PhoneNumber phone, String label, String addressedAs, String addressesPersonaAs, int closeness, FriendGroup group,
        String workplaceId, Origin origin) {
    public WorldPerson {
        Objects.requireNonNull(origin, "origin");
        parentIds = List.copyOf(parentIds);
    }
}
