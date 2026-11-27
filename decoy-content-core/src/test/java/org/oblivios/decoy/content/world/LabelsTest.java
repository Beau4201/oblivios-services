// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.TestSetup;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.persona.Profiles;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.world.Habits.LabelHabit;

class LabelsTest {
    private static final LanguagePack OWN_WORDS = LanguagePack.of("nl");
    private static final Persona PERSONA = TestSetup.derive(Seed.of(1, 1), PersonaProfile.open(Profiles.inputs()), 2026);

    private static WorldPerson person(Relation relation, Gender gender, String given, String family, String addressedAs) {
        return new WorldPerson("p1", relation, given, family, family, gender, LocalDate.of(1980, 5, 1), "NL", List.of(), null,
                false, null, null, "", addressedAs, "Sanne", 0, null, null, Origin.answer("CIRCLE"));
    }

    private static String label(WorldPerson w, LabelHabit habit, Set<String> taken) {
        return Labels.label(w, PERSONA, habit, OWN_WORDS, taken, Seed.of(2, 2).stream("label"));
    }

    @Test
    void labelsFollowTheHabit() {
        WorldPerson friend = person(Relation.FRIEND, Gender.MALE, "Daan", "Bakker", "Daan");
        assertEquals("Daan", label(friend, LabelHabit.GIVEN, new HashSet<>()));
        assertEquals("Daan Bakker", label(friend, LabelHabit.FULL, new HashSet<>()));
    }

    @Test
    void twoPeopleWithOneGivenNameHaveDistinctLabels() {
        Set<String> taken = new HashSet<>();
        Labels.reserve(List.of("Daan", "Daan", "Eva"), taken);
        assertEquals("Daan Bakker", label(person(Relation.FRIEND, Gender.MALE, "Daan", "Bakker", "Daan"), LabelHabit.GIVEN, taken));
        assertEquals("Daan Visser", label(person(Relation.COLLEAGUE, Gender.MALE, "Daan", "Visser", "Daan"), LabelHabit.GIVEN, taken));
        assertEquals("Eva", label(person(Relation.FRIEND, Gender.FEMALE, "Eva", "Smit", "Eva"), LabelHabit.GIVEN, taken));
    }

    @Test
    void parentsAreSavedByTheirWord() {
        Set<String> taken = new HashSet<>();
        assertEquals("Mam", label(person(Relation.PARENT, Gender.FEMALE, "Anna", "Smit", "mam"), LabelHabit.FULL, taken));
        assertEquals("Oma", label(person(Relation.GRANDPARENT, Gender.FEMALE, "Truus", "Smit", "oma"), LabelHabit.GIVEN, taken));
        assertEquals("Oma Riet", label(person(Relation.GRANDPARENT, Gender.FEMALE, "Riet", "Mol", "oma"), LabelHabit.GIVEN, taken));
    }

    @Test
    void aWordForAParentComesFromTheAnswerOrTheLanguage() {
        WorldPerson mother = person(Relation.PARENT, Gender.FEMALE, "Anna", "Smit", "");
        assertEquals("mammie", Labels.addressedAs(mother, "mammie", OWN_WORDS, Seed.of(3, 3).stream("a")));
        assertTrue(OWN_WORDS.list("kin.PARENT.FEMALE").contains(Labels.addressedAs(mother, "", OWN_WORDS, Seed.of(3, 3).stream("a"))));
        assertEquals("Anna", Labels.addressedAs(mother, "", LanguagePack.of("en"), Seed.of(3, 3).stream("a")),
                "a language without words for a parent uses the name");
    }

    @Test
    void aNameOfTwoPeopleNeverStandsAlone() {
        Set<String> taken = new HashSet<>();
        Labels.reserve(List.of("Daan", "daan"), taken);
        assertEquals(Set.of("daan"), taken);
    }

    @Test
    void servicesAreSavedByRole() {
        assertEquals(Optional.of("Huisarts"), Labels.roleLabel(PlaceKind.DOCTOR, OWN_WORDS));
        assertEquals(Optional.of("Tandarts"), Labels.roleLabel(PlaceKind.DENTIST, OWN_WORDS));
        assertEquals(Optional.empty(), Labels.roleLabel(PlaceKind.CAFE, OWN_WORDS));
        assertEquals(Optional.empty(), Labels.roleLabel(PlaceKind.DOCTOR, LanguagePack.of("en")));
    }
}
