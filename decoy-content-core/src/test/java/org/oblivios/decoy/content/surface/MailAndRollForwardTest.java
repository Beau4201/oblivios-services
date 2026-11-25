// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
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

class MailAndRollForwardTest {
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

    private static CountryPack nl() {
        return Packs.forDevice("NL", ZONE, Locale.ROOT).pack();
    }

    private record Setup(Persona persona, Timeline timeline, People people) {}

    private static Setup setup(long n) {
        Persona p = Persona.derive(Seed.of(n, 1), new PersonaInputs("Sam Example", "Utrecht", "NL", ZONE, Locale.forLanguageTag("nl-NL")));
        List<MonthCount> months = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            months.add(new MonthCount(YearMonth.of(2026, 10).minusMonths(29 - i), 60 + i * 5, 10, 8, 1_000_000L));
        }
        Timeline t = Timeline.generate(p, VolumeModel.fromCounts(new VolumeCounts(months), Seed.of(n, 2)), nl(), NOW, Seed.of(n, 3));
        return new Setup(p, t, PeopleSurface.generate(p, t, nl(), Seed.of(n, 4)));
    }

    private static MailPlan mail(Setup s, ServerProbe probe, long n) {
        return MailSurface.generate(s.persona(), s.timeline(), nl(), probe, Seed.of(n, 5));
    }

    // ---- mail -----------------------------------------------------------------------------------------------

    @Test
    void appendDateFormat() {
        assertEquals("01-Jan-2023 09:15:00 +0100", MailSurface.appendDate(Instant.parse("2023-01-01T08:15:00Z"), ZONE));
        assertEquals("01-Jul-2023 10:15:00 +0200", MailSurface.appendDate(Instant.parse("2023-07-01T08:15:00Z"), ZONE));
    }

    @Test
    void mailDatedBetween6MonthsAnd6YearsBackAndAfterThePhoneStart() {
        for (long n = 0; n < 20; n++) {
            Setup s = setup(n);
            ZonedDateTime now = NOW.atZone(ZONE);
            Instant latest = now.minusMonths(6).toInstant();
            Instant earliest = now.minusYears(6).toInstant();
            MailPlan plan = mail(s, ServerProbe.HONOURED, n);
            assertTrue(plan.messages().size() >= 25 && plan.messages().size() <= 150, "messages " + plan.messages().size());
            for (MailMessage m : plan.messages()) {
                assertFalse(m.date().isAfter(latest), "newer than 6 months: " + m);
                assertFalse(m.date().isBefore(earliest), "older than 6 years: " + m);
                assertFalse(m.date().isBefore(s.timeline().phoneStart()), "before the phone start: " + m);
            }
        }
    }

    @Test
    void sendersFromCountryPack() {
        Set<String> known = new HashSet<>();
        for (SenderKind k : SenderKind.values()) {
            nl().senders(k).forEach(x -> known.add(x.name()));
        }
        for (long n = 0; n < 10; n++) {
            for (MailMessage m : mail(setup(n), ServerProbe.HONOURED, n).messages()) {
                assertTrue(known.contains(m.from()), "sender of another country or invented: " + m.from());
            }
        }
    }

    @Test
    void neutralPackMailComesFromGlobalSendersOnly() {
        Setup s = setup(1);
        CountryPack neutral = Packs.neutral();
        Set<String> global = neutral.senders(SenderKind.GLOBAL).stream().map(x -> x.name()).collect(Collectors.toSet());
        MailPlan plan = MailSurface.generate(s.persona(), s.timeline(), neutral, ServerProbe.HONOURED, Seed.of(1, 5));
        assertFalse(plan.messages().isEmpty());
        for (MailMessage m : plan.messages()) {
            assertTrue(global.contains(m.from()), m.from());
        }
    }

    @Test
    void serverIgnoresInternalDate() {
        Setup s = setup(2);
        MailPlan refused = mail(s, ServerProbe.IGNORED, 2);
        assertTrue(refused.messages().isEmpty());
        assertTrue(refused.refusedNotice().isPresent());
        assertFalse(refused.refusedNotice().get().isBlank());
        MailPlan honoured = mail(s, ServerProbe.HONOURED, 2);
        assertFalse(honoured.messages().isEmpty());
        assertTrue(honoured.refusedNotice().isEmpty());
    }

    @Test
    void everyMessageHasSubjectAndBodyAndNoneIsADuplicate() {
        for (long n = 0; n < 10; n++) {
            Set<String> seen = new HashSet<>();
            for (MailMessage m : mail(setup(n), ServerProbe.HONOURED, n).messages()) {
                assertFalse(m.subject().isBlank());
                assertFalse(m.body().isBlank());
                assertTrue(seen.add(m.from() + "|" + m.subject() + "|" + m.date()), "duplicate " + m);
            }
        }
    }

    @Test
    void mailIsOrderedByDateAndFollowsTheCountryLanguage() {
        for (long n = 0; n < 5; n++) {
            List<MailMessage> messages = mail(setup(n), ServerProbe.HONOURED, n).messages();
            for (int i = 1; i < messages.size(); i++) {
                assertFalse(messages.get(i).date().isBefore(messages.get(i - 1).date()));
            }
        }
        Persona english = Persona.derive(Seed.of(1, 1), new PersonaInputs("Sam Example", "Utrecht", "NL", ZONE, Locale.US));
        Setup s = setup(1);
        // a sender of the country writes in the country's language, whatever the persona speaks
        MailPlan en = MailSurface.generate(english, s.timeline(), nl(), ServerProbe.HONOURED, Seed.of(1, 5));
        MailPlan nlPlan = mail(s, ServerProbe.HONOURED, 1);
        assertEquals(nlPlan.messages().get(0).subject(), en.messages().get(0).subject());
        // the neutral pack has no language: its senders write in the persona's language
        MailPlan enNeutral = MailSurface.generate(english, s.timeline(), Packs.neutral(), ServerProbe.HONOURED, Seed.of(1, 5));
        MailPlan nlNeutral = MailSurface.generate(s.persona(), s.timeline(), Packs.neutral(), ServerProbe.HONOURED, Seed.of(1, 5));
        assertNotEquals(nlNeutral.messages().get(0).subject(), enNeutral.messages().get(0).subject());
    }

    @Test
    void sameSeedSameMail() {
        Setup s = setup(3);
        assertEquals(mail(s, ServerProbe.HONOURED, 3), mail(s, ServerProbe.HONOURED, 3));
        assertNotEquals(mail(s, ServerProbe.HONOURED, 3), mail(s, ServerProbe.HONOURED, 4));
    }

    // ---- roll-forward ---------------------------------------------------------------------------------------

    private static ContentSnapshot snapshot(Setup s, Instant latestEvent) {
        return new ContentSnapshot(s.persona(), latestEvent, s.people().contacts());
    }

    private static long count(RollForwardPlan plan, String kind) {
        return plan.events().stream().filter(e -> e.kind().equals(kind)).count();
    }

    @Test
    void rollForwardAddsOnlyAfterLastEvent() {
        Setup s = setup(1);
        Instant last = NOW.minus(Duration.ofDays(30));
        Set<String> ids = s.people().contacts().stream().map(Contact::id).collect(Collectors.toSet());
        RollForwardPlan plan = RollForward.plan(snapshot(s, last), NOW, Seed.of(9, 9));
        assertTrue(count(plan, "CALL") >= 1 && count(plan, "SMS") >= 1, "events " + plan.events().size());
        for (RollForwardEvent e : plan.events()) {
            assertTrue(e.when().isAfter(last), "not after the last event: " + e);
            assertFalse(e.when().isAfter(NOW), "in the future: " + e);
            assertTrue(ids.contains(e.detail().split("\\|")[0]), "unknown contact in " + e);
        }
    }

    @Test
    void longGapIsBounded() {
        Setup s = setup(1);
        for (int days : new int[] {180, 3650}) {
            RollForwardPlan plan = RollForward.plan(snapshot(s, NOW.minus(Duration.ofDays(days))), NOW, Seed.of(9, days));
            assertTrue(count(plan, "SMS") <= 60, days + " days: SMS " + count(plan, "SMS"));
            assertTrue(count(plan, "CALL") <= 40, days + " days: calls " + count(plan, "CALL"));
            assertEquals(plan.events().size(), count(plan, "SMS") + count(plan, "CALL"));
        }
        // a gap of 180 days is large enough to reach the caps, so the bound is the rule that holds the count
        RollForwardPlan plan = RollForward.plan(snapshot(s, NOW.minus(Duration.ofDays(180))), NOW, Seed.of(9, 180));
        assertTrue(count(plan, "SMS") >= 30 && count(plan, "CALL") >= 20);
    }

    @Test
    void noEventLandsOnTheLatestEventToTheMinute() {
        // a draw can hit the very minute of the latest event; thousands of plans make that happen
        Setup s = setup(1);
        Instant latest = NOW.minus(Duration.ofDays(40));
        ContentSnapshot snap = snapshot(s, latest);
        for (long n = 0; n < 8000; n++) {
            for (RollForwardEvent e : RollForward.plan(snap, NOW, Seed.of(n, 77)).events()) {
                assertTrue(e.when().isAfter(latest), "event at or before the latest event, seed " + n + ": " + e);
            }
        }
    }

    @Test
    void clockMovedBack() {
        Setup s = setup(1);
        RollForwardPlan plan = RollForward.plan(snapshot(s, NOW.plus(Duration.ofDays(3))), NOW, Seed.of(9, 9));
        assertTrue(plan.events().isEmpty());
    }

    @Test
    void noGapNoEvents() {
        Setup s = setup(1);
        assertTrue(RollForward.plan(snapshot(s, NOW), NOW, Seed.of(9, 9)).events().isEmpty());
        assertTrue(RollForward.plan(snapshot(s, NOW.minus(Duration.ofMinutes(20))), NOW, Seed.of(9, 9)).events().isEmpty());
    }

    @Test
    void noContactsNoEvents() {
        Setup s = setup(1);
        ContentSnapshot empty = new ContentSnapshot(s.persona(), NOW.minus(Duration.ofDays(30)), List.of());
        assertTrue(RollForward.plan(empty, NOW, Seed.of(9, 9)).events().isEmpty());
    }

    @Test
    void eventsAreOrderedAndInWakingHours() {
        Setup s = setup(2);
        RollForwardPlan plan = RollForward.plan(snapshot(s, NOW.minus(Duration.ofDays(60))), NOW, Seed.of(5, 5));
        Instant previous = Instant.MIN;
        for (RollForwardEvent e : plan.events()) {
            assertFalse(e.when().isBefore(previous));
            previous = e.when();
            int hour = e.when().atZone(ZONE).getHour();
            assertTrue(hour >= 7 && hour <= 22, e + " at hour " + hour);
        }
    }

    @Test
    void rollForwardMessagesNameTheContactsOfTheStory() {
        Setup s = setup(1);
        Set<String> firstNames = s.people().contacts().stream().map(c -> c.name().split(" ")[0]).collect(Collectors.toSet());
        boolean named = false;
        for (long n = 0; n < 20; n++) {
            RollForwardPlan plan = RollForward.plan(snapshot(s, NOW.minus(Duration.ofDays(90))), NOW, Seed.of(n, 5));
            for (RollForwardEvent e : plan.events()) {
                named |= e.kind().equals("SMS") && firstNames.stream().anyMatch(f -> e.detail().split("\\|", 3)[2].contains(" " + f));
            }
        }
        assertTrue(named, "no roll-forward message names a contact");
    }

    @Test
    void rollForwardIsDeterministicPerFreshSeed() {
        Setup s = setup(1);
        ContentSnapshot snap = snapshot(s, NOW.minus(Duration.ofDays(45)));
        assertEquals(RollForward.plan(snap, NOW, Seed.of(1, 1)), RollForward.plan(snap, NOW, Seed.of(1, 1)));
        assertNotEquals(RollForward.plan(snap, NOW, Seed.of(1, 1)), RollForward.plan(snap, NOW, Seed.of(1, 2)));
    }
}
