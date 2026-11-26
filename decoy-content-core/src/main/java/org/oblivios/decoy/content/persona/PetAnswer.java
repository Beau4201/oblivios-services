// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.persona;

import org.oblivios.decoy.content.persona.PersonaProfile.Pet;

/** The answer about one pet. In a persona the name is set, empty for the one the world draws. */
public record PetAnswer(Pet kind, String name) {}
