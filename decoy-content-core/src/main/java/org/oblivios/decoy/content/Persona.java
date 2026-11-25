// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content;

import java.util.List;
import java.util.random.RandomGenerator;

/** The invented part of the life story; the owner's own part stays in {@link PersonaInputs}. */
public record Persona(PersonaInputs inputs, int birthYear, String occupation, int householdSize) {

    // shortcut: English occupations only, not per country; upgrade when a pack needs local ones.
    private static final List<String> OCCUPATIONS = List.of(
            "teacher", "nurse", "software developer", "accountant", "electrician", "designer",
            "project manager", "sales representative", "mechanic", "student", "warehouse operator",
            "administrator", "chef", "engineer");

    public static Persona derive(Seed seed, PersonaInputs in) {
        RandomGenerator r = seed.stream("persona");
        int birthYear = 1970 + r.nextInt(33); // 1970..2002
        String occupation = OCCUPATIONS.get(r.nextInt(OCCUPATIONS.size()));
        int household = 1 + r.nextInt(5); // 1..5
        return new Persona(in, birthYear, occupation, household);
    }
}
