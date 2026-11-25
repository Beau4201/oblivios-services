// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.persona.PersonaProfile.CircleSize;
import org.oblivios.decoy.content.persona.PersonaProfile.Fact;
import org.oblivios.decoy.content.persona.PersonaProfile.FriendGroup;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.persona.PersonaProfile.Housing;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.persona.PersonaProfile.Pet;
import org.oblivios.decoy.content.persona.PersonaProfile.Relationship;
import org.oblivios.decoy.content.persona.PersonaProfile.Stage;
import org.oblivios.decoy.content.persona.PersonaProfile.Texting;
import org.oblivios.decoy.content.persona.PersonaProfile.Transport;
import org.oblivios.decoy.content.style.StyleVector;

class PersonaProfileTest {
    private static final int YEAR = 2026;

    private static PersonaInputs inputs(Locale locale) {
        return new PersonaInputs("Sam Example", "Utrecht", "NL", ZoneId.of("Europe/Amsterdam"), locale);
    }

    private static PersonaInputs inputs() {
        return inputs(Locale.forLanguageTag("nl-NL"));
    }

    private static PersonaProfile open() {
        return PersonaProfile.open(inputs());
    }

    private static Persona derive(long n, PersonaProfile p) {
        return Persona.derive(Seed.of(n, 1), p, YEAR);
    }

    private static StyleVector style() {
        return new StyleVector(StyleVector.Register.NEUTRAL, StyleVector.Caps.STANDARD, StyleVector.Punctuation.FULL, 0.1, 1,
                StyleVector.Length.MEDIUM, 0.02, 0.05);
    }

    private static PersonaProfile full() {
        return new PersonaProfile(inputs(), Gender.FEMALE, 1988, "NL", List.of("nl", "en"), "en", JobKind.OFFICE, "finance",
                Relationship.MARRIED, List.of(2, 9), 4, Housing.OWN, Set.of(Pet.DOG), Transport.BIKE, true, false, List.of(35),
                Set.of(FriendGroup.STUDY, FriendGroup.SPORT), CircleSize.LARGE, Set.of("cooking"), Set.of("christmas"),
                Texting.HIGH, false, style());
    }

    /** The open profile with one field given, by builder. */
    private static PersonaProfile with(java.util.function.UnaryOperator<PersonaProfile.Builder> f) {
        return f.apply(PersonaProfile.builder(inputs())).build();
    }

    @Test
    void givenFieldsAreKept() {
        Persona p = derive(1, full());
        PersonaProfile f = full();
        assertEquals(f.gender(), p.gender());
        assertEquals(1988, p.birthYear());
        assertEquals("NL", p.nationality());
        assertEquals(List.of("nl", "en"), p.languages());
        assertEquals("nl", p.language());
        assertEquals("en", p.mixLanguage());
        assertEquals(JobKind.OFFICE, p.job());
        assertEquals("finance", p.jobField());
        assertEquals(Relationship.MARRIED, p.relationship());
        assertEquals(List.of(2, 9), p.childAges());
        assertEquals(4, p.household());
        assertEquals(Housing.OWN, p.housing());
        assertEquals(Set.of(Pet.DOG), p.pets());
        assertEquals(Transport.BIKE, p.transport());
        assertTrue(p.parentsAlive());
        assertFalse(p.parentsTogether());
        assertEquals(List.of(35), p.siblingAges());
        assertEquals(Set.of(FriendGroup.STUDY, FriendGroup.SPORT), p.friendGroups());
        assertEquals(CircleSize.LARGE, p.circle());
        assertEquals(Set.of("cooking"), p.interests());
        assertEquals(Set.of("christmas"), p.holidays());
        assertEquals(Texting.HIGH, p.texting());
        assertFalse(p.voiceNotes());
        assertEquals(style(), p.style());
    }

    @Test
    void openFieldsAreAllDrawn() {
        Persona p = derive(2, open());
        assertNotNull(p.gender());
        assertNotNull(p.job());
        assertNotNull(p.relationship());
        assertNotNull(p.housing());
        assertNotNull(p.transport());
        assertNotNull(p.circle());
        assertNotNull(p.texting());
        assertNotNull(p.style());
        assertFalse(p.friendGroups().isEmpty());
    }

    @Test
    void openBirthYearIsBetween16And70YearsAgo() {
        for (long n = 0; n < 1000; n++) {
            int y = derive(n, open()).birthYear();
            assertTrue(y >= YEAR - 70 && y <= YEAR - 16, "birth year " + y);
        }
    }

    @Test
    void childrenAreAtLeast16YearsYoungerThanThePersona() {
        boolean anyChild = false;
        for (long n = 0; n < 1000; n++) {
            Persona p = derive(n, open());
            int age = YEAR - p.birthYear();
            for (int c : p.childAges()) {
                anyChild = true;
                assertTrue(c >= 0 && c <= age - 16, "child of " + c + " for a persona of " + age);
            }
        }
        assertTrue(anyChild, "no open profile drew a child");
    }

    @Test
    void studentIsBetween16And35WhenDrawn() {
        for (long n = 0; n < 1000; n++) {
            int age = YEAR - derive(n, with(b -> b.job(JobKind.STUDENT))).birthYear();
            assertTrue(age >= 16 && age <= 35, "student of " + age);
            Persona drawn = derive(n, open());
            if (drawn.job() == JobKind.STUDENT) {
                int a = YEAR - drawn.birthYear();
                assertTrue(a >= 16 && a <= 35, "drawn student of " + a);
            }
        }
    }

    @Test
    void contradictoryProfileIsRejected() {
        IllegalArgumentException child = assertThrows(IllegalArgumentException.class,
                () -> derive(1, with(b -> b.birthYear(YEAR - 17).childAges(List.of(20)))));
        assertTrue(child.getMessage().contains("childAges"), child.getMessage());
        IllegalArgumentException household = assertThrows(IllegalArgumentException.class,
                () -> derive(1, with(b -> b.relationship(Relationship.MARRIED).household(1))));
        assertTrue(household.getMessage().contains("household"), household.getMessage());
    }

    @Test
    void openFieldsRespectTheGivenOnes() {
        for (long n = 0; n < 300; n++) {
            Persona p = derive(n, with(b -> b.relationship(Relationship.MARRIED)));
            assertTrue(p.household() >= 2, "married persona living alone");
            Persona q = derive(n, with(b -> b.childAges(List.of(30))));
            assertTrue(YEAR - q.birthYear() >= 46, "persona too young for a child of 30");
        }
    }

    @Test
    void partnerFactFollowsTheRelationship() {
        for (Relationship rel : Relationship.values()) {
            Persona p = derive(3, with(b -> b.relationship(rel).household(2)));
            boolean partner = rel == Relationship.PARTNER || rel == Relationship.MARRIED;
            assertEquals(partner, p.facts().contains(Fact.PARTNER), rel.name());
        }
        assertTrue(derive(3, with(b -> b.relationship(Relationship.SINGLE))).facts().contains(Fact.SINGLE));
    }

    @Test
    void stagesFollowTheFacts() {
        Persona parent = derive(4, with(b -> b.birthYear(YEAR - 38).childAges(List.of(2, 9))));
        assertTrue(parent.stages().contains(Stage.PARENT_SMALL));
        assertTrue(parent.stages().contains(Stage.PARENT_SCHOOL));
        assertFalse(parent.stages().contains(Stage.PARENT_TEEN));
        Persona shift = derive(4, with(b -> b.birthYear(YEAR - 30).job(JobKind.SHIFT)));
        assertTrue(shift.stages().contains(Stage.SHIFT_WORKER));
        assertFalse(shift.stages().contains(Stage.YOUNG_WORKER));
        Persona young = derive(4, with(b -> b.birthYear(YEAR - 21).job(JobKind.OFFICE)));
        assertTrue(young.stages().contains(Stage.YOUNG_WORKER));
        Persona expat = derive(4, with(b -> b.nationality("PL")));
        assertTrue(expat.stages().contains(Stage.EXPAT));
        assertTrue(expat.facts().contains(Fact.EXPAT));
    }

    @Test
    void languagesAreNeverDrawnFromNothing() {
        assertEquals(List.of("nl"), derive(5, open()).languages());
        assertEquals("en", derive(5, PersonaProfile.open(inputs(Locale.US))).language());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> derive(5, PersonaProfile.open(inputs(Locale.GERMANY))));
        assertEquals("no language data for de", e.getMessage());
        IllegalArgumentException given = assertThrows(IllegalArgumentException.class,
                () -> derive(5, with(b -> b.languages(List.of("fr")))));
        assertEquals("no language data for fr", given.getMessage());
    }

    @Test
    void aGivenFieldNeverMovesADrawnOne() {
        for (long n = 0; n < 50; n++) {
            Persona a = derive(n, open());
            Persona b = derive(n, with(x -> x.gender(a.gender() == Gender.FEMALE ? Gender.MALE : Gender.FEMALE)));
            assertEquals(a.birthYear(), b.birthYear());
            assertEquals(a.pets(), b.pets());
            assertEquals(a.circle(), b.circle());
            assertEquals(a.texting(), b.texting());
            assertEquals(a.friendGroups(), b.friendGroups());
        }
    }

    @Test
    void sameSeedSamePersona() {
        assertEquals(derive(7, open()), derive(7, open()));
        assertEquals(derive(7, full()), derive(7, full()));
    }
}
