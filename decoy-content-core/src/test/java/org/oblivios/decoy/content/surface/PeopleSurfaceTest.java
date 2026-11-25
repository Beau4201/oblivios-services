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
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.country.SenderKind;
import org.oblivios.decoy.content.story.MonthCount;
import org.oblivios.decoy.content.story.Timeline;
import org.oblivios.decoy.content.story.VolumeCounts;
import org.oblivios.decoy.content.story.VolumeModel;

class PeopleSurfaceTest {
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final String[] ACM_FREE_BLOCKS = {
        "062-5780000 t/m 062-5999999", "062-1010000 t/m 062-1099999", "062-1720000 t/m 062-1799999",
        "062-7660000 t/m 062-7739999", "062-7750000 t/m 062-7809999", "061-5970000 t/m 061-5999999",
    };
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b\\d{6}\\b");

    private static boolean inAcmFreeBlock(String e164) {
        if (!e164.startsWith("+316") || e164.length() != 12) {
            return false;
        }
        long national = Long.parseLong(e164.substring(3));
        for (String block : ACM_FREE_BLOCKS) {
            String[] ends = block.split(" t/m ");
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
                new PersonaInputs("Sam Example", "Utrecht", "NL", ZoneId.of("Europe/Amsterdam"), Locale.forLanguageTag("nl-NL")));
        List<MonthCount> months = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            months.add(new MonthCount(YearMonth.of(2026, 10).minusMonths(17 - i), 60 + i * 5, 10, 8, 1_000_000L));
        }
        Timeline t = Timeline.generate(p, VolumeModel.fromCounts(new VolumeCounts(months), Seed.of(n, 2)), nl(), NOW, Seed.of(n, 3));
        return new Setup(p, t, PeopleSurface.generate(p, t, nl(), Seed.of(n, 4)));
    }

    @Test
    void messagesNameTheContactsAndPlacesOfTheStory() {
        // a message refers to a person and a place that exist elsewhere in the decoy
        boolean namesAContact = false;
        boolean namesAPlace = false;
        for (long n = 0; n < 10; n++) {
            Setup s = setup(n);
            Set<String> firstNames = new HashSet<>();
            s.people().contacts().forEach(c -> firstNames.add(c.name().split(" ")[0]));
            Set<String> places = new HashSet<>();
            s.timeline().places().forEach(p -> places.add(p.name()));
            for (SmsThread t : s.people().threads()) {
                if (t.senderKind() != null) {
                    continue;
                }
                for (SmsMessage m : t.messages()) {
                    namesAContact |= firstNames.stream().anyMatch(f -> m.body().contains(" " + f));
                    namesAPlace |= places.stream().anyMatch(m.body()::contains);
                }
            }
        }
        assertTrue(namesAContact, "no message names a contact");
        assertTrue(namesAPlace, "no message names a place of the story");
    }

    @Test
    void customerNumberIsOneNumberPerOperatorThread() {
        // a customer number belongs to the customer: it never changes between the messages of one operator
        Pattern customer = Pattern.compile("Klantnummer (\\d+)");
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
}
