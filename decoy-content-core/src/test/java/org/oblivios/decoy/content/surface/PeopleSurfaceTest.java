// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.country.SenderKind;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.story.MonthCount;
import org.oblivios.decoy.content.story.Timeline;
import org.oblivios.decoy.content.story.VolumeCounts;
import org.oblivios.decoy.content.story.VolumeModel;

class PeopleSurfaceTest {
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final String[] ACM_FREE_BLOCKS = {
        "062-5780000 to 062-5999999", "062-1010000 to 062-1099999", "062-1720000 to 062-1799999",
        "062-7660000 to 062-7739999", "062-7750000 to 062-7809999", "061-5970000 to 061-5999999",
    };
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b\\d{6}\\b");

    private static boolean inAcmFreeBlock(String e164) {
        if (!e164.startsWith("+316") || e164.length() != 12) {
            return false;
        }
        long national = Long.parseLong(e164.substring(3));
        for (String block : ACM_FREE_BLOCKS) {
            String[] ends = block.split(" to ");
            if (national >= Long.parseLong(ends[0].replace("-", "")) && national <= Long.parseLong(ends[1].replace("-", ""))) {
                return true;
            }
        }
        return false;
    }

    private static CountryPack nl() {
        return Packs.forDevice("NL", ZoneId.of("Europe/Amsterdam"), Locale.ROOT).pack();
    }

    private record Setup(Persona persona, Timeline timeline, People people) {}

    private static Setup setup(long n) {
        Persona p = Persona.derive(Seed.of(n, 1),
                PersonaProfile.open(new PersonaInputs("Sam Example", "Utrecht", "NL", ZoneId.of("Europe/Amsterdam"), Locale.forLanguageTag("nl-NL"))), 2026);
        List<MonthCount> months = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            months.add(new MonthCount(YearMonth.of(2026, 10).minusMonths(17 - i), 60 + i * 5, 10, 8, 1_000_000L));
        }
        Timeline t = Timeline.generate(p, VolumeModel.fromCounts(new VolumeCounts(months), Seed.of(n, 2)), nl(), NOW, Seed.of(n, 3));
        return new Setup(p, t, PeopleSurface.generate(p, t, nl(), Seed.of(n, 4)));
    }

    @Test
    void customerNumberIsOneNumberPerOperatorThread() {
        // a customer number belongs to the customer: it never changes between the messages of one operator
        String[] around = LanguagePack.of("nl").text("sms.service.OPERATOR").split("\\{customer}", -1);
        Pattern customer = Pattern.compile(Pattern.quote(around[0]) + "(\\d+)" + Pattern.quote(around[1]));
        boolean checkedAThreadWithTwo = false;
        for (long n = 0; n < 30; n++) {
            for (SmsThread t : setup(n).people().threads()) {
                if (t.senderKind() != SenderKind.OPERATOR) {
                    continue;
                }
                List<String> numbers = new ArrayList<>();
                t.messages().forEach(m -> {
                    java.util.regex.Matcher found = customer.matcher(m.body());
                    if (found.find()) {
                        numbers.add(found.group(1));
                    }
                });
                assertEquals(1, new HashSet<>(numbers).size() + (numbers.isEmpty() ? 1 : 0), "customer numbers of " + t.title() + ": " + numbers);
                checkedAThreadWithTwo |= numbers.size() >= 2;
            }
        }
        assertTrue(checkedAThreadWithTwo, "no operator thread with two customer numbers: the check is vacuous");
    }

    private static Set<String> contactNumbers(People p) {
        Set<String> out = new HashSet<>();
        p.contacts().forEach(c -> out.add(c.number()));
        return out;
    }

    @Test
    void numbersAreInVerifiedRange() {
        for (long n = 0; n < 15; n++) {
            People p = setup(n).people();
            List<String> all = new ArrayList<>(contactNumbers(p));
            p.calls().forEach(c -> all.add(c.number()));
            p.voicemails().forEach(v -> all.add(v.fromNumber()));
            all.add(p.ownNumber());
            for (String num : all) {
                assertTrue(inAcmFreeBlock(num), "outside the free blocks: " + num);
            }
        }
    }

    @Test
    void contactCountBetween60And400() {
        for (long n = 0; n < 40; n++) {
            int count = setup(n).people().contacts().size();
            assertTrue(count >= 60 && count <= 400, "contacts " + count);
        }
    }

    @Test
    void favouritesAtMost8() {
        for (long n = 0; n < 40; n++) {
            long fav = setup(n).people().contacts().stream().filter(Contact::favourite).count();
            assertTrue(fav <= 8, "favourites " + fav);
        }
    }

    @Test
    void contactNumbersAreUnique() {
        for (long n = 0; n < 15; n++) {
            People p = setup(n).people();
            assertEquals(p.contacts().size(), contactNumbers(p).size());
        }
    }

    @Test
    void callsOnlyToContacts() {
        // Real services have no verified numbers, so the call log holds calls to contacts only.
        for (long n = 0; n < 15; n++) {
            People p = setup(n).people();
            Set<String> numbers = contactNumbers(p);
            for (Call c : p.calls()) {
                assertTrue(numbers.contains(c.number()), "call to a stranger: " + c.number());
            }
            assertTrue(p.calls().size() > 50, "calls " + p.calls().size());
        }
    }

    @Test
    void smsHasOneTimeCodes() {
        for (long n = 0; n < 15; n++) {
            People p = setup(n).people();
            boolean found = false;
            for (SmsThread t : p.threads()) {
                if (t.senderKind() == SenderKind.BANK || t.senderKind() == SenderKind.OPERATOR) {
                    for (SmsMessage m : t.messages()) {
                        found |= SIX_DIGITS.matcher(m.body()).find();
                    }
                }
            }
            assertTrue(found, "no bank or operator thread with a six digit code for seed " + n);
        }
    }

    @Test
    void noContactHasTheOwnNumber() {
        for (long n = 0; n < 15; n++) {
            People p = setup(n).people();
            assertFalse(contactNumbers(p).contains(p.ownNumber()));
        }
    }

    @Test
    void missedCallsHaveZeroDuration() {
        for (long n = 0; n < 15; n++) {
            People p = setup(n).people();
            boolean anyMissed = false;
            for (Call c : p.calls()) {
                if (c.type() == CallType.MISSED) {
                    anyMissed = true;
                    assertEquals(0, c.durationSeconds());
                } else {
                    assertTrue(c.durationSeconds() > 0, "answered call without duration");
                }
            }
            assertTrue(anyMissed);
        }
    }

    @Test
    void lastContactedMatchesLatestCallOrSms() {
        for (long n = 0; n < 15; n++) {
            People p = setup(n).people();
            Map<String, Instant> latest = new java.util.HashMap<>();
            p.calls().forEach(c -> latest.merge(c.number(), c.at(), (a, b) -> a.isAfter(b) ? a : b));
            for (SmsThread t : p.threads()) {
                if (t.senderKind() == null) {
                    t.messages().forEach(m -> latest.merge(t.address(), m.at(), (a, b) -> a.isAfter(b) ? a : b));
                }
            }
            for (Contact c : p.contacts()) {
                assertEquals(latest.get(c.number()), c.lastContacted(), "contact " + c.id());
            }
        }
    }

    @Test
    void contactPhotosAreNoneOrInitials() {
        for (Contact c : setup(1).people().contacts()) {
            assertTrue(c.photo() == PhotoKind.NONE || c.photo() == PhotoKind.INITIALS);
        }
    }

    @Test
    void voicemailsComeFromContactsOrUnknown() {
        for (long n = 0; n < 15; n++) {
            People p = setup(n).people();
            Set<String> numbers = contactNumbers(p);
            for (Voicemail v : p.voicemails()) {
                assertNotEquals(p.ownNumber(), v.fromNumber());
                assertTrue(v.seconds() > 0 && v.seconds() <= 180);
                assertTrue(numbers.contains(v.fromNumber()) || inAcmFreeBlock(v.fromNumber()));
            }
        }
    }

    @Test
    void noEventAfterNowOrBeforeThePhoneStart() {
        for (long n = 0; n < 15; n++) {
            Setup s = setup(n);
            People p = s.people();
            List<Instant> all = new ArrayList<>();
            p.calls().forEach(c -> all.add(c.at()));
            p.voicemails().forEach(v -> all.add(v.at()));
            p.threads().forEach(t -> t.messages().forEach(m -> all.add(m.at())));
            for (Instant at : all) {
                assertFalse(at.isAfter(s.timeline().now()), "event after now: " + at);
                assertFalse(at.isBefore(s.timeline().phoneStart()), "event before the phone start: " + at);
            }
        }
    }

    @Test
    void callsRespectTheRhythm() {
        for (long n = 0; n < 8; n++) {
            Setup s = setup(n);
            ZoneId zone = s.persona().inputs().zone();
            for (Call c : s.people().calls()) {
                int hour = c.at().atZone(zone).getHour();
                assertTrue(hour >= 5 && hour < 24, "call at hour " + hour);
            }
        }
    }

    @Test
    void sameSeedSamePeople() {
        assertEquals(setup(5).people(), setup(5).people());
    }

    // --- the circle: relation, age and gender of every contact ---

    private static final int YEAR = 2026;

    private static Setup setup(long n, java.util.function.UnaryOperator<PersonaProfile.Builder> f) {
        PersonaInputs in = new PersonaInputs("Sam Example", "Utrecht", "NL", ZoneId.of("Europe/Amsterdam"), Locale.forLanguageTag("nl-NL"));
        Persona p = Persona.derive(Seed.of(n, 1), f.apply(PersonaProfile.builder(in)).build(), YEAR);
        List<MonthCount> months = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            months.add(new MonthCount(YearMonth.of(2026, 10).minusMonths(11 - i), 60, 10, 8, 1_000_000L));
        }
        Timeline t = Timeline.generate(p, VolumeModel.fromCounts(new VolumeCounts(months), Seed.of(n, 2)), nl(), NOW, Seed.of(n, 3));
        return new Setup(p, t, PeopleSurface.generate(p, t, nl(), Seed.of(n, 4)));
    }

    private static List<Contact> of(Setup s, Relation r) {
        return s.people().contacts().stream().filter(c -> c.relation() == r).toList();
    }

    private static int ageOf(Contact c) {
        return YEAR - c.birthYear();
    }

    @Test
    void parentsAre25To35YearsOlder() {
        boolean seen = false;
        for (long n = 0; n < 40; n++) {
            Setup s = setup(n, b -> b.birthYear(YEAR - 30).parentsAlive(true));
            for (Contact c : of(s, Relation.PARENT)) {
                int gap = ageOf(c) - s.persona().age();
                assertTrue(gap >= 25 && gap <= 35, "parent " + gap + " years older");
                seen = true;
            }
        }
        assertTrue(seen, "no parent drawn");
    }

    @Test
    void noParentsWhenParentsAreNotAlive() {
        for (long n = 0; n < 20; n++) {
            Setup s = setup(n, b -> b.parentsAlive(false).housing(PersonaProfile.Housing.RENT));
            assertTrue(of(s, Relation.PARENT).isEmpty());
            assertTrue(of(s, Relation.STEP_PARENT).isEmpty());
        }
    }

    @Test
    void partnerOnlyWithAPartnerOrMarriage() {
        for (long n = 0; n < 20; n++) {
            assertEquals(1, of(setup(n, b -> b.relationship(PersonaProfile.Relationship.MARRIED).household(2)), Relation.PARTNER).size());
            assertEquals(1, of(setup(n, b -> b.relationship(PersonaProfile.Relationship.PARTNER)), Relation.PARTNER).size());
            assertTrue(of(setup(n, b -> b.relationship(PersonaProfile.Relationship.SINGLE)), Relation.PARTNER).isEmpty());
            assertTrue(of(setup(n, b -> b.relationship(PersonaProfile.Relationship.DIVORCED)), Relation.PARTNER).isEmpty());
        }
    }

    @Test
    void colleaguesOnlyWithAJob() {
        boolean seen = false;
        for (long n = 0; n < 20; n++) {
            for (PersonaProfile.JobKind job : PersonaProfile.JobKind.values()) {
                Setup s = setup(n, b -> b.birthYear(YEAR - 40).job(job).childAges(List.of()));
                List<Contact> colleagues = of(s, Relation.COLLEAGUE);
                boolean working = job == PersonaProfile.JobKind.OFFICE || job == PersonaProfile.JobKind.SHIFT
                        || job == PersonaProfile.JobKind.SELF_EMPLOYED;
                if (!working) {
                    assertTrue(colleagues.isEmpty(), job + " has colleagues");
                }
                for (Contact c : colleagues) {
                    assertTrue(ageOf(c) >= 20 && ageOf(c) <= 65, "colleague of " + ageOf(c));
                    seen = true;
                }
            }
        }
        assertTrue(seen);
    }

    @Test
    void friendsComeFromTheChosenGroups() {
        Set<PersonaProfile.FriendGroup> chosen = Set.of(PersonaProfile.FriendGroup.SPORT, PersonaProfile.FriendGroup.MUSIC);
        for (long n = 0; n < 20; n++) {
            Setup s = setup(n, b -> b.friendGroups(chosen));
            for (Relation r : List.of(Relation.FRIEND, Relation.BEST_FRIEND, Relation.OLD_FRIEND)) {
                for (Contact c : of(s, r)) {
                    assertTrue(chosen.contains(c.origin()), r + " from " + c.origin());
                }
            }
            assertTrue(of(s, Relation.BEST_FRIEND).size() <= 1);
            s.people().contacts().stream().filter(c -> c.relation() != Relation.FRIEND && c.relation() != Relation.BEST_FRIEND
                    && c.relation() != Relation.OLD_FRIEND).forEach(c -> assertEquals(null, c.origin(), c.relation().name()));
        }
    }

    @Test
    void schoolFriendsAreWithinTwoYears() {
        boolean seen = false;
        for (long n = 0; n < 20; n++) {
            Setup s = setup(n, b -> b.friendGroups(Set.of(PersonaProfile.FriendGroup.SCHOOL, PersonaProfile.FriendGroup.WORK)));
            for (Contact c : s.people().contacts()) {
                if (c.origin() == PersonaProfile.FriendGroup.SCHOOL) {
                    assertTrue(Math.abs(ageOf(c) - s.persona().age()) <= 2, "school friend " + ageOf(c) + " for " + s.persona().age());
                    seen = true;
                } else if (c.origin() == PersonaProfile.FriendGroup.WORK) {
                    assertTrue(Math.abs(ageOf(c) - s.persona().age()) <= 8);
                }
            }
        }
        assertTrue(seen);
    }

    @Test
    void circleSizeOrdersFriendCounts() {
        double[] mean = new double[3];
        for (PersonaProfile.CircleSize size : PersonaProfile.CircleSize.values()) {
            int total = 0;
            for (long n = 0; n < 50; n++) {
                Setup s = setup(n, b -> b.circle(size));
                total += of(s, Relation.FRIEND).size() + of(s, Relation.BEST_FRIEND).size() + of(s, Relation.OLD_FRIEND).size();
            }
            mean[size.ordinal()] = total / 50.0;
        }
        assertTrue(mean[0] < mean[1] && mean[1] < mean[2], java.util.Arrays.toString(mean));
    }

    @Test
    void familyRelationsGetTheFamilyGroup() {
        List<String> groups = LanguagePack.of("nl").list("contact.groups");
        Set<Relation> family = Set.of(Relation.PARTNER, Relation.PARENT, Relation.STEP_PARENT, Relation.GRANDPARENT, Relation.SIBLING,
                Relation.CHILD, Relation.IN_LAW, Relation.RELATIVE);
        Set<Relation> friends = Set.of(Relation.BEST_FRIEND, Relation.FRIEND, Relation.OLD_FRIEND, Relation.HOUSEMATE);
        Set<Relation> work = Set.of(Relation.COLLEAGUE, Relation.MANAGER, Relation.REPORT, Relation.CLIENT);
        for (long n = 0; n < 10; n++) {
            for (Contact c : setup(n).people().contacts()) {
                int want = family.contains(c.relation()) ? 0 : friends.contains(c.relation()) ? 1 : work.contains(c.relation()) ? 2 : 3;
                assertEquals(groups.get(want), c.group(), c.relation().name());
            }
        }
    }

    @Test
    void exOnlyFromTwenty() {
        for (long n = 0; n < 30; n++) {
            assertTrue(of(setup(n, b -> b.birthYear(YEAR - 18).relationship(PersonaProfile.Relationship.SINGLE)), Relation.EX_PARTNER).isEmpty());
            assertEquals(1, of(setup(n, b -> b.birthYear(YEAR - 40).relationship(PersonaProfile.Relationship.DIVORCED)), Relation.EX_PARTNER).size());
        }
    }

    @Test
    void dateOnlyWhenSingleOrDivorced() {
        boolean seen = false;
        for (long n = 0; n < 30; n++) {
            assertTrue(of(setup(n, b -> b.relationship(PersonaProfile.Relationship.MARRIED).household(2)), Relation.DATE).isEmpty());
            assertTrue(of(setup(n, b -> b.birthYear(YEAR - 55).relationship(PersonaProfile.Relationship.SINGLE)), Relation.DATE).isEmpty());
            seen |= !of(setup(n, b -> b.birthYear(YEAR - 30).relationship(PersonaProfile.Relationship.SINGLE)), Relation.DATE).isEmpty();
        }
        assertTrue(seen, "a single persona of 30 never has a date");
    }

    @Test
    void inLawsOnlyWithAPartner() {
        boolean seen = false;
        for (long n = 0; n < 20; n++) {
            assertTrue(of(setup(n, b -> b.relationship(PersonaProfile.Relationship.SINGLE)), Relation.IN_LAW).isEmpty());
            seen |= !of(setup(n, b -> b.relationship(PersonaProfile.Relationship.MARRIED).household(2)), Relation.IN_LAW).isEmpty();
        }
        assertTrue(seen);
    }

    @Test
    void housematesOnlyInAHouseShare() {
        for (long n = 0; n < 20; n++) {
            assertTrue(of(setup(n, b -> b.housing(PersonaProfile.Housing.OWN)), Relation.HOUSEMATE).isEmpty());
            assertFalse(of(setup(n, b -> b.housing(PersonaProfile.Housing.HOUSE_SHARE).household(4)), Relation.HOUSEMATE).isEmpty());
        }
    }

    @Test
    void teachersAndClassmatesOnlyForPupilsAndStudents() {
        for (long n = 0; n < 20; n++) {
            Setup office = setup(n, b -> b.birthYear(YEAR - 40).job(PersonaProfile.JobKind.OFFICE));
            assertTrue(of(office, Relation.TEACHER).isEmpty() && of(office, Relation.CLASSMATE).isEmpty());
            Setup student = setup(n, b -> b.birthYear(YEAR - 20).job(PersonaProfile.JobKind.STUDENT));
            assertFalse(of(student, Relation.CLASSMATE).isEmpty());
            for (Contact c : of(student, Relation.CLASSMATE)) {
                assertTrue(Math.abs(ageOf(c) - 20) <= 2);
            }
        }
    }

    @Test
    void landlordOnlyWhenRenting() {
        for (long n = 0; n < 20; n++) {
            assertTrue(of(setup(n, b -> b.housing(PersonaProfile.Housing.OWN)), Relation.LANDLORD).isEmpty());
            assertTrue(of(setup(n, b -> b.housing(PersonaProfile.Housing.RENT)), Relation.TENANT).isEmpty());
            assertEquals(1, of(setup(n, b -> b.housing(PersonaProfile.Housing.RENT)), Relation.LANDLORD).size());
        }
    }

    @Test
    void childminderOnlyWithAChildUnderTwelve() {
        boolean seen = false;
        for (long n = 0; n < 30; n++) {
            Setup teen = setup(n, b -> b.birthYear(YEAR - 45).childAges(List.of(14, 17)));
            assertTrue(of(teen, Relation.CHILDMINDER).isEmpty());
            assertTrue(of(teen, Relation.PARENT_OF_FRIEND).isEmpty());
            seen |= !of(setup(n, b -> b.birthYear(YEAR - 35).childAges(List.of(5))), Relation.CHILDMINDER).isEmpty();
        }
        assertTrue(seen);
    }

    @Test
    void childrenWithAPhoneAreTenOrOlder() {
        for (long n = 0; n < 10; n++) {
            Setup s = setup(n, b -> b.birthYear(YEAR - 45).childAges(List.of(3, 11, 16)));
            List<Contact> children = of(s, Relation.CHILD);
            assertEquals(2, children.size());
            for (Contact c : children) {
                assertTrue(ageOf(c) >= 10);
            }
        }
    }

    @Test
    void grandparentsAreAtMost95() {
        boolean seen = false;
        for (long n = 0; n < 60; n++) {
            Setup s = setup(n, b -> b.birthYear(YEAR - 30).parentsAlive(true));
            for (Contact c : of(s, Relation.GRANDPARENT)) {
                assertTrue(ageOf(c) <= 95, "grandparent of " + ageOf(c));
                seen = true;
            }
            assertTrue(of(setup(n, b -> b.birthYear(YEAR - 40)), Relation.GRANDPARENT).isEmpty());
        }
        assertTrue(seen);
    }
}
