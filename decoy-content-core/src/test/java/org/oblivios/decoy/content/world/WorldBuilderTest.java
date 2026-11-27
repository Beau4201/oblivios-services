// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.TestSetup;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.data.DataSet;
import org.oblivios.decoy.content.data.NameData;
import org.oblivios.decoy.content.data.PlaceData;
import org.oblivios.decoy.content.data.Spreads;
import org.oblivios.decoy.content.persona.ChildAnswer;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.persona.PersonaProfile.CircleSize;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.persona.PersonaProfile.Housing;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.persona.PersonaProfile.Relationship;
import org.oblivios.decoy.content.persona.Profiles;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.surface.Relation;

class WorldBuilderTest {
    private static final CountryPack NL = Packs.load("NL").orElseThrow();
    private static final int MANY = 2000;

    private static Persona persona(long n, UnaryOperator<PersonaProfile.Builder> answers) {
        return TestSetup.derive(Seed.of(n, 5), answers.apply(PersonaProfile.builder(Profiles.inputs())).build(), 2026);
    }

    private static World world(Persona p, long n) {
        return WorldBuilder.build(p, NL, DataSet.of("NL"), Draws.of(Seed.of(n, 6)));
    }

    private static World world(long n) {
        return world(persona(n, b -> b), n);
    }

    @Test
    void namesFitGenderAndCohort() {
        NameData names = NameData.load("NL");
        for (long n = 0; n < MANY; n++) {
            Persona p = persona(n, b -> b);
            for (WorldPerson w : world(p, n).people()) {
                assertTrue(names.cohortShare(w.given(), w.gender(), w.birthDate().getYear()) >= 0.05, w.toString());
                assertTrue(names.genderOf(w.given()).map(g -> g == w.gender()).orElse(true), w.toString());
                assertFalse(w.birthDate().isAfter(p.today()), w.toString());
            }
        }
    }

    @Test
    void familyFollowsTheRules() {
        int children = 0;
        int siblings = 0;
        for (long n = 0; n < MANY; n++) {
            World w = world(n);
            for (WorldPerson one : w.people()) {
                if (one.relation() == Relation.SIBLING) {
                    siblings++;
                    assertEquals(w.self().family(), one.family(), one.toString());
                } else if (one.relation() == Relation.CHILD) {
                    children++;
                    assertTrue(one.parentIds().contains(w.self().id()), one.toString());
                    List<String> parents = one.parentIds().stream().map(i -> w.person(i).orElseThrow().family()).toList();
                    boolean named = parents.contains(one.family()) || parents.size() == 2
                            && (one.family().equals(parents.get(0) + " " + parents.get(1))
                                    || one.family().equals(parents.get(1) + " " + parents.get(0)));
                    assertTrue(named, one.family() + " of " + parents);
                }
            }
            List<String> lines = w.self().parentIds().stream().map(i -> w.person(i).orElseThrow())
                    .flatMap(parent -> parent.parentIds().stream().map(i -> w.person(i).orElseThrow()))
                    .filter(g -> g.gender() == Gender.MALE).map(WorldPerson::family).toList();
            assertEquals(lines.size(), new HashSet<>(lines).size(), "two grandparent lines with one name: " + lines);
        }
        assertTrue(children > 100 && siblings > 100, children + " " + siblings);
    }

    @Test
    void noTwoPeopleShareAFullNameOrLabel() {
        for (long n = 0; n < MANY; n++) {
            World w = world(n);
            Set<String> full = new HashSet<>();
            full.add((w.self().given() + " " + w.self().usedFamily()).toLowerCase(Locale.ROOT));
            Set<String> labels = new HashSet<>();
            for (WorldPerson one : w.people()) {
                assertTrue(full.add((one.given() + " " + one.usedFamily()).toLowerCase(Locale.ROOT)), one.toString());
                if (one.phone() != null) {
                    assertTrue(labels.add(one.label().toLowerCase(Locale.ROOT)), "label twice: " + one.label());
                }
            }
        }
    }

    @Test
    void friendsFollowTheCityFigures() {
        // only backgrounds with a name list can be drawn (ADR-348): the share is the city's among those
        Set<String> drawable = NameData.backgrounds();
        int[] found = new int[drawable.size()];
        List<String> order = drawable.stream().sorted().toList();
        int friends = 0;
        for (long n = 0; n < MANY; n++) {
            Persona p = persona(n, b -> b.circle(CircleSize.MEDIUM));
            for (WorldPerson one : world(p, n).people()) {
                if (one.relation() == Relation.FRIEND) {
                    friends++;
                    assertTrue(drawable.contains(one.background()), one.toString());
                    found[order.indexOf(one.background())]++;
                }
            }
        }
        var population = DataSet.of("NL").population();
        String utrecht = PlaceData.require("NL", "utrecht").municipality();
        double all = order.stream().mapToDouble(b -> population.share(utrecht, 35, b)).sum();
        for (int i = 0; i < order.size(); i++) {
            assertEquals(population.share(utrecht, 35, order.get(i)) / all, found[i] / (double) friends, 0.05, order.get(i));
        }
    }

    @Test
    void backgroundOfFamilyIsThePersonas() {
        for (long n = 0; n < 200; n++) {
            Persona p = persona(n, b -> b);
            for (WorldPerson one : world(p, n).people()) {
                if (one.relation() == Relation.PARENT || one.relation() == Relation.SIBLING || one.relation() == Relation.CHILD
                        || one.relation() == Relation.GRANDPARENT || one.relation() == Relation.RELATIVE) {
                    assertEquals(p.background(), one.background(), one.toString());
                }
            }
        }
    }

    @Test
    void closestProposalHoldsFiveToTen() {
        for (long n = 0; n < 200; n++) {
            Persona p = persona(n, b -> b.relationship(Relationship.MARRIED).birthYear(1990)
                    .children(List.of(new ChildAnswer(4, Gender.FEMALE, "", null, null))).household(3).housing(Housing.RENT));
            List<WorldPerson> closest = world(p, n).closest();
            assertTrue(closest.size() >= 5 && closest.size() <= 10, closest.size() + " seed " + n);
            assertEquals(Relation.PARTNER, closest.get(0).relation());
        }
    }

    @Test
    void youngChildIsAPersonWithoutAPhone() {
        Persona p = persona(1, b -> b.birthYear(1988).relationship(Relationship.SINGLE).housing(Housing.RENT).household(3)
                .children(List.of(new ChildAnswer(2, Gender.FEMALE, "Mila", null, null),
                        new ChildAnswer(14, Gender.MALE, "Sem", null, null))));
        List<WorldPerson> children = world(p, 1).people().stream().filter(x -> x.relation() == Relation.CHILD).toList();
        assertEquals(List.of("Mila", "Sem"), children.stream().map(WorldPerson::given).toList());
        assertNull(children.get(0).phone());
        assertNotNull(children.get(1).phone());
        assertEquals(2, java.time.Period.between(children.get(0).birthDate(), p.today()).getYears());
        assertEquals(14, java.time.Period.between(children.get(1).birthDate(), p.today()).getYears());
    }

    @Test
    void householdIsThePeopleAtHome() {
        for (long n = 0; n < MANY; n++) {
            Persona p = persona(n, b -> b);
            World w = world(p, n);
            List<WorldPerson> home = w.people().stream().filter(WorldPerson::atHome).toList();
            assertEquals(p.household() - 1, home.size(), "seed " + n + " " + p.housing() + " " + home);
            String house = w.self().placeId();
            home.forEach(x -> assertEquals(house, x.placeId(), x.toString()));
        }
    }

    @Test
    void oneOfEachStandingChoice() {
        for (long n = 0; n < 100; n++) {
            StandingChoices c = world(n).choices();
            assertFalse(c.bank().isEmpty() || c.operator().isEmpty() || c.supermarket().isEmpty() || c.homeSsid().isEmpty(),
                    c.toString());
            assertFalse(c.homeSsid().contains("#"), c.homeSsid());
        }
    }

    @Test
    void standingChoicesFollowTheAnswers() {
        World w = world(persona(3, b -> b.bank("Knab").operator("Simyo").supermarket("Jumbo")), 3);
        assertEquals("Knab", w.choices().bank());
        assertEquals("Simyo", w.choices().operator());
        assertEquals("Jumbo", w.choices().supermarket());
    }

    @Test
    void workPlaceOnlyWithAJob() {
        for (long n = 0; n < MANY; n++) {
            Persona p = persona(n, b -> b);
            boolean works = p.job() == JobKind.OFFICE || p.job() == JobKind.SHIFT || p.job() == JobKind.SELF_EMPLOYED;
            assertEquals(works, world(p, n).places().stream().anyMatch(x -> x.kind() == PlaceKind.WORK), p.job().name());
        }
    }

    @Test
    void pointsOfPeopleComeFromThePlaceData() {
        PlaceData places = PlaceData.load("NL");
        String utrecht = PlaceData.require("NL", "utrecht").municipality();
        for (long n = 0; n < 100; n++) {
            World w = world(n);
            WorldPlace home = w.place(w.self().placeId()).orElseThrow();
            assertEquals(PlaceKind.HOME, home.kind());
            assertEquals(utrecht, home.municipality());
            for (WorldPlace place : w.places()) {
                assertNotNull(place.origin(), place.toString());
                if (place.id().startsWith("venue.")) {
                    assertTrue(places.venues(place.municipality(), kindOfVenue(place.id())).stream()
                            .anyMatch(v -> v.id().equals(place.id()) && v.name().equals(place.name())), place.toString());
                }
            }
            for (WorldPerson one : w.people()) {
                Optional<WorldPlace> at = one.placeId() == null ? Optional.empty() : w.place(one.placeId());
                assertTrue(one.placeId() == null || at.isPresent(), one.toString());
            }
        }
    }

    private static org.oblivios.decoy.content.data.Venue.VenueKind kindOfVenue(String id) {
        return org.oblivios.decoy.content.data.Venue.VenueKind.valueOf(id.split("\\.")[2]);
    }

    @Test
    void circleSizeSetsTheNumberOfPeople() {
        long small = 0;
        long large = 0;
        for (long n = 0; n < 200; n++) {
            small += world(persona(n, b -> b.circle(CircleSize.SMALL)), n).people().size();
            large += world(persona(n, b -> b.circle(CircleSize.LARGE)), n).people().size();
        }
        assertTrue(large > small * 1.4, small + " " + large);
    }

    private static void spread(Spreads s, String key, List<Habits> habits, ToDoubleFunction<Habits> value) {
        double low = s.value(key + ".low");
        double high = s.value(key + ".high");
        var found = habits.stream().mapToDouble(value).summaryStatistics();
        assertTrue(found.getMin() >= low && found.getMax() <= high, key + " " + found);
        assertTrue(found.getMax() - found.getMin() > (high - low) / 2, key + " " + found);
    }

    @Test
    void eachPersonaHasItsOwnHabits() {
        Spreads s = DataSet.of("NL").spreads();
        Set<List<Double>> rhythms = new HashSet<>();
        List<Habits> habits = new java.util.ArrayList<>();
        for (long n = 0; n < 1000; n++) {
            Habits h = world(n).habits();
            habits.add(h);
            assertEquals(24, h.rhythm().hourWeights().size());
            assertEquals(1.0, h.rhythm().hourWeights().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
            assertTrue(rhythms.add(h.rhythm().hourWeights()), "two personas with one rhythm, seed " + n);
            assertFalse(h.contactGroups(), "no source names a group (ADR-353)");
        }
        spread(s, "habit.rankExponent", habits, Habits::rankExponent);
        spread(s, "habit.calendarShare", habits, Habits::calendarShare);
        spread(s, "habit.photoShare", habits, Habits::photoShare);
        spread(s, "activity.calls", habits, h -> h.activity().calls());
        spread(s, "activity.texts", habits, h -> h.activity().texts());
        spread(s, "activity.photoBurst", habits, h -> h.activity().photoBurst());
        spread(s, "activity.notes", habits, h -> h.activity().notes());
        spread(s, "activity.browsing", habits, h -> h.activity().browsing());
        spread(s, "activity.callLength", habits, h -> h.activity().callLength());
        spread(s, "tidiness.unusedContacts", habits, h -> h.tidiness().unusedContacts());
        spread(s, "tidiness.unexplainedMessages", habits, h -> h.tidiness().unexplainedMessages());
        spread(s, "tidiness.fullTraceEvents", habits, h -> h.tidiness().fullTraceEvents());
        spread(s, "tidiness.offPhoneCauses", habits, h -> h.tidiness().offPhoneCauses());
    }

    @Test
    void sameDrawsSameWorld() {
        assertEquals(world(7), world(7));
        assertNotEquals(world(7), world(8));
    }
}
