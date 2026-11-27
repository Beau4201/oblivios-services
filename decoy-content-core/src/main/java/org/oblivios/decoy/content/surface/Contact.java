// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;
import java.time.LocalDate;
import org.oblivios.decoy.content.persona.PersonaProfile.FriendGroup;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;

/**
 * A contact of the decoy; {@code origin} is the friend group of a friend and {@code null} for every other relation.
 * {@code group} is {@code null} until a source names contact groups (ADR-353).
 */
public record Contact(String id, String name, String number, boolean favourite, String group, LocalDate birthday,
        Instant lastContacted, PhotoKind photo, Relation relation, int birthYear, Gender gender, FriendGroup origin) {}
