// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.story.MonthCount;
import org.oblivios.decoy.content.story.Timeline;
import org.oblivios.decoy.content.story.VolumeCounts;
import org.oblivios.decoy.content.story.VolumeModel;
import org.oblivios.decoy.content.style.StyleVector.Caps;
import org.oblivios.decoy.content.style.StyleVector.Length;
import org.oblivios.decoy.content.style.StyleVector.Punctuation;
import org.oblivios.decoy.content.style.StyleVector.Register;
import org.oblivios.decoy.content.surface.Contact;
import org.oblivios.decoy.content.surface.People;
import org.oblivios.decoy.content.surface.PeopleSurface;
import org.oblivios.decoy.content.surface.Relation;

class StylesTest {
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final int YEAR = 2026;

    private record Setup(Persona persona, People people, StyleBook book) {}

    private static Setup setup(long n, UnaryOperator<PersonaProfile.Builder> f) {
        PersonaInputs in = new PersonaInputs("Sam", "Example", "Utrecht", "NL", ZoneId.of("Europe/Amsterdam"), Locale.forLanguageTag("nl-NL"));
        Persona p = org.oblivios.decoy.content.TestSetup.derive(Seed.of(n, 1), f.apply(PersonaProfile.builder(in)).build(), YEAR);
        List<MonthCount> months = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            months.add(new MonthCount(YearMonth.of(2026, 10).minusMonths(11 - i), 60, 10, 8, 1_000_000L));
        }
        CountryPack nl = Packs.load("NL").orElseThrow();
        Timeline t = Timeline.generate(p, VolumeModel.fromCounts(new VolumeCounts(months), org.oblivios.decoy.content.Draws.of(Seed.of(n, 2))), nl, NOW, org.oblivios.decoy.content.Draws.of(Seed.of(n, 3)));
        People people = PeopleSurface.generate(p, t, nl, org.oblivios.decoy.content.Draws.of(Seed.of(n, 4)));
        return new Setup(p, people, Styles.book(p, people, org.oblivios.decoy.content.Draws.of(Seed.of(n, 5))));
    }

    private static StyleVector style(Register r) {
        return new StyleVector(r, Caps.LOWER, Punctuation.LIGHT, 0.2, 2, Length.SHORT, 0.03, 0.1);
    }

    @Test
    void chosenPersonaStyleIsKept() {
        StyleVector chosen = style(Register.FORMAL);
        assertEquals(chosen, setup(1, b -> b.style(chosen).voiceNotes(true)).book().persona());
    }

    @Test
    void noVoiceWhenThePersonaSendsNone() {
        assertEquals(0.0, setup(1, b -> b.style(style(Register.CASUAL)).voiceNotes(false)).book().persona().voiceRate());
    }

    @Test
    void personaWritesMoreFormallyToParentsThanToFriends() {
        for (Register r : List.of(Register.NEUTRAL, Register.CASUAL, Register.STREET)) {
            StyleBook book = setup(2, b -> b.style(style(r))).book();
            assertTrue(book.personaTowards().get(Relation.PARENT).register().ordinal()
                    < book.personaTowards().get(Relation.FRIEND).register().ordinal(), r.name());
        }
    }

    @Test
    void streetRegisterNeverTowardsAParent() {
        StyleBook book = setup(3, b -> b.style(style(Register.STREET))).book();
        for (Relation r : List.of(Relation.PARENT, Relation.STEP_PARENT, Relation.GRANDPARENT, Relation.IN_LAW, Relation.COLLEAGUE,
                Relation.MANAGER, Relation.TEACHER, Relation.LANDLORD)) {
            assertNotEquals(Register.STREET, book.personaTowards().get(r).register(), r.name());
        }
        assertEquals(Register.STREET, book.personaTowards().get(Relation.FRIEND).register());
    }

    @Test
    void friendsStayWithinOneRegisterStepOfThePersona() {
        for (long n = 0; n < 10; n++) {
            Setup s = setup(n, b -> b.style(style(Register.CASUAL)));
            for (Contact c : s.people().contacts()) {
                if (c.relation() == Relation.FRIEND || c.relation() == Relation.BEST_FRIEND || c.relation() == Relation.OLD_FRIEND) {
                    int d = s.book().contacts().get(c.id()).register().ordinal() - Register.CASUAL.ordinal();
                    assertTrue(Math.abs(d) <= 1, "friend register " + s.book().contacts().get(c.id()).register());
                }
            }
        }
    }

    @Test
    void streetFriendsOnlyForAStreetPersona() {
        for (long n = 0; n < 10; n++) {
            Setup s = setup(n, b -> b.style(style(Register.CASUAL)));
            s.book().contacts().values().forEach(v -> assertNotEquals(Register.STREET, v.register()));
        }
    }

    @Test
    void colleaguesAreNeutralOrFormal() {
        boolean seen = false;
        for (long n = 0; n < 10; n++) {
            Setup s = setup(n, b -> b.birthYear(YEAR - 30).job(PersonaProfile.JobKind.OFFICE));
            for (Contact c : s.people().contacts()) {
                if (c.relation() == Relation.COLLEAGUE) {
                    Register r = s.book().contacts().get(c.id()).register();
                    assertTrue(r == Register.NEUTRAL || r == Register.FORMAL, r.name());
                    seen = true;
                }
            }
        }
        assertTrue(seen);
    }

    @Test
    void contactOver55WritesStandardCapitalsAndFullPunctuation() {
        boolean seen = false;
        for (long n = 0; n < 5; n++) {
            Setup s = setup(n, b -> b);
            for (Contact c : s.people().contacts()) {
                if (YEAR - c.birthYear() >= 55) {
                    StyleVector v = s.book().contacts().get(c.id());
                    assertEquals(Caps.STANDARD, v.caps());
                    assertEquals(Punctuation.FULL, v.punctuation());
                    seen = true;
                }
            }
        }
        assertTrue(seen);
    }

    @Test
    void everyContactHasAStyle() {
        Setup s = setup(6, b -> b);
        assertEquals(s.people().contacts().size(), s.book().contacts().size());
        for (Relation r : Relation.values()) {
            assertTrue(s.book().personaTowards().containsKey(r), r.name());
        }
    }

    @Test
    void ageBandStaysNearItsCentre() {
        var r = org.oblivios.decoy.content.Draws.of(Seed.of(1, 1)).stream("band");
        for (int i = 0; i < 2000; i++) {
            StyleVector young = Styles.ageBand(20, r);
            assertNotEquals(Register.STREET, young.register());
            assertTrue(young.emojiRate() >= 0.3 * 0.7 - 1e-9 && young.emojiRate() <= 0.3 * 1.3 + 1e-9);
            assertTrue(young.burst() >= 2 && young.burst() <= 4);
            StyleVector old = Styles.ageBand(70, r);
            assertTrue(old.register() == Register.NEUTRAL || old.register() == Register.FORMAL || old.register() == Register.CASUAL);
            assertTrue(old.typoRate() <= 0.03 * 1.3 + 1e-9);
        }
    }

    @Test
    void stepIsBounded() {
        assertEquals(Register.FORMAL, Styles.step(Register.NEUTRAL, -5));
        assertEquals(Register.STREET, Styles.step(Register.CASUAL, 5));
        assertEquals(Register.NEUTRAL, Styles.step(Register.CASUAL, -1));
    }

    @Test
    void sameSeedSameBook() {
        assertEquals(setup(7, b -> b).book(), setup(7, b -> b).book());
    }
}
