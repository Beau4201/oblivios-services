// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.persona;

import java.util.List;
import java.util.Set;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.style.StyleVector;

/**
 * The persona as the owner composes it at setup (ADR-319). A {@code null} field means "let the generator choose"; the
 * library receives the profile as a parameter and never stores it.
 */
public record PersonaProfile(PersonaInputs inputs, Gender gender, Integer birthYear, String nationality, List<String> languages,
        String mixLanguage, String background, JobKind job, String jobField, WorkPattern work, Relationship relationship,
        Boolean partnerAtHome, List<ChildAnswer> children, Integer household, Housing housing, List<PetAnswer> pets,
        Transport transport, List<ParentAnswer> parents, Boolean parentsTogether, List<SiblingAnswer> siblings,
        Set<FriendGroup> friendGroups, List<ActivityAnswer> activities, CircleSize circle, Set<String> interests,
        Set<String> holidays, String bank, String operator, String supermarket, Texting texting, Boolean voiceNotes,
        StyleVector style, List<TripAnswer> trips) {

    public enum Gender { FEMALE, MALE }

    public enum JobKind { OFFICE, SHIFT, STUDENT, PUPIL, SELF_EMPLOYED, RETIRED, JOB_SEEKER, CARER, NONE }

    public enum Relationship { SINGLE, PARTNER, MARRIED, DIVORCED }

    public enum Housing { RENT, OWN, WITH_PARENTS, HOUSE_SHARE }

    public enum Pet { DOG, CAT, OTHER }

    public enum Transport { CAR, BIKE, PUBLIC }

    public enum FriendGroup { SCHOOL, STUDY, WORK, SPORT, GAMING, MUSIC, FAITH, NEIGHBOURHOOD }

    public enum ActivityKind { TEAM_SPORT, GYM, RUNNING, SWIMMING, MUSIC, CHOIR, FAITH, CLASS, VOLUNTEERING, GAMING, CLUB }

    public enum TravelMode { CAR, TRAIN, PLANE, BUS, BOAT }

    public enum CircleSize { SMALL, MEDIUM, LARGE }

    public enum Texting { LOW, MEDIUM, HIGH }

    /** What a conversation may presume about the persona. */
    public enum Fact {
        DOG, CAT, OTHER_PET, CAR, BIKE, CHILDREN, YOUNG_CHILDREN, SCHOOL_CHILDREN, TEEN_CHILDREN, SINGLE_PARENT, STUDENT, PUPIL,
        SHIFT_WORK, SELF_EMPLOYED, RETIRED, JOB_SEEKER, CARER, PARTNER, SINGLE, DIVORCED, LIVES_ALONE, RENTS, OWNS_HOME,
        HOUSE_SHARE, WITH_PARENTS, PARENTS_ALIVE, SPORT, MUSIC, GAMING, FAITH, EXPAT, WORKS
    }

    /** The life stages of the design. */
    public enum Stage {
        PUPIL, STUDENT, YOUNG_WORKER, OFFICE_WORKER, SHIFT_WORKER, SELF_EMPLOYED, PARENT_SMALL, PARENT_SCHOOL, PARENT_TEEN,
        SINGLE_PARENT, RETIREE, CARER, JOB_SEEKER, EXPAT
    }

    public PersonaProfile {
        if (inputs == null) {
            throw new IllegalArgumentException("a profile needs the persona inputs");
        }
        languages = languages == null ? null : List.copyOf(languages);
        children = children == null ? null : List.copyOf(children);
        pets = pets == null ? null : List.copyOf(pets);
        parents = parents == null ? null : List.copyOf(parents);
        siblings = siblings == null ? null : List.copyOf(siblings);
        friendGroups = friendGroups == null ? null : Set.copyOf(friendGroups);
        activities = activities == null ? null : List.copyOf(activities);
        interests = interests == null ? null : Set.copyOf(interests);
        holidays = holidays == null ? null : Set.copyOf(holidays);
        trips = trips == null ? null : List.copyOf(trips);
    }

    /** Every field open except the inputs. */
    public static PersonaProfile open(PersonaInputs in) {
        return builder(in).build();
    }

    public static Builder builder(PersonaInputs in) {
        return new Builder(in);
    }

    /** Fills a profile field by field; a field never set stays open. */
    public static final class Builder {
        private final PersonaInputs inputs;
        private Gender gender;
        private Integer birthYear;
        private String nationality;
        private List<String> languages;
        private String mixLanguage;
        private String background;
        private JobKind job;
        private String jobField;
        private WorkPattern work;
        private Relationship relationship;
        private Boolean partnerAtHome;
        private List<ChildAnswer> children;
        private Integer household;
        private Housing housing;
        private List<PetAnswer> pets;
        private Transport transport;
        private List<ParentAnswer> parents;
        private Boolean parentsTogether;
        private List<SiblingAnswer> siblings;
        private Set<FriendGroup> friendGroups;
        private List<ActivityAnswer> activities;
        private CircleSize circle;
        private Set<String> interests;
        private Set<String> holidays;
        private String bank;
        private String operator;
        private String supermarket;
        private Texting texting;
        private Boolean voiceNotes;
        private StyleVector style;
        private List<TripAnswer> trips;

        private Builder(PersonaInputs inputs) {
            this.inputs = inputs;
        }

        public Builder gender(Gender v) { gender = v; return this; }
        public Builder birthYear(Integer v) { birthYear = v; return this; }
        public Builder nationality(String v) { nationality = v; return this; }
        public Builder languages(List<String> v) { languages = v; return this; }
        public Builder mixLanguage(String v) { mixLanguage = v; return this; }
        public Builder background(String v) { background = v; return this; }
        public Builder job(JobKind v) { job = v; return this; }
        public Builder jobField(String v) { jobField = v; return this; }
        public Builder work(WorkPattern v) { work = v; return this; }
        public Builder relationship(Relationship v) { relationship = v; return this; }
        public Builder partnerAtHome(Boolean v) { partnerAtHome = v; return this; }
        public Builder children(List<ChildAnswer> v) { children = v; return this; }
        public Builder household(Integer v) { household = v; return this; }
        public Builder housing(Housing v) { housing = v; return this; }
        public Builder pets(List<PetAnswer> v) { pets = v; return this; }
        public Builder transport(Transport v) { transport = v; return this; }
        public Builder parents(List<ParentAnswer> v) { parents = v; return this; }
        public Builder parentsTogether(Boolean v) { parentsTogether = v; return this; }
        public Builder siblings(List<SiblingAnswer> v) { siblings = v; return this; }
        public Builder friendGroups(Set<FriendGroup> v) { friendGroups = v; return this; }
        public Builder activities(List<ActivityAnswer> v) { activities = v; return this; }
        public Builder circle(CircleSize v) { circle = v; return this; }
        public Builder interests(Set<String> v) { interests = v; return this; }
        public Builder holidays(Set<String> v) { holidays = v; return this; }
        public Builder bank(String v) { bank = v; return this; }
        public Builder operator(String v) { operator = v; return this; }
        public Builder supermarket(String v) { supermarket = v; return this; }
        public Builder texting(Texting v) { texting = v; return this; }
        public Builder voiceNotes(Boolean v) { voiceNotes = v; return this; }
        public Builder style(StyleVector v) { style = v; return this; }
        public Builder trips(List<TripAnswer> v) { trips = v; return this; }

        public PersonaProfile build() {
            return new PersonaProfile(inputs, gender, birthYear, nationality, languages, mixLanguage, background, job, jobField, work,
                    relationship, partnerAtHome, children, household, housing, pets, transport, parents, parentsTogether, siblings,
                    friendGroups, activities, circle, interests, holidays, bank, operator, supermarket, texting, voiceNotes, style,
                    trips);
        }
    }
}
