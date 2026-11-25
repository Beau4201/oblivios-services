// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.pool;

import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.style.StyleVector.Register;
import org.oblivios.decoy.content.surface.Relation;

/** One writer of a pool conversation; side {@code A} is the persona and has no relation. */
public record PoolSide(char letter, Relation relation, AgeBand age, Gender gender, Register register) {}
