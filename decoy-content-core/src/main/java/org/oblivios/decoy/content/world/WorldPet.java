// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.world;

import java.util.Objects;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.persona.PersonaProfile.Pet;

/** A pet of the household. The name is the owner's, or empty: no source names pets (ADR-336). */
public record WorldPet(String id, Pet kind, String name, Origin origin) {
    public WorldPet {
        Objects.requireNonNull(origin, "origin");
    }
}
