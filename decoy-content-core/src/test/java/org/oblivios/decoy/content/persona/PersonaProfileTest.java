// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.persona.ChildAnswer.Custody;
import org.oblivios.decoy.content.persona.PersonaProfile.ActivityKind;
import org.oblivios.decoy.content.persona.PersonaProfile.Fact;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.persona.PersonaProfile.Pet;
import org.oblivios.decoy.content.persona.PersonaProfile.Relationship;
import org.oblivios.decoy.content.persona.PersonaProfile.Stage;
import org.oblivios.decoy.content.persona.PersonaProfile.TravelMode;

class PersonaProfileTest {
    private static final int YEAR = 2026;
    private static final LocalDate TODAY = LocalDate.of(YEAR, 11, 26);
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");

    private static PersonaInputs inputs(Locale locale) {
        return new PersonaInputs("Sanne", "de Vries", "utrecht", "NL", ZONE, locale);
    }

    private static PersonaInputs inputs() {
        return inputs(Locale.forLanguageTag("nl-NL"));
    }

    private static PersonaProfile open() {
        return PersonaProfile.open(inputs());
    }

    private static CountryPack nl() {
        return Packs.forDevice("NL", ZONE, Locale.forLanguageTag("nl-NL")).pack();
    }

    private static Persona derive(long n, PersonaProfile p) {
        return Persona.derive(Draws.of(Seed.of(n, 1)), p, TODAY, nl());
    }

    /** The open profile with answers given, by builder. */
    private static PersonaProfile with(UnaryOperator<PersonaProfile.Builder> f) {
        return f.apply(PersonaProfile.builder(inputs())).build();
    }

    private static ChildAnswer childAged(int age) {
        return new ChildAnswer(age, null, null, null, null);
    }

    private static List<ChildAnswer> aged(int... ages) {
        return java.util.Arrays.stream(ages).mapToObj(PersonaProfileTest::childAged).toList();
    }

    private static void refuses(String field, UnaryOperator<PersonaProfile.Builder> f) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> derive(1, with(f)), field);
        assertTrue(e.getMessage().startsWith(field + ": "), "the message names the field " + field + ": " + e.getMessage());
    }

    @Test
    void givenFieldsAreKept() {
        PersonaProfile f = Profiles.full();
        Persona p = derive(1, f);
        assertEquals(f.gender(), p.gender());
        assertEquals(1988, p.birthYear());
        assertEquals("NL", p.nationality());
        assertEquals(List.of("nl", "en"), p.languages());
        assertEquals("nl", p.language());
        assertEquals("en", p.mixLanguage());
        assertEquals("NL", p.background());
        assertEquals(f.job(), p.job());
        assertEquals("finance", p.jobField());
        assertEquals(f.work(), p.work());
        assertEquals(f.relationship(), p.relationship());
        assertTrue(p.partnerAtHome());
        assertEquals(f.children(), p.children());
        assertEquals(List.of(2, 9), p.childAges());
        assertEquals(4, p.household());
        assertEquals(f.housing(), p.housing());
        assertEquals(f.pets(), p.pets());
        assertEquals(f.transport(), p.transport());
        assertEquals(f.parents(), p.parents());
        assertTrue(p.parentsAlive());
        assertFalse(p.parentsTogether());
        assertEquals(f.siblings(), p.siblings());
        assertEquals(f.friendGroups(), p.friendGroups());
        assertEquals(f.activities(), p.activities());
        assertEquals(f.circle(), p.circle());
        assertEquals(f.interests(), p.interests());
        assertEquals(f.holidays(), p.holidays());
        assertEquals("ING", p.bank());
        assertEquals("KPN", p.operator());
        assertEquals("Albert Heijn", p.supermarket());
        assertEquals(f.texting(), p.texting());
        assertFalse(p.voiceNotes());
        assertEquals(f.style(), p.style());
        assertEquals(f.trips(), p.trips());
        assertEquals(TODAY, p.today());
    }

    @Test
    void openFieldsAreAllDrawn() throws ReflectiveOperationException {
        for (long n = 0; n < 200; n++) {
            Persona p = derive(n, open());
            for (RecordComponent c : Persona.class.getRecordComponents()) {
                assertNotNull(c.getAccessor().invoke(p), c.getName() + " is open in " + p);
            }
            p.children().forEach(c -> assertTrue(c.age() != null && c.gender() != null && c.givenName() != null && c.custody() != null
                    && c.daysWithPersona() != null, c.toString()));
            p.pets().forEach(a -> assertTrue(a.kind() != null && a.name() != null, a.toString()));
            p.parents().forEach(a -> assertTrue(a.gender() != null && a.alive() != null && a.calledAs() != null, a.toString()));
            p.siblings().forEach(a -> assertTrue(a.age() != null && a.gender() != null && a.calledAs() != null, a.toString()));
            assertFalse(p.friendGroups().isEmpty());
            assertEquals(List.of(), p.trips(), "no trip is invented");
            assertTrue(nl().senders(org.oblivios.decoy.content.country.SenderKind.BANK).stream().anyMatch(s -> s.name().equals(p.bank())));
            assertTrue(nl().supermarkets().contains(p.supermarket()));
        }
    }

    @Test
    void anOpenWorkPatternFollowsTheJob() {
        for (long n = 0; n < 200; n++) {
            Persona p = derive(n, open());
            boolean none = p.job() == JobKind.RETIRED || p.job() == JobKind.NONE || p.job() == JobKind.JOB_SEEKER;
            assertEquals(none, p.work().days().isEmpty(), p.job() + " " + p.work());
        }
    }

    @Test
    void openActivitiesFollowTheFriendGroups() {
        boolean any = false;
        for (long n = 0; n < 200; n++) {
            Persona p = derive(n, open());
            for (ActivityAnswer a : p.activities()) {
                any = true;
                assertTrue(p.friendGroups().contains(a.group()), a.toString());
                assertTrue(a.minutes() > 0 && a.day() != null && a.start() != null && a.kind() != null, a.toString());
            }
        }
        assertTrue(any, "no open profile drew an activity");
    }

    @Test
    void nameIsAskedInParts() {
        PersonaInputs in = derive(1, open()).inputs();
        assertEquals("Sanne", in.givenName());
        assertEquals("de Vries", in.familyName());
    }

    @Test
    void unknownCityIsRefused() {
        PersonaInputs atlantis = new PersonaInputs("Sanne", "de Vries", "atlantis", "NL", ZONE, Locale.forLanguageTag("nl-NL"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> derive(1, PersonaProfile.open(atlantis)));
        assertTrue(e.getMessage().contains("city"), e.getMessage());
    }

    @Test
    void childWithSharedCustodyNeedsDays() {
        refuses("children", b -> b.birthYear(YEAR - 40).children(List.of(new ChildAnswer(6, null, null, Custody.SHARED, null))));
        refuses("children", b -> b.birthYear(YEAR - 40).children(List.of(new ChildAnswer(6, null, null, Custody.SHARED, Set.of()))));
        Persona p = derive(1, with(b -> b.birthYear(YEAR - 40)
                .children(List.of(new ChildAnswer(6, null, null, Custody.SHARED, Set.of(DayOfWeek.MONDAY))))));
        assertEquals(Set.of(DayOfWeek.MONDAY), p.children().get(0).daysWithPersona());
    }

    @Test
    void childrenKeepTheirAnswers() {
        List<ChildAnswer> given = List.of(new ChildAnswer(12, Gender.MALE, "Sem", Custody.ELSEWHERE, Set.of()),
                new ChildAnswer(3, Gender.FEMALE, "Mila", Custody.HOME, Set.of()));
        Persona p = derive(1, with(b -> b.birthYear(YEAR - 40).relationship(Relationship.SINGLE).household(2).children(given)));
        assertEquals(given, p.children());
        assertEquals(List.of(12, 3), p.childAges(), "in the order given");
    }

    @Test
    void aChildElsewhereDoesNotLiveAtHome() {
        for (long n = 0; n < 50; n++) {
            Persona p = derive(n, with(b -> b.birthYear(YEAR - 45).relationship(Relationship.SINGLE).housing(PersonaProfile.Housing.RENT)
                    .children(List.of(new ChildAnswer(8, null, null, Custody.ELSEWHERE, null)))));
            assertEquals(1, p.household(), p.toString());
            assertFalse(p.facts().contains(Fact.SINGLE_PARENT));
        }
    }

    @Test
    void petsKeepTheirNames() {
        List<PetAnswer> pets = List.of(new PetAnswer(Pet.DOG, "Bo"), new PetAnswer(Pet.DOG, "Teun"), new PetAnswer(Pet.CAT, null));
        Persona p = derive(1, with(b -> b.pets(pets)));
        assertEquals(List.of(new PetAnswer(Pet.DOG, "Bo"), new PetAnswer(Pet.DOG, "Teun"), new PetAnswer(Pet.CAT, "")), p.pets());
        assertTrue(p.facts().contains(Fact.DOG) && p.facts().contains(Fact.CAT) && !p.facts().contains(Fact.OTHER_PET));
        refuses("pets", b -> b.pets(List.of(new PetAnswer(null, "Bo"))));
    }

    @Test
    void workPatternWithoutAJobIsRefused() {
        WorkPattern week = new WorkPattern(Set.of(DayOfWeek.MONDAY), LocalTime.of(9, 0), LocalTime.of(17, 0), false);
        for (JobKind job : List.of(JobKind.RETIRED, JobKind.NONE, JobKind.JOB_SEEKER)) {
            refuses("work", b -> b.job(job).work(week));
        }
        refuses("work", b -> b.job(JobKind.OFFICE).work(new WorkPattern(Set.of(), LocalTime.of(9, 0), LocalTime.of(17, 0), false)));
        refuses("work", b -> b.job(JobKind.OFFICE)
                .work(new WorkPattern(Set.of(DayOfWeek.MONDAY), LocalTime.of(9, 0), LocalTime.of(9, 0), false)));
        refuses("work", b -> b.job(JobKind.OFFICE).work(new WorkPattern(Set.of(DayOfWeek.MONDAY), null, LocalTime.of(9, 0), false)));
    }

    @Test
    void nightShiftCrossesMidnight() {
        WorkPattern night = new WorkPattern(Set.of(DayOfWeek.MONDAY), LocalTime.of(22, 0), LocalTime.of(6, 0), false);
        assertEquals(night, derive(1, with(b -> b.job(JobKind.SHIFT).work(night))).work());
    }

    @Test
    void bankMustBeABankOfThePack() {
        refuses("bank", b -> b.bank("Barclays"));
        assertEquals("Knab", derive(1, with(b -> b.bank("Knab"))).bank());
    }

    @Test
    void operatorMustBeAnOperatorOfThePack() {
        refuses("operator", b -> b.operator("Orange"));
        assertEquals("Simyo", derive(1, with(b -> b.operator("Simyo"))).operator());
    }

    @Test
    void supermarketMustBeOfThePack() {
        refuses("supermarket", b -> b.supermarket("Tesco"));
        assertEquals("Jumbo", derive(1, with(b -> b.supermarket("Jumbo"))).supermarket());
    }

    @Test
    void tripsLieInThePastYear() {
        refuses("trips", b -> b.trips(List.of(new TripAnswer(YearMonth.of(2025, 10), 5, "FR", TravelMode.CAR))));
        refuses("trips", b -> b.trips(List.of(new TripAnswer(YearMonth.of(2026, 12), 5, "FR", TravelMode.CAR))));
        refuses("trips", b -> b.trips(List.of(new TripAnswer(YearMonth.of(2026, 5), 0, "FR", TravelMode.CAR))));
        List<TripAnswer> past = List.of(new TripAnswer(YearMonth.of(2025, 11), 5, "FR", TravelMode.CAR),
                new TripAnswer(YearMonth.of(2026, 11), 2, "BE", TravelMode.TRAIN));
        assertEquals(past, derive(1, with(b -> b.trips(past))).trips());
    }

    @Test
    void activitiesNeedAKindADayAStartAndADuration() {
        refuses("activities", b -> b.activities(List.of(new ActivityAnswer(ActivityKind.GYM, DayOfWeek.MONDAY, LocalTime.NOON, 0, null))));
        refuses("activities", b -> b.activities(List.of(new ActivityAnswer(null, DayOfWeek.MONDAY, LocalTime.NOON, 60, null))));
    }

    @Test
    void parentsAreAtMostTwoAndTogetherOnlyWhenBothLive() {
        ParentAnswer mother = new ParentAnswer(Gender.FEMALE, true, null);
        ParentAnswer father = new ParentAnswer(Gender.MALE, false, null);
        refuses("parents", b -> b.parents(List.of(mother, father, mother)));
        refuses("parentsTogether", b -> b.parents(List.of(mother, father)).parentsTogether(true));
        refuses("parentsTogether", b -> b.parents(List.of(mother)).parentsTogether(true));
        refuses("housing", b -> b.parents(List.of(father)).housing(PersonaProfile.Housing.WITH_PARENTS));
        Persona none = derive(1, with(b -> b.parents(List.of()).housing(PersonaProfile.Housing.RENT)));
        assertFalse(none.parentsAlive());
        assertFalse(none.parentsTogether());
    }

    @Test
    void aPartnerAtHomeNeedsAPartnerAndARoomInTheHousehold() {
        refuses("partnerAtHome", b -> b.relationship(Relationship.SINGLE).partnerAtHome(true));
        refuses("household", b -> b.relationship(Relationship.PARTNER).partnerAtHome(true).household(1));
        for (long n = 0; n < 100; n++) {
            assertFalse(derive(n, with(b -> b.relationship(Relationship.PARTNER).household(1))).partnerAtHome());
        }
    }

    @Test
    void birthDateFallsInTheBirthYear() {
        Set<Integer> months = new java.util.HashSet<>();
        for (long n = 0; n < 300; n++) {
            Persona p = derive(n, with(b -> b.birthYear(1988)));
            assertEquals(1988, p.birthDate().getYear());
            months.add(p.birthDate().getMonthValue());
            Persona young = derive(n, with(b -> b.birthYear(YEAR - 16)));
            assertTrue(young.age() >= 16, "a persona of the youngest year has had the birthday: " + young.birthDate());
        }
        assertEquals(12, months.size(), "birthdays fall in every month");
    }

    @Test
    void ageFollowsTheBirthday() {
        Persona p = bornOn(derive(1, with(b -> b.birthYear(2000))), LocalDate.of(2000, 12, 1));
        assertEquals(25, p.age(LocalDate.of(2026, 11, 26)));
        assertEquals(25, p.age());
        assertEquals(26, p.age(LocalDate.of(2026, 12, 1)));
    }

    private static Persona bornOn(Persona p, LocalDate birthDate) {
        return new Persona(p.inputs(), p.gender(), birthDate, p.nationality(), p.languages(), p.mixLanguage(), p.background(), p.job(),
                p.jobField(), p.work(), p.relationship(), p.partnerAtHome(), p.children(), p.household(), p.housing(), p.pets(),
                p.transport(), p.parents(), p.parentsTogether(), p.siblings(), p.friendGroups(), p.activities(), p.circle(),
                p.interests(), p.holidays(), p.bank(), p.operator(), p.supermarket(), p.texting(), p.voiceNotes(), p.style(), p.trips(),
                p.today());
    }

    @Test
    void openBirthYearIsBetween16And70YearsAgo() {
        for (long n = 0; n < 1000; n++) {
            int y = derive(n, open()).birthYear();
            assertTrue(y >= YEAR - 70 && y <= YEAR - 16, "birth year " + y);
        }
    }

    @Test
    void anOpenGenderIsFemaleOrMale() {
        // owner, 2026-11-25: no third value, since no conversation of the pool could fit it
        Set<String> seen = new java.util.TreeSet<>();
        for (long n = 0; n < 1000; n++) {
            seen.add(derive(n, open()).gender().name());
        }
        assertEquals(Set.of("FEMALE", "MALE"), seen);
    }

    @Test
    void childrenAreAtLeast16YearsYoungerThanThePersona() {
        boolean anyChild = false;
        for (long n = 0; n < 1000; n++) {
            Persona p = derive(n, open());
            for (int c : p.childAges()) {
                anyChild = true;
                assertTrue(c >= 0 && c <= p.age() - 16, "child of " + c + " for a persona of " + p.age());
            }
        }
        assertTrue(anyChild, "no open profile drew a child");
    }

    @Test
    void aGivenChildFitsTheDrawnBirthday() {
        for (long n = 0; n < 500; n++) {
            Persona p = derive(n, with(b -> b.birthYear(YEAR - 38).children(aged(22))));
            assertTrue(p.age() >= 38, "a child of 22 needs a persona of 38 today: " + p.birthDate());
            Persona q = derive(n, with(b -> b.children(aged(30))));
            assertTrue(q.age() >= 46, "persona too young for a child of 30: " + q.birthDate());
        }
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
        refuses("children", b -> b.birthYear(YEAR - 17).children(aged(20)));
        refuses("children", b -> b.children(aged(-1)));
        refuses("siblings", b -> b.siblings(List.of(new SiblingAnswer(-1, null, null))));
        refuses("household", b -> b.relationship(Relationship.MARRIED).household(1));
        refuses("birthYear", b -> b.birthYear(YEAR - 15));
    }

    @Test
    void openFieldsRespectTheGivenOnes() {
        for (long n = 0; n < 300; n++) {
            Persona p = derive(n, with(b -> b.relationship(Relationship.MARRIED)));
            assertTrue(p.household() >= 2, "married persona living alone");
            assertTrue(p.partnerAtHome());
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
        Persona parent = derive(4, with(b -> b.birthYear(YEAR - 38).children(aged(2, 9))));
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
    void anOpenBackgroundIsTheCountry() {
        assertEquals("NL", derive(1, open()).background());
        assertEquals("TR", derive(1, with(b -> b.background("tr"))).background());
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
    void givenAnswerNeverMovesADrawnOne() throws ReflectiveOperationException {
        for (long n = 0; n < 50; n++) {
            Persona a = derive(n, open());
            Persona bank = derive(n, with(x -> x.bank(a.bank().equals("ING") ? "Knab" : "ING")));
            for (RecordComponent c : Persona.class.getRecordComponents()) {
                if (!c.getName().equals("bank")) {
                    assertEquals(c.getAccessor().invoke(a), c.getAccessor().invoke(bank), c.getName() + " moved, seed " + n);
                }
            }
            Persona b = derive(n, with(x -> x.gender(a.gender() == Gender.FEMALE ? Gender.MALE : Gender.FEMALE)));
            assertEquals(a.birthDate(), b.birthDate());
            assertEquals(a.pets(), b.pets());
            assertEquals(a.circle(), b.circle());
            assertEquals(a.texting(), b.texting());
            assertEquals(a.friendGroups(), b.friendGroups());
        }
    }

    @Test
    void sameSeedSamePersona() {
        assertEquals(derive(7, open()), derive(7, open()));
        assertEquals(derive(7, Profiles.full()), derive(7, Profiles.full()));
    }
}
