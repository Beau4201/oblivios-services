// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Period;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Sender;
import org.oblivios.decoy.content.country.SenderKind;
import org.oblivios.decoy.content.data.CityRef;
import org.oblivios.decoy.content.data.DataSet;
import org.oblivios.decoy.content.data.NameData;
import org.oblivios.decoy.content.data.PlaceData;
import org.oblivios.decoy.content.data.Spreads;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.persona.ActivityAnswer;
import org.oblivios.decoy.content.persona.ChildAnswer;
import org.oblivios.decoy.content.persona.ChildAnswer.Custody;
import org.oblivios.decoy.content.persona.ParentAnswer;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.persona.PersonaProfile.ActivityKind;
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
import org.oblivios.decoy.content.persona.PetAnswer;
import org.oblivios.decoy.content.persona.SiblingAnswer;
import org.oblivios.decoy.content.persona.TripAnswer;
import org.oblivios.decoy.content.persona.WorkPattern;
import org.oblivios.decoy.content.style.StyleVector;
import org.oblivios.decoy.content.style.Styles;

/**
 * The persona of one decoy: the owner's profile with every open answer drawn; no field is {@code null}, and a name that
 * is empty here is drawn by the world. Each open answer is drawn from its own named stream after the given answers are
 * fixed, so an answer the owner gives never moves a drawn one that does not depend on it.
 */
public record Persona(PersonaInputs inputs, Gender gender, LocalDate birthDate, String nationality, List<String> languages,
        String mixLanguage, String background, JobKind job, String jobField, WorkPattern work, Relationship relationship,
        boolean partnerAtHome, List<ChildAnswer> children, int household, Housing housing, List<PetAnswer> pets,
        Transport transport, List<ParentAnswer> parents, boolean parentsTogether, List<SiblingAnswer> siblings,
        Set<FriendGroup> friendGroups, List<ActivityAnswer> activities, CircleSize circle, Set<String> interests,
        Set<String> holidays, String bank, String operator, String supermarket, Texting texting, boolean voiceNotes,
        StyleVector style, List<TripAnswer> trips, LocalDate today) {

    private static final int MIN_AGE = 16;
    private static final int MAX_AGE = 100;
    private static final int MAX_DRAWN_AGE = 70;

    public Persona {
        languages = List.copyOf(languages);
        children = List.copyOf(children);
        pets = List.copyOf(pets);
        parents = List.copyOf(parents);
        siblings = List.copyOf(siblings);
        friendGroups = Set.copyOf(friendGroups);
        activities = List.copyOf(activities);
        interests = Set.copyOf(interests);
        holidays = Set.copyOf(holidays);
        trips = List.copyOf(trips);
    }

    /** The language of the persona's own texts: the first of its languages. */
    public String language() {
        return languages.get(0);
    }

    public int birthYear() {
        return birthDate.getYear();
    }

    /** The age on {@link #today}. */
    public int age() {
        return age(today);
    }

    /** The age on {@code on}: a year older from each birthday. */
    public int age(LocalDate on) {
        return Period.between(birthDate, on).getYears();
    }

    /** The ages of the children, in the order of {@link #children}. */
    public List<Integer> childAges() {
        return children.stream().map(ChildAnswer::age).toList();
    }

    public boolean parentsAlive() {
        return parents.stream().anyMatch(ParentAnswer::alive);
    }

    /**
     * Throws {@link IllegalArgumentException} whose message starts with the field when the profile contradicts itself or
     * names what the country pack does not hold, and {@code no language data for <tag>} when the persona's language has
     * no data. An unknown city is refused with the nearest names, never replaced.
     */
    public static Persona derive(Draws d, PersonaProfile p, LocalDate today, CountryPack pack, DataSet data) {
        PersonaInputs in = p.inputs();

        List<String> languages = p.languages();
        if (languages == null) {
            languages = List.of(LanguagePack.of(in.locale().toLanguageTag()).tag());
        } else if (languages.isEmpty()) {
            throw new IllegalArgumentException("languages: a given list must name at least one language");
        }
        LanguagePack.of(languages.get(0)); // refuses a language without data, never replaces it
        CityRef city = PlaceData.require(pack.code(), in.city());
        data.places(); // records the version of the places the city was read from
        check(p, today, pack);
        Spreads spreads = data.spreads();

        Gender gender = p.gender() != null ? p.gender() : drawGender(d.stream("persona.gender"));
        int birthYear = p.birthYear() != null ? p.birthYear()
                : drawBirthYear(d.stream("persona.birthYear"), p, today.getYear(), spreads);
        LocalDate birthDate = drawBirthDate(d.stream("persona.birthDate"), p, birthYear, today);
        int age = Period.between(birthDate, today).getYears();
        JobKind job = p.job() != null ? p.job()
                : pick(d.stream("persona.job"), spreads.jobWeights(age, gender), JobKind.values());
        if (p.job() == null && p.work() != null && !works(job)) {
            job = JobKind.OFFICE; // a given work pattern says that the persona works or studies
        }
        WorkPattern work = work(d, p, job);
        Relationship rel = p.relationship() != null ? p.relationship()
                : drawRelationship(d.stream("persona.relationship"), spreads.relationshipWeights(age, gender), p);
        boolean couple = rel == Relationship.PARTNER || rel == Relationship.MARRIED;
        if (Boolean.TRUE.equals(p.partnerAtHome()) && !couple) {
            throw new IllegalArgumentException("partnerAtHome: no partner to share the home, relationship " + rel);
        }
        boolean partnerAtHome = p.partnerAtHome() != null ? p.partnerAtHome()
                : rel == Relationship.MARRIED || (rel == Relationship.PARTNER && (p.household() == null || p.household() > 1)
                        && d.stream("persona.partnerAtHome").nextInt(100) < spreads.partnerAtHomePercent(age, gender));
        List<ChildAnswer> children = children(d, p, age, spreads.childPercent(age, gender));
        Housing housing = p.housing() != null ? p.housing()
                : drawHousing(d.stream("persona.housing"), spreads.housingWeights(age, gender), job, p);
        List<ParentAnswer> parents = parents(d, p, age, housing);
        boolean anyParent = parents.stream().anyMatch(ParentAnswer::alive);
        boolean bothParents = parents.size() == 2 && parents.stream().allMatch(ParentAnswer::alive);
        boolean parentsTogether = p.parentsTogether() != null ? p.parentsTogether()
                : bothParents && d.stream("persona.parentsTogether").nextInt(100) < 70;
        int household = p.household() != null ? p.household()
                : drawHousehold(d.stream("persona.household"), partnerAtHome, children, housing);

        if (partnerAtHome && household < 2) {
            throw new IllegalArgumentException("household: a persona whose partner is at home does not live alone, household " + household);
        }
        if ((housing == Housing.WITH_PARENTS || housing == Housing.HOUSE_SHARE) && household < 2) {
            throw new IllegalArgumentException("household: housing " + housing + " with a household of " + household);
        }
        if (housing == Housing.WITH_PARENTS && !anyParent) {
            throw new IllegalArgumentException("housing: living with parents who are not alive");
        }
        if (parentsTogether && !bothParents) {
            throw new IllegalArgumentException("parentsTogether: parents together who are not both alive");
        }

        String nationality = p.nationality() != null ? p.nationality().toUpperCase(Locale.ROOT) : in.countryCode().toUpperCase(Locale.ROOT);
        // an open background is one with name data, as the people of this age and sex in the city have them: a country
        // counts only as a whole package (ADR-348), so no open answer leads to a refusal
        String background = p.background() != null ? p.background().toUpperCase(Locale.ROOT)
                : data.population().background(city.municipality(), age, gender, NameData.backgrounds(),
                        d.stream("persona.background"));
        String mix = p.mixLanguage() != null ? p.mixLanguage() : "";
        String jobField = p.jobField() != null ? p.jobField() : "";
        List<PetAnswer> pets = p.pets() != null
                ? p.pets().stream().map(a -> new PetAnswer(a.kind(), Objects.requireNonNullElse(a.name(), ""))).toList()
                : drawPets(d.stream("persona.pets"), spreads);
        Transport transport = p.transport() != null ? p.transport()
                : pick(d.stream("persona.transport"), spreads.transportWeights(age), Transport.values());
        List<SiblingAnswer> siblings = siblings(d, p, age);
        Set<FriendGroup> friends = p.friendGroups() != null ? p.friendGroups() : drawFriendGroups(d.stream("persona.friendGroups"), age, job);
        List<ActivityAnswer> activities = p.activities() != null ? p.activities() : drawActivities(d, friends);
        CircleSize circle = p.circle() != null ? p.circle() : pick(d.stream("persona.circle"), new int[] {30, 50, 20}, CircleSize.values());
        Set<String> interests = p.interests() != null ? p.interests() : Set.of();
        Set<String> kept = data.holidays().kept(background);
        Set<String> holidays = p.holidays() != null ? p.holidays() : kept;
        String bank = p.bank() != null ? p.bank() : oneOf(d.stream("persona.bank"), names(pack.senders(SenderKind.BANK)));
        String operator = p.operator() != null ? p.operator()
                : oneOf(d.stream("persona.operator"), names(pack.senders(SenderKind.OPERATOR)));
        String supermarket = p.supermarket() != null ? p.supermarket() : oneOf(d.stream("persona.supermarket"), pack.supermarkets());
        Texting texting = p.texting() != null ? p.texting()
                : pick(d.stream("persona.texting"), age < 30 ? new int[] {15, 45, 40} : new int[] {30, 50, 20}, Texting.values());
        boolean voice = p.voiceNotes() != null ? p.voiceNotes() : d.stream("persona.voiceNotes").nextInt(100) < (age < 35 ? 60 : 35);
        StyleVector style = p.style() != null ? p.style() : Styles.ageBand(age, d.stream("persona.style"));
        List<TripAnswer> trips = p.trips() != null ? p.trips() : List.of(); // no trip is invented

        return new Persona(in, gender, birthDate, nationality, languages, mix, background, job, jobField, work, rel, partnerAtHome,
                children, household, housing, pets, transport, parents, parentsTogether, siblings, friends, activities, circle,
                interests, holidays, bank, operator, supermarket, texting, voice, style, trips, today);
    }

    /** The refusals that need no drawn value. */
    private static void check(PersonaProfile p, LocalDate today, CountryPack pack) {
        if (p.birthYear() != null) {
            int age = today.getYear() - p.birthYear();
            if (age < MIN_AGE || age > MAX_AGE) {
                throw new IllegalArgumentException("birthYear: an age of " + age + " is outside 16 to 100");
            }
        }
        if (p.children() != null) {
            for (ChildAnswer c : p.children()) {
                if (c.age() != null && c.age() < 0) {
                    throw new IllegalArgumentException("children: a child age is negative");
                }
                if (c.custody() == Custody.SHARED && (c.daysWithPersona() == null || c.daysWithPersona().isEmpty())) {
                    throw new IllegalArgumentException("children: a child in shared custody needs the days with the persona");
                }
            }
        }
        if (p.siblings() != null && p.siblings().stream().anyMatch(s -> s.age() != null && s.age() < 0)) {
            throw new IllegalArgumentException("siblings: a sibling age is negative");
        }
        if (p.household() != null && p.household() < 1) {
            throw new IllegalArgumentException("household: at least the persona lives in it");
        }
        if (p.pets() != null && p.pets().stream().anyMatch(a -> a.kind() == null)) {
            throw new IllegalArgumentException("pets: a pet needs a kind");
        }
        if (p.parents() != null && p.parents().size() > 2) {
            throw new IllegalArgumentException("parents: at most two");
        }
        if (p.activities() != null && p.activities().stream()
                .anyMatch(a -> a.kind() == null || a.day() == null || a.start() == null || a.minutes() <= 0)) {
            throw new IllegalArgumentException("activities: an activity needs a kind, a day, a start and a duration above 0");
        }
        if (p.trips() != null) {
            YearMonth last = YearMonth.from(today);
            for (TripAnswer t : p.trips()) {
                if (t.month() == null || t.month().isBefore(last.minusMonths(12)) || t.month().isAfter(last)) {
                    throw new IllegalArgumentException("trips: a trip lies in the twelve months before today, not in " + t.month());
                }
                if (t.days() < 1 || t.country() == null || t.country().isBlank() || t.mode() == null) {
                    throw new IllegalArgumentException("trips: a trip needs at least 1 day, a country and a means of travel");
                }
            }
        }
        listed("bank", p.bank(), names(pack.senders(SenderKind.BANK)), pack);
        listed("operator", p.operator(), names(pack.senders(SenderKind.OPERATOR)), pack);
        listed("supermarket", p.supermarket(), pack.supermarkets(), pack);
    }

    private static void listed(String field, String given, List<String> known, CountryPack pack) {
        if (given != null && !known.contains(given)) {
            throw new IllegalArgumentException(field + ": '" + given + "' is not one of country " + pack.code());
        }
    }

    private static List<String> names(List<Sender> senders) {
        return senders.stream().map(Sender::name).toList();
    }

    /** Uniform over the list; empty for a pack that holds none. */
    private static String oneOf(RandomGenerator r, List<String> values) {
        return values.isEmpty() ? "" : values.get(r.nextInt(values.size()));
    }

    /**
     * A day of the birth year, such that the persona is at least 16, and 16 years older than every given child, today.
     */
    private static LocalDate drawBirthDate(RandomGenerator r, PersonaProfile p, int birthYear, LocalDate today) {
        int minAge = MIN_AGE;
        if (p.children() != null) {
            for (ChildAnswer c : p.children()) {
                if (c.age() != null) {
                    refuseTooOld(c.age(), today.getYear() - birthYear);
                    minAge = Math.max(minAge, c.age() + MIN_AGE);
                }
            }
        }
        LocalDate first = LocalDate.of(birthYear, 1, 1);
        LocalDate last = LocalDate.of(birthYear, 12, 31);
        LocalDate latest = today.minusYears(minAge);
        if (latest.isBefore(last)) {
            last = latest;
        }
        return first.plusDays(r.nextInt((int) ChronoUnit.DAYS.between(first, last) + 1));
    }

    private static void refuseTooOld(int child, int age) {
        if (child > age - MIN_AGE) {
            throw new IllegalArgumentException("children: a child of " + child + " for a persona of " + age
                    + " (a parent is at least 16 years older)");
        }
    }

    private static boolean works(JobKind job) {
        return job != JobKind.RETIRED && job != JobKind.NONE && job != JobKind.JOB_SEEKER;
    }

    /** The given pattern, or one for the job; {@code [assumption]} until the routine has sourced spreads. */
    private static WorkPattern work(Draws d, PersonaProfile p, JobKind job) {
        WorkPattern given = p.work();
        if (!works(job)) {
            if (given != null) {
                throw new IllegalArgumentException("work: a persona that is " + job + " has no work pattern");
            }
            return WorkPattern.NONE;
        }
        if (given != null) {
            if (given.days() == null || given.days().isEmpty()) {
                throw new IllegalArgumentException("work: a work pattern needs at least one day");
            }
            if (given.start() == null || given.end() == null || given.start().equals(given.end())) {
                throw new IllegalArgumentException("work: a work pattern needs a start and a different end");
            }
            return given;
        }
        Set<DayOfWeek> week = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        return switch (job) {
            case SHIFT -> shift(d.stream("persona.work"));
            case SELF_EMPLOYED -> new WorkPattern(week, LocalTime.of(9, 0), LocalTime.of(17, 30), false);
            case STUDENT -> new WorkPattern(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), LocalTime.of(9, 0),
                    LocalTime.of(16, 0), false);
            case PUPIL -> new WorkPattern(week, LocalTime.of(8, 30), LocalTime.of(15, 0), false);
            case CARER -> new WorkPattern(week, LocalTime.of(9, 0), LocalTime.of(15, 0), false);
            default -> new WorkPattern(week, LocalTime.of(8, 30), LocalTime.of(17, 0), false);
        };
    }

    /** Five days in a row from any day, early, late or night; half of the shift workers rotate. */
    private static WorkPattern shift(RandomGenerator r) {
        DayOfWeek first = DayOfWeek.of(1 + r.nextInt(7));
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (int i = 0; i < 5; i++) {
            days.add(first.plus(i));
        }
        LocalTime start = new LocalTime[] {LocalTime.of(7, 0), LocalTime.of(14, 0), LocalTime.of(22, 0)}[r.nextInt(3)];
        return new WorkPattern(days, start, start.plusHours(8).plusMinutes(30), r.nextBoolean());
    }

    private static List<ChildAnswer> children(Draws d, PersonaProfile p, int age, int chance) {
        List<ChildAnswer> out = new ArrayList<>();
        if (p.children() == null) {
            RandomGenerator genders = d.stream("persona.childGenders");
            for (int c : drawChildAges(d.stream("persona.childAges"), age, chance)) {
                out.add(new ChildAnswer(c, drawGender(genders), "", Custody.HOME, Set.of()));
            }
            return out;
        }
        for (int i = 0; i < p.children().size(); i++) {
            ChildAnswer a = p.children().get(i);
            String item = "persona.children." + i;
            int years = a.age() != null ? a.age()
                    : d.stream(item + ".age").nextInt(Math.max(0, Math.min(age - 18, 45)) + 1);
            refuseTooOld(years, age);
            out.add(new ChildAnswer(years, a.gender() != null ? a.gender() : drawGender(d.stream(item + ".gender")),
                    Objects.requireNonNullElse(a.givenName(), ""), Objects.requireNonNullElse(a.custody(), Custody.HOME),
                    Objects.requireNonNullElse(a.daysWithPersona(), Set.of())));
        }
        return out;
    }

    /** An open list is a mother and a father; whether they live is one draw, as old as the persona. */
    private static List<ParentAnswer> parents(Draws d, PersonaProfile p, int age, Housing housing) {
        List<ParentAnswer> given = p.parents() != null ? p.parents()
                : List.of(new ParentAnswer(Gender.FEMALE, null, null), new ParentAnswer(Gender.MALE, null, null));
        boolean alive = housing == Housing.WITH_PARENTS || Boolean.TRUE.equals(p.parentsTogether())
                || drawParentsAlive(d.stream("persona.parentsAlive"), age);
        List<ParentAnswer> out = new ArrayList<>();
        for (int i = 0; i < given.size(); i++) {
            ParentAnswer a = given.get(i);
            Gender gender = a.gender() != null ? a.gender() : drawGender(d.stream("persona.parents." + i + ".gender"));
            out.add(new ParentAnswer(gender, a.alive() != null ? a.alive() : alive, Objects.requireNonNullElse(a.calledAs(), "")));
        }
        return out;
    }

    private static List<SiblingAnswer> siblings(Draws d, PersonaProfile p, int age) {
        List<SiblingAnswer> out = new ArrayList<>();
        if (p.siblings() == null) {
            RandomGenerator genders = d.stream("persona.siblingGenders");
            for (int years : drawSiblingAges(d.stream("persona.siblingAges"), age)) {
                out.add(new SiblingAnswer(years, drawGender(genders), ""));
            }
            return out;
        }
        for (int i = 0; i < p.siblings().size(); i++) {
            SiblingAnswer a = p.siblings().get(i);
            String item = "persona.siblings." + i;
            out.add(new SiblingAnswer(a.age() != null ? a.age() : drawSiblingAge(d.stream(item + ".age"), age),
                    a.gender() != null ? a.gender() : drawGender(d.stream(item + ".gender")),
                    Objects.requireNonNullElse(a.calledAs(), "")));
        }
        return out;
    }

    /** One weekly activity for each friend group that meets for one; days and hours are {@code [assumption]}. */
    private static List<ActivityAnswer> drawActivities(Draws d, Set<FriendGroup> groups) {
        List<ActivityAnswer> out = new ArrayList<>();
        for (FriendGroup g : FriendGroup.values()) {
            ActivityKind kind = switch (g) {
                case SPORT -> ActivityKind.TEAM_SPORT;
                case MUSIC -> ActivityKind.MUSIC;
                case FAITH -> ActivityKind.FAITH;
                case GAMING -> ActivityKind.GAMING;
                default -> null;
            };
            if (kind == null || !groups.contains(g)) {
                continue;
            }
            RandomGenerator r = d.stream("persona.activities." + g);
            out.add(kind == ActivityKind.FAITH ? new ActivityAnswer(kind, DayOfWeek.SUNDAY, LocalTime.of(10, 0), 90, g)
                    : new ActivityAnswer(kind, DayOfWeek.of(1 + r.nextInt(4)), LocalTime.of(19 + r.nextInt(2), 0), 90, g));
        }
        return out;
    }

    private static int drawBirthYear(RandomGenerator r, PersonaProfile p, int currentYear, Spreads spreads) {
        int lo = MIN_AGE;
        int hi = MAX_DRAWN_AGE;
        if (p.job() == JobKind.STUDENT) {
            hi = 35;
        } else if (p.job() == JobKind.PUPIL) {
            hi = 19;
        } else if (p.job() == JobKind.RETIRED) {
            lo = 58;
        }
        if (p.children() != null) {
            for (ChildAnswer c : p.children()) {
                if (c.age() != null) {
                    lo = Math.max(lo, c.age() + MIN_AGE);
                }
            }
        }
        if (p.job() == null && lo > hi - 10) {
            hi = Math.min(MAX_AGE, Math.max(hi, lo + 10)); // an older child widens the open range up to 100
        }
        if (lo > hi) {
            throw new IllegalArgumentException("children: the children need a persona of at least " + lo
                    + ", the job " + p.job() + " allows at most " + hi);
        }
        return currentYear - spreads.age(lo, hi, r);
    }

    private static Relationship drawRelationship(RandomGenerator r, int[] w, PersonaProfile p) {
        if (p.household() != null && p.household() < 2 && !Boolean.FALSE.equals(p.partnerAtHome())) {
            w[2] = 0; // a married persona lives with the partner
        }
        if (Boolean.TRUE.equals(p.partnerAtHome())) {
            w[0] = 0;
            w[3] = 0;
            if (w[1] + w[2] == 0) {
                w[1] = 100;
            }
        }
        return pick(r, w, Relationship.values());
    }

    private static List<Integer> drawChildAges(RandomGenerator r, int age, int chance) {
        List<Integer> out = new ArrayList<>();
        if (age < 18 || r.nextInt(100) >= chance) {
            return out;
        }
        int oldest = Math.min(age - 18, 45);
        int count = 1 + r.nextInt(3);
        for (int i = 0; i < count; i++) {
            out.add(r.nextInt(oldest + 1));
        }
        out.sort(null);
        return out;
    }

    private static Housing drawHousing(RandomGenerator r, int[] w, JobKind job, PersonaProfile p) {
        if (job == JobKind.PUPIL) {
            Arrays.fill(w, 0);
            w[Housing.WITH_PARENTS.ordinal()] = 100;
        }
        if (p.household() != null && p.household() < 2) {
            w[2] = 0;
            w[3] = 0;
        }
        if (p.parents() != null && p.parents().stream().allMatch(a -> Boolean.FALSE.equals(a.alive()))) {
            w[2] = 0;
        }
        if (w[0] + w[1] + w[2] + w[3] == 0) {
            w[0] = 100;
        }
        return pick(r, w, Housing.values());
    }

    private static boolean drawParentsAlive(RandomGenerator r, int age) {
        int chance = age < 40 ? 97 : age < 60 ? 80 : 35;
        return r.nextInt(100) < chance;
    }

    private static int drawHousehold(RandomGenerator r, boolean partnerAtHome, List<ChildAnswer> children, Housing housing) {
        int n = partnerAtHome ? 2 : 1;
        for (ChildAnswer c : children) {
            if (c.custody() != Custody.ELSEWHERE && (c.age() < 18 || (c.age() < 25 && r.nextBoolean()))) {
                n++;
            }
        }
        if (housing == Housing.WITH_PARENTS) {
            n += 2;
        } else if (housing == Housing.HOUSE_SHARE) {
            n += 2 + r.nextInt(3);
        }
        return n;
    }

    private static Gender drawGender(RandomGenerator r) {
        return pick(r, new int[] {50, 50}, Gender.values());
    }

    private static List<PetAnswer> drawPets(RandomGenerator r, Spreads spreads) {
        List<PetAnswer> out = new ArrayList<>();
        for (Pet pet : Pet.values()) {
            if (r.nextInt(100) < spreads.petPercent(pet)) {
                out.add(new PetAnswer(pet, ""));
            }
        }
        return out;
    }

    private static List<Integer> drawSiblingAges(RandomGenerator r, int age) {
        int count = pick(r, new int[] {20, 40, 30, 10}, new Integer[] {0, 1, 2, 3});
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(drawSiblingAge(r, age));
        }
        return out;
    }

    private static int drawSiblingAge(RandomGenerator r, int age) {
        return Math.max(1, age + r.nextInt(17) - 8);
    }

    private static Set<FriendGroup> drawFriendGroups(RandomGenerator r, int age, JobKind job) {
        Set<FriendGroup> out = EnumSet.noneOf(FriendGroup.class);
        if (age < 30 || job == JobKind.PUPIL || r.nextInt(100) < 40) {
            out.add(FriendGroup.SCHOOL);
        }
        if (job == JobKind.STUDENT || (age >= 22 && r.nextInt(100) < 40)) {
            out.add(FriendGroup.STUDY);
        }
        if ((job == JobKind.OFFICE || job == JobKind.SHIFT || job == JobKind.SELF_EMPLOYED) && r.nextInt(100) < 70) {
            out.add(FriendGroup.WORK);
        }
        if (r.nextInt(100) < 40) {
            out.add(FriendGroup.SPORT);
        }
        if (age < 40 && r.nextInt(100) < 20) {
            out.add(FriendGroup.GAMING);
        }
        if (r.nextInt(100) < 15) {
            out.add(FriendGroup.MUSIC);
        }
        if (r.nextInt(100) < 10) {
            out.add(FriendGroup.FAITH);
        }
        if (r.nextInt(100) < 20 || out.isEmpty()) {
            out.add(FriendGroup.NEIGHBOURHOOD);
        }
        return out;
    }

    private static <T> T pick(RandomGenerator r, int[] weights, T[] values) {
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        int roll = r.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0) {
                return values[i];
            }
        }
        throw new IllegalStateException("unreachable: the roll is below the total");
    }

    /** What a conversation may presume about this persona. */
    public Set<Fact> facts() {
        Set<Fact> f = EnumSet.noneOf(Fact.class);
        for (PetAnswer pet : pets) {
            f.add(switch (pet.kind()) {
                case DOG -> Fact.DOG;
                case CAT -> Fact.CAT;
                case OTHER -> Fact.OTHER_PET;
            });
        }
        if (transport == Transport.CAR) {
            f.add(Fact.CAR);
        } else if (transport == Transport.BIKE) {
            f.add(Fact.BIKE);
        }
        if (!children.isEmpty()) {
            f.add(Fact.CHILDREN);
        }
        for (int c : childAges()) {
            if (c < 4) {
                f.add(Fact.YOUNG_CHILDREN);
            } else if (c <= 12) {
                f.add(Fact.SCHOOL_CHILDREN);
            } else if (c <= 19) {
                f.add(Fact.TEEN_CHILDREN);
            }
        }
        boolean minorChild = children.stream().anyMatch(c -> c.age() < 18 && c.custody() != Custody.ELSEWHERE);
        if (minorChild && (relationship == Relationship.SINGLE || relationship == Relationship.DIVORCED)) {
            f.add(Fact.SINGLE_PARENT);
        }
        switch (job) {
            case STUDENT -> f.add(Fact.STUDENT);
            case PUPIL -> f.add(Fact.PUPIL);
            case SHIFT -> f.add(Fact.SHIFT_WORK);
            case SELF_EMPLOYED -> f.add(Fact.SELF_EMPLOYED);
            case RETIRED -> f.add(Fact.RETIRED);
            case JOB_SEEKER -> f.add(Fact.JOB_SEEKER);
            case CARER -> f.add(Fact.CARER);
            default -> { }
        }
        if (job == JobKind.OFFICE || job == JobKind.SHIFT || job == JobKind.SELF_EMPLOYED) {
            f.add(Fact.WORKS);
        }
        switch (relationship) {
            case PARTNER, MARRIED -> f.add(Fact.PARTNER);
            case SINGLE -> f.add(Fact.SINGLE);
            case DIVORCED -> f.add(Fact.DIVORCED);
        }
        if (household == 1) {
            f.add(Fact.LIVES_ALONE);
        }
        switch (housing) {
            case RENT -> f.add(Fact.RENTS);
            case OWN -> f.add(Fact.OWNS_HOME);
            case HOUSE_SHARE -> f.add(Fact.HOUSE_SHARE);
            case WITH_PARENTS -> f.add(Fact.WITH_PARENTS);
        }
        if (parentsAlive()) {
            f.add(Fact.PARENTS_ALIVE);
        }
        if (friendGroups.contains(FriendGroup.SPORT)) {
            f.add(Fact.SPORT);
        }
        if (friendGroups.contains(FriendGroup.MUSIC)) {
            f.add(Fact.MUSIC);
        }
        if (friendGroups.contains(FriendGroup.GAMING)) {
            f.add(Fact.GAMING);
        }
        if (friendGroups.contains(FriendGroup.FAITH)) {
            f.add(Fact.FAITH);
        }
        if (isExpat()) {
            f.add(Fact.EXPAT);
        }
        return f;
    }

    private boolean isExpat() {
        return !mixLanguage.isEmpty() || !nationality.equalsIgnoreCase(inputs.countryCode());
    }

    /** The life stages of this persona {@link #today}. A worker under 25 is a young worker; from 25 on the job gives the stage. */
    public Set<Stage> stages() {
        Set<Fact> f = facts();
        Set<Stage> s = EnumSet.noneOf(Stage.class);
        int age = age();
        boolean worker = job == JobKind.OFFICE || job == JobKind.SHIFT || job == JobKind.SELF_EMPLOYED;
        if (worker && age < 25) {
            s.add(Stage.YOUNG_WORKER);
        } else if (job == JobKind.OFFICE) {
            s.add(Stage.OFFICE_WORKER);
        } else if (job == JobKind.SHIFT) {
            s.add(Stage.SHIFT_WORKER);
        } else if (job == JobKind.SELF_EMPLOYED) {
            s.add(Stage.SELF_EMPLOYED);
        }
        switch (job) {
            case PUPIL -> s.add(Stage.PUPIL);
            case STUDENT -> s.add(Stage.STUDENT);
            case RETIRED -> s.add(Stage.RETIREE);
            case CARER -> s.add(Stage.CARER);
            case JOB_SEEKER -> s.add(Stage.JOB_SEEKER);
            default -> { }
        }
        if (f.contains(Fact.YOUNG_CHILDREN)) {
            s.add(Stage.PARENT_SMALL);
        }
        if (f.contains(Fact.SCHOOL_CHILDREN)) {
            s.add(Stage.PARENT_SCHOOL);
        }
        if (f.contains(Fact.TEEN_CHILDREN)) {
            s.add(Stage.PARENT_TEEN);
        }
        if (f.contains(Fact.SINGLE_PARENT)) {
            s.add(Stage.SINGLE_PARENT);
        }
        if (f.contains(Fact.EXPAT)) {
            s.add(Stage.EXPAT);
        }
        return s;
    }
}
