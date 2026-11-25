// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.persona.PersonaProfile;
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
import org.oblivios.decoy.content.style.Styles;

/**
 * The persona of one decoy: the owner's profile with every open field drawn. Each open field is drawn from its own named
 * stream of the seed after the given fields are fixed, so a field the owner gives never moves a drawn one.
 */
public record Persona(PersonaInputs inputs, Gender gender, int birthYear, String nationality, List<String> languages,
        String mixLanguage, JobKind job, String jobField, Relationship relationship, List<Integer> childAges, int household,
        Housing housing, Set<Pet> pets, Transport transport, boolean parentsAlive, boolean parentsTogether,
        List<Integer> siblingAges, Set<FriendGroup> friendGroups, CircleSize circle, Set<String> interests, Set<String> holidays,
        Texting texting, boolean voiceNotes, StyleVector style, int year) {

    private static final int MIN_AGE = 16;
    private static final int MAX_DRAWN_AGE = 70;

    public Persona {
        languages = List.copyOf(languages);
        childAges = List.copyOf(childAges);
        pets = Set.copyOf(pets);
        siblingAges = List.copyOf(siblingAges);
        friendGroups = Set.copyOf(friendGroups);
        interests = Set.copyOf(interests);
        holidays = Set.copyOf(holidays);
    }

    /** The language of the persona's own texts: the first of its languages. */
    public String language() {
        return languages.get(0);
    }

    /** The age the persona reaches in {@link #year}, the year its ages (children, siblings) refer to. */
    public int age() {
        return year - birthYear;
    }

    /**
     * Throws {@link IllegalArgumentException} whose message names the field when the profile contradicts itself, and
     * {@code no language data for <tag>} when the persona's language has no data.
     */
    public static Persona derive(Seed seed, PersonaProfile p, int currentYear) {
        PersonaInputs in = p.inputs();

        List<String> languages = p.languages();
        if (languages == null) {
            languages = List.of(LanguagePack.of(in.locale().toLanguageTag()).tag());
        } else if (languages.isEmpty()) {
            throw new IllegalArgumentException("languages: a given list must name at least one language");
        }
        LanguagePack.of(languages.get(0)); // refuses a language without data, never replaces it

        if (p.birthYear() != null) {
            int age = currentYear - p.birthYear();
            if (age < MIN_AGE || age > 100) {
                throw new IllegalArgumentException("birthYear: an age of " + age + " is outside 16 to 100");
            }
        }
        if (p.childAges() != null && p.childAges().stream().anyMatch(c -> c < 0)) {
            throw new IllegalArgumentException("childAges: a child age is negative");
        }
        if (p.siblingAges() != null && p.siblingAges().stream().anyMatch(c -> c < 0)) {
            throw new IllegalArgumentException("siblingAges: a sibling age is negative");
        }
        if (p.household() != null && p.household() < 1) {
            throw new IllegalArgumentException("household: at least the persona lives in it");
        }

        int birthYear = p.birthYear() != null ? p.birthYear() : drawBirthYear(seed.stream("persona.birthYear"), p, currentYear);
        int age = currentYear - birthYear;
        JobKind job = p.job() != null ? p.job() : drawJob(seed.stream("persona.job"), age);
        Relationship rel = p.relationship() != null ? p.relationship() : drawRelationship(seed.stream("persona.relationship"), age, p.household());
        List<Integer> children = p.childAges() != null ? p.childAges() : drawChildren(seed.stream("persona.childAges"), age);
        Housing housing = p.housing() != null ? p.housing() : drawHousing(seed.stream("persona.housing"), age, job, p);
        boolean parentsAlive = p.parentsAlive() != null ? p.parentsAlive()
                : housing == Housing.WITH_PARENTS || drawParentsAlive(seed.stream("persona.parentsAlive"), age);
        boolean parentsTogether = p.parentsTogether() != null ? p.parentsTogether()
                : parentsAlive && seed.stream("persona.parentsTogether").nextInt(100) < 70;
        int household = p.household() != null ? p.household()
                : drawHousehold(seed.stream("persona.household"), rel, children, housing);

        for (int c : children) {
            if (c > age - MIN_AGE) {
                throw new IllegalArgumentException("childAges: a child of " + c + " for a persona of " + age
                        + " (a parent is at least 16 years older)");
            }
        }
        if (rel == Relationship.MARRIED && household < 2) {
            throw new IllegalArgumentException("household: a married persona lives with the partner, household " + household);
        }
        if ((housing == Housing.WITH_PARENTS || housing == Housing.HOUSE_SHARE) && household < 2) {
            throw new IllegalArgumentException("household: housing " + housing + " with a household of " + household);
        }
        if (housing == Housing.WITH_PARENTS && !parentsAlive) {
            throw new IllegalArgumentException("housing: living with parents who are not alive");
        }
        if (parentsTogether && !parentsAlive) {
            throw new IllegalArgumentException("parentsTogether: parents together who are not alive");
        }

        Gender gender = p.gender() != null ? p.gender() : drawGender(seed.stream("persona.gender"));
        String nationality = p.nationality() != null ? p.nationality().toUpperCase(Locale.ROOT) : in.countryCode().toUpperCase(Locale.ROOT);
        String mix = p.mixLanguage() != null ? p.mixLanguage() : "";
        String jobField = p.jobField() != null ? p.jobField() : "";
        Set<Pet> pets = p.pets() != null ? p.pets() : drawPets(seed.stream("persona.pets"));
        Transport transport = p.transport() != null ? p.transport() : drawTransport(seed.stream("persona.transport"), age);
        List<Integer> siblings = p.siblingAges() != null ? p.siblingAges() : drawSiblings(seed.stream("persona.siblingAges"), age);
        Set<FriendGroup> friends = p.friendGroups() != null ? p.friendGroups() : drawFriendGroups(seed.stream("persona.friendGroups"), age, job);
        CircleSize circle = p.circle() != null ? p.circle() : pick(seed.stream("persona.circle"), new int[] {30, 50, 20}, CircleSize.values());
        Set<String> interests = p.interests() != null ? p.interests() : Set.of();
        Set<String> holidays = p.holidays() != null ? p.holidays() : Set.of();
        Texting texting = p.texting() != null ? p.texting()
                : pick(seed.stream("persona.texting"), age < 30 ? new int[] {15, 45, 40} : new int[] {30, 50, 20}, Texting.values());
        boolean voice = p.voiceNotes() != null ? p.voiceNotes() : seed.stream("persona.voiceNotes").nextInt(100) < (age < 35 ? 60 : 35);
        StyleVector style = p.style() != null ? p.style() : Styles.ageBand(age, seed.stream("persona.style"));

        return new Persona(in, gender, birthYear, nationality, languages, mix, job, jobField, rel, children, household, housing, pets,
                transport, parentsAlive, parentsTogether, siblings, friends, circle, interests, holidays, texting, voice, style, currentYear);
    }

    private static int drawBirthYear(RandomGenerator r, PersonaProfile p, int currentYear) {
        int lo = MIN_AGE;
        int hi = MAX_DRAWN_AGE;
        if (p.job() == JobKind.STUDENT) {
            hi = 35;
        } else if (p.job() == JobKind.PUPIL) {
            hi = 19;
        } else if (p.job() == JobKind.RETIRED) {
            lo = 58;
        }
        if (p.childAges() != null) {
            for (int c : p.childAges()) {
                lo = Math.max(lo, c + MIN_AGE);
            }
        }
        if (p.job() == null && lo > hi - 10) {
            hi = Math.min(100, Math.max(hi, lo + 10)); // an older child widens the open range up to 100
        }
        if (lo > hi) {
            throw new IllegalArgumentException("childAges: the children need a persona of at least " + lo
                    + ", the job " + p.job() + " allows at most " + hi);
        }
        return currentYear - (lo + r.nextInt(hi - lo + 1));
    }

    private static JobKind drawJob(RandomGenerator r, int age) {
        if (age < 19) {
            return r.nextInt(100) < 85 ? JobKind.PUPIL : JobKind.SHIFT;
        }
        if (age <= 24) {
            return pick(r, new int[] {50, 25, 20, 5}, new JobKind[] {JobKind.STUDENT, JobKind.SHIFT, JobKind.OFFICE, JobKind.JOB_SEEKER});
        }
        if (age >= 67) {
            return JobKind.RETIRED;
        }
        if (age >= 60) {
            return pick(r, new int[] {45, 30, 10, 10, 5},
                    new JobKind[] {JobKind.RETIRED, JobKind.OFFICE, JobKind.SHIFT, JobKind.SELF_EMPLOYED, JobKind.CARER});
        }
        int[] w = age <= 35 ? new int[] {48, 20, 10, 5, 5, 5, 4, 3} : new int[] {50, 20, 12, 0, 5, 5, 5, 3};
        return pick(r, w, new JobKind[] {JobKind.OFFICE, JobKind.SHIFT, JobKind.SELF_EMPLOYED, JobKind.STUDENT, JobKind.JOB_SEEKER,
                JobKind.CARER, JobKind.NONE, JobKind.OFFICE});
    }

    private static Relationship drawRelationship(RandomGenerator r, int age, Integer givenHousehold) {
        int[] w;
        if (age < 20) {
            w = new int[] {70, 30, 0, 0};
        } else if (age < 30) {
            w = new int[] {40, 45, 15, 0};
        } else if (age < 50) {
            w = new int[] {20, 25, 45, 10};
        } else {
            w = new int[] {15, 15, 50, 20};
        }
        if (givenHousehold != null && givenHousehold < 2) {
            w[2] = 0; // a married persona lives with the partner
        }
        return pick(r, w, Relationship.values());
    }

    private static List<Integer> drawChildren(RandomGenerator r, int age) {
        int chance = age < 22 ? 0 : age < 30 ? 25 : age < 45 ? 65 : 75;
        List<Integer> out = new ArrayList<>();
        if (r.nextInt(100) >= chance) {
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

    private static Housing drawHousing(RandomGenerator r, int age, JobKind job, PersonaProfile p) {
        int[] w; // RENT, OWN, WITH_PARENTS, HOUSE_SHARE
        if (job == JobKind.PUPIL) {
            w = new int[] {0, 0, 100, 0};
        } else if (age < 23 && job == JobKind.STUDENT) {
            w = new int[] {10, 0, 60, 30};
        } else if (age < 30) {
            w = new int[] {50, 15, 15, 20};
        } else {
            w = new int[] {45, 55, 0, 0};
        }
        if (p.household() != null && p.household() < 2) {
            w[2] = 0;
            w[3] = 0;
        }
        if (Boolean.FALSE.equals(p.parentsAlive())) {
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

    private static int drawHousehold(RandomGenerator r, Relationship rel, List<Integer> children, Housing housing) {
        int n = 1;
        if (rel == Relationship.MARRIED || (rel == Relationship.PARTNER && r.nextInt(100) < 60)) {
            n++;
        }
        for (int c : children) {
            if (c < 18 || (c < 25 && r.nextBoolean())) {
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
        return pick(r, new int[] {49, 49, 2}, Gender.values());
    }

    private static Set<Pet> drawPets(RandomGenerator r) {
        Set<Pet> out = EnumSet.noneOf(Pet.class);
        if (r.nextInt(100) < 20) {
            out.add(Pet.DOG);
        }
        if (r.nextInt(100) < 20) {
            out.add(Pet.CAT);
        }
        if (r.nextInt(100) < 8) {
            out.add(Pet.OTHER);
        }
        return out;
    }

    private static Transport drawTransport(RandomGenerator r, int age) {
        int[] w = age < 18 ? new int[] {0, 60, 40} : new int[] {50, 30, 20};
        return pick(r, w, Transport.values());
    }

    private static List<Integer> drawSiblings(RandomGenerator r, int age) {
        int count = pick(r, new int[] {20, 40, 30, 10}, new Integer[] {0, 1, 2, 3});
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(Math.max(1, age + r.nextInt(17) - 8));
        }
        return out;
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
        if (pets.contains(Pet.DOG)) {
            f.add(Fact.DOG);
        }
        if (pets.contains(Pet.CAT)) {
            f.add(Fact.CAT);
        }
        if (pets.contains(Pet.OTHER)) {
            f.add(Fact.OTHER_PET);
        }
        if (transport == Transport.CAR) {
            f.add(Fact.CAR);
        } else if (transport == Transport.BIKE) {
            f.add(Fact.BIKE);
        }
        if (!childAges.isEmpty()) {
            f.add(Fact.CHILDREN);
        }
        for (int c : childAges) {
            if (c < 4) {
                f.add(Fact.YOUNG_CHILDREN);
            } else if (c <= 12) {
                f.add(Fact.SCHOOL_CHILDREN);
            } else if (c <= 19) {
                f.add(Fact.TEEN_CHILDREN);
            }
        }
        boolean minorChild = childAges.stream().anyMatch(c -> c < 18);
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
        if (parentsAlive) {
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

    /** The life stages of this persona in its {@link #year}. A worker under 25 is a young worker; from 25 on the job gives the stage. */
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
