// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.persona;

import java.time.DayOfWeek;
import java.util.Set;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;

/**
 * The answer about one child; a {@code null} field is open. In a persona every field is set, and an empty given name is
 * the one the world draws.
 */
public record ChildAnswer(Integer age, Gender gender, String givenName, Custody custody, Set<DayOfWeek> daysWithPersona) {

    /** Where the child lives: at home, at home on some days of the week, or elsewhere. */
    public enum Custody { HOME, SHARED, ELSEWHERE }

    public ChildAnswer {
        daysWithPersona = daysWithPersona == null ? null : Set.copyOf(daysWithPersona);
    }
}
