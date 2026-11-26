// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.persona;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.persona.ChildAnswer.Custody;
import org.oblivios.decoy.content.persona.PersonaProfile.ActivityKind;
import org.oblivios.decoy.content.persona.PersonaProfile.CircleSize;
import org.oblivios.decoy.content.persona.PersonaProfile.FriendGroup;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.persona.PersonaProfile.Housing;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.persona.PersonaProfile.Pet;
import org.oblivios.decoy.content.persona.PersonaProfile.Relationship;
import org.oblivios.decoy.content.persona.PersonaProfile.Texting;
import org.oblivios.decoy.content.persona.PersonaProfile.TravelMode;
import org.oblivios.decoy.content.persona.PersonaProfile.Transport;
import org.oblivios.decoy.content.style.StyleVector;

/** A profile with every answer given, and per question the same profile with that one answer changed. */
public final class Profiles {
    private Profiles() {}

    public static PersonaInputs inputs() {
        return new PersonaInputs("Sanne", "de Vries", "utrecht", "NL", ZoneId.of("Europe/Amsterdam"), Locale.forLanguageTag("nl-NL"));
    }

    private static StyleVector style(double typos) {
        return new StyleVector(StyleVector.Register.NEUTRAL, StyleVector.Caps.STANDARD, StyleVector.Punctuation.FULL, 0.1, 1,
                StyleVector.Length.MEDIUM, typos, 0.05);
    }

    public static ChildAnswer child(int age, Gender gender, String name) {
        return new ChildAnswer(age, gender, name, Custody.HOME, Set.of());
    }

    public static PersonaProfile.Builder fullBuilder(PersonaInputs in) {
        return PersonaProfile.builder(in).gender(Gender.FEMALE).birthYear(1988).nationality("NL").languages(List.of("nl", "en"))
                .mixLanguage("en").background("NL").job(JobKind.OFFICE).jobField("finance")
                .work(new WorkPattern(Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), LocalTime.of(8, 30), LocalTime.of(17, 0), false))
                .relationship(Relationship.MARRIED).partnerAtHome(true)
                .children(List.of(child(2, Gender.FEMALE, "Mila"), child(9, Gender.MALE, "Sem"))).household(4).housing(Housing.OWN)
                .pets(List.of(new PetAnswer(Pet.DOG, "Bo"))).transport(Transport.BIKE)
                .parents(List.of(new ParentAnswer(Gender.FEMALE, true, "mam"), new ParentAnswer(Gender.MALE, true, "pap")))
                .parentsTogether(false).siblings(List.of(new SiblingAnswer(35, Gender.MALE, "Bas")))
                .friendGroups(Set.of(FriendGroup.STUDY, FriendGroup.SPORT))
                .activities(List.of(new ActivityAnswer(ActivityKind.TEAM_SPORT, DayOfWeek.TUESDAY, LocalTime.of(20, 0), 90,
                        FriendGroup.SPORT)))
                .circle(CircleSize.LARGE).interests(Set.of("cooking")).holidays(Set.of("christmas")).bank("ING").operator("KPN")
                .supermarket("Albert Heijn").texting(Texting.HIGH).voiceNotes(false).style(style(0.02))
                .trips(List.of(new TripAnswer(YearMonth.of(2026, 7), 7, "FR", TravelMode.CAR)));
    }

    public static PersonaProfile full() {
        return fullBuilder(inputs()).build();
    }

    /** {@link #full} with the answer to {@code q}, and only that one, changed. */
    public static PersonaProfile alternative(Question q) {
        PersonaInputs in = inputs();
        PersonaInputs changed = switch (q) {
            case GIVEN_NAME -> new PersonaInputs("Noor", in.familyName(), in.city(), in.countryCode(), in.zone(), in.locale());
            case FAMILY_NAME -> new PersonaInputs(in.givenName(), "Jansen", in.city(), in.countryCode(), in.zone(), in.locale());
            case CITY -> new PersonaInputs(in.givenName(), in.familyName(), "rotterdam", in.countryCode(), in.zone(), in.locale());
            default -> in;
        };
        PersonaProfile.Builder b = fullBuilder(changed);
        switch (q) {
            case GIVEN_NAME, FAMILY_NAME, CITY -> { }
            case GENDER -> b.gender(Gender.MALE);
            case BIRTH_YEAR -> b.birthYear(1980);
            case NATIONALITY -> b.nationality("PL");
            case LANGUAGES -> b.languages(List.of("nl"));
            case MIX_LANGUAGE -> b.mixLanguage("tr");
            case BACKGROUND -> b.background("TR");
            case RELATIONSHIP -> b.relationship(Relationship.PARTNER);
            case PARTNER_AT_HOME -> b.partnerAtHome(false);
            case CHILDREN -> b.children(List.of(child(9, Gender.MALE, "Sem")));
            case PETS -> b.pets(List.of(new PetAnswer(Pet.CAT, "Pip")));
            case HOUSING -> b.housing(Housing.RENT);
            case HOUSEHOLD -> b.household(5);
            case JOB -> b.job(JobKind.SHIFT);
            case JOB_FIELD -> b.jobField("care");
            case WORK_PATTERN -> b.work(new WorkPattern(Set.of(DayOfWeek.FRIDAY), LocalTime.of(9, 0), LocalTime.of(13, 0), false));
            case TRANSPORT -> b.transport(Transport.CAR);
            case ACTIVITIES -> b.activities(List.of(new ActivityAnswer(ActivityKind.GYM, DayOfWeek.FRIDAY, LocalTime.of(18, 0), 60, null)));
            case INTERESTS -> b.interests(Set.of("cycling"));
            case PARENTS -> b.parents(List.of(new ParentAnswer(Gender.FEMALE, true, "mam"), new ParentAnswer(Gender.MALE, false, "pap")));
            case PARENTS_TOGETHER -> b.parentsTogether(true);
            case SIBLINGS -> b.siblings(List.of());
            case FRIEND_GROUPS -> b.friendGroups(Set.of(FriendGroup.SCHOOL));
            case CIRCLE -> b.circle(CircleSize.SMALL);
            case BANK -> b.bank("Rabobank");
            case OPERATOR -> b.operator("Odido");
            case SUPERMARKET -> b.supermarket("Jumbo");
            case TEXTING -> b.texting(Texting.LOW);
            case VOICE_NOTES -> b.voiceNotes(true);
            case STYLE -> b.style(style(0.04));
            case HOLIDAYS -> b.holidays(Set.of("eid"));
            case TRIPS -> b.trips(List.of(new TripAnswer(YearMonth.of(2026, 3), 3, "DE", TravelMode.TRAIN)));
        }
        return b.build();
    }

    /** The answer of {@code p} to {@code q}. */
    public static Object answer(PersonaProfile p, Question q) {
        return switch (q) {
            case GIVEN_NAME -> p.inputs().givenName();
            case FAMILY_NAME -> p.inputs().familyName();
            case CITY -> p.inputs().city();
            case GENDER -> p.gender();
            case BIRTH_YEAR -> p.birthYear();
            case NATIONALITY -> p.nationality();
            case LANGUAGES -> p.languages();
            case MIX_LANGUAGE -> p.mixLanguage();
            case BACKGROUND -> p.background();
            case RELATIONSHIP -> p.relationship();
            case PARTNER_AT_HOME -> p.partnerAtHome();
            case CHILDREN -> p.children();
            case PETS -> p.pets();
            case HOUSING -> p.housing();
            case HOUSEHOLD -> p.household();
            case JOB -> p.job();
            case JOB_FIELD -> p.jobField();
            case WORK_PATTERN -> p.work();
            case TRANSPORT -> p.transport();
            case ACTIVITIES -> p.activities();
            case INTERESTS -> p.interests();
            case PARENTS -> p.parents();
            case PARENTS_TOGETHER -> p.parentsTogether();
            case SIBLINGS -> p.siblings();
            case FRIEND_GROUPS -> p.friendGroups();
            case CIRCLE -> p.circle();
            case BANK -> p.bank();
            case OPERATOR -> p.operator();
            case SUPERMARKET -> p.supermarket();
            case TEXTING -> p.texting();
            case VOICE_NOTES -> p.voiceNotes();
            case STYLE -> p.style();
            case HOLIDAYS -> p.holidays();
            case TRIPS -> p.trips();
        };
    }
}
