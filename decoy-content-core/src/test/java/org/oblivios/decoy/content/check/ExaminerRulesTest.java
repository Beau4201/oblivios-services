// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.oblivios.decoy.content.check.RuleFixtures.NOW;
import static org.oblivios.decoy.content.check.RuleFixtures.ago;
import static org.oblivios.decoy.content.check.RuleFixtures.bare;
import static org.oblivios.decoy.content.check.RuleFixtures.contact;
import static org.oblivios.decoy.content.check.RuleFixtures.firstWord;
import static org.oblivios.decoy.content.check.RuleFixtures.number;
import static org.oblivios.decoy.content.check.RuleFixtures.outgoing;
import static org.oblivios.decoy.content.check.RuleFixtures.people;
import static org.oblivios.decoy.content.check.RuleFixtures.persona;
import static org.oblivios.decoy.content.check.RuleFixtures.planted;
import static org.oblivios.decoy.content.check.RuleFixtures.thread;
import static org.oblivios.decoy.content.check.RuleFixtures.with;


import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.country.SenderKind;
import org.oblivios.decoy.content.persona.PersonaProfile.Transport;
import org.oblivios.decoy.content.plan.ContentPlan;
import org.oblivios.decoy.content.surface.BluetoothPair;
import org.oblivios.decoy.content.surface.BrowserPlan;
import org.oblivios.decoy.content.surface.CalEvent;
import org.oblivios.decoy.content.surface.CalendarPlan;
import org.oblivios.decoy.content.surface.Call;
import org.oblivios.decoy.content.surface.CallType;
import org.oblivios.decoy.content.surface.Contact;
import org.oblivios.decoy.content.surface.Cookie;
import org.oblivios.decoy.content.surface.MediaPlan;
import org.oblivios.decoy.content.surface.NetworkPlan;
import org.oblivios.decoy.content.surface.People;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.surface.SmsThread;
import org.oblivios.decoy.content.surface.Voicemail;

class ExaminerRulesTest {
    private static int count(ContentPlan p, String id) {
        return planted(p).only(id).size();
    }

    private static List<Contact> one() {
        return List.of(contact("c1", "Sam Example", number(1), Relation.FRIEND));
    }

    /** Two camera photos in Utrecht and Rotterdam, about 48 km apart, {@code minutes} apart. */
    private static ContentPlan twoPlaces(int minutes) {
        return with(bare(), new MediaPlan(List.of(RuleFixtures.photo(52.09, 5.12, ago(9, 0)),
                RuleFixtures.photo(51.92, 4.48, ago(9, 0).plus(Duration.ofMinutes(minutes)))), List.of()));
    }

    @Test
    void twoPlacesTooFarApartIsReported() {
        assertEquals(1, count(twoPlaces(10), "T-ONE-PLACE"));
    }

    @Test
    void aJourneyThatFitsPasses() {
        assertEquals(0, count(twoPlaces(45), "T-ONE-PLACE"));
    }

    @Test
    void sameLabelTwiceIsReported() {
        ContentPlan twice = with(bare(), people(List.of(contact("c1", "Sam Example", number(1), Relation.FRIEND),
                contact("c2", "Sam Example", number(2), Relation.COLLEAGUE)), List.of(), List.of()));
        assertEquals(1, count(twice, "N-DISTINCT"));
        ContentPlan alone = with(bare(), people(List.of(contact("c1", "Sam", number(1), Relation.FRIEND),
                contact("c2", "Sam Example", number(2), Relation.COLLEAGUE)), List.of(), List.of()));
        assertEquals(1, count(alone, "N-DISTINCT"), "a given name alone beside another label of that name");
        ContentPlan distinct = with(bare(), people(List.of(contact("c1", "Sam Example", number(1), Relation.FRIEND),
                contact("c2", "Sam Other", number(2), Relation.COLLEAGUE)), List.of(), List.of()));
        assertEquals(0, count(distinct, "N-DISTINCT"));
    }

    @Test
    void carWordWithoutACarIsReported() {
        String word = firstWord("nl", "presumes.CAR");
        CalendarPlan calendar = new CalendarPlan(List.of(new CalEvent(word, ago(5, 0), false)), List.of(), List.of());
        Persona noCar = persona(b -> b.transport(Transport.BIKE));
        Persona car = persona(b -> b.transport(Transport.CAR));
        assertEquals(1, count(with(bare(noCar), calendar), "F-PRESUMES"));
        assertEquals(0, count(with(bare(car), calendar), "F-PRESUMES"));
    }

    @Test
    void twoOperatorsAreReported() {
        List<SmsThread> threads = new ArrayList<>();
        Checks.packOf(bare().persona()).senders(SenderKind.OPERATOR).stream().limit(2)
                .forEach(s -> threads.add(thread(s.name(), "x")));
        assertEquals(1, count(with(bare(), people(List.of(), List.of(), threads)), "S-ONE"));
        assertEquals(0, count(with(bare(), people(List.of(), List.of(), threads.subList(0, 1))), "S-ONE"));
    }

    @Test
    void photoMarkerInABodyIsReported() {
        assertTrue(count(with(bare(), people(one(), List.of(), List.of(thread(number(1), "look [photo]")))), "M-RAW") > 0);
        assertEquals(0, count(with(bare(), people(one(), List.of(), List.of(thread(number(1), "look at this photo")))), "M-RAW"));
    }

    private static List<Call> tenCalls(boolean exact) {
        List<Call> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Instant at = NOW.minus(Duration.ofDays(i + 1)).minusSeconds(3_600L * i);
            calls.add(new Call(number(1), exact ? at : at.minusSeconds(13).minusMillis(250 + i), 60, CallType.INCOMING));
        }
        return calls;
    }

    @Test
    void exactMinutesAreReported() {
        assertTrue(count(with(bare(), people(one(), tenCalls(true), List.of())), "G-CLOCK") > 0);
    }

    @Test
    void millisecondsPass() {
        assertEquals(0, count(with(bare(), people(one(), tenCalls(false), List.of())), "G-CLOCK"));
    }

    @Test
    void noUnansweredOutgoingIsReported() {
        assertEquals(1, count(with(bare(), people(one(), outgoing(20, 45), List.of())), "C-CALLS"));
        List<Call> oneUnanswered = new ArrayList<>(outgoing(19, 45));
        oneUnanswered.addAll(outgoing(1, 0));
        assertEquals(0, count(with(bare(), people(one(), oneUnanswered, List.of())), "C-CALLS"));
    }

    @Test
    void voicemailWithoutMissedCallIsReported() {
        Instant at = ago(6, 0);
        People alone = new People(bare().people().ownNumber(), one(), List.of(), List.of(), List.of(new Voicemail(number(1), at, 20)));
        assertEquals(1, count(with(bare(), alone), "C-CALLS"));
        People missed = new People(bare().people().ownNumber(), one(), List.of(new Call(number(1), at.minusSeconds(90), 0, CallType.MISSED)),
                List.of(), List.of(new Voicemail(number(1), at, 20)));
        assertEquals(0, count(with(bare(), missed), "C-CALLS"));
    }

    @Test
    void expiredCookieIsReported() {
        assertEquals(1, count(with(bare(), new BrowserPlan(List.of(), List.of(new Cookie("example.org", "id", NOW.minusSeconds(60))),
                List.of())), "E-STALE"));
        assertEquals(0, count(with(bare(), new BrowserPlan(List.of(), List.of(new Cookie("example.org", "id", NOW.plus(Duration.ofDays(30)))),
                List.of())), "E-STALE"));
    }

    /** The N-NUMBER reports of the bare plan with these contacts and the SIM's number as its own. */
    private static List<String> numberReports(String own, String contactNumber) {
        People people = new People(own, List.of(contact("c1", "Sam Example", contactNumber, Relation.FRIEND)), List.of(),
                List.of(), List.of());
        return planted(with(bare(), people)).only("N-NUMBER").stream().map(Violation::detail).toList();
    }

    @Test
    void invalidNumberIsReported() {
        String sim = bare().simNumber();
        assertEquals(List.of(), numberReports(sim, number(1)));
        assertEquals(1, numberReports(sim, "+3199123").size());
    }

    @Test
    void tollFreeNumberIsReported() {
        assertEquals(1, numberReports(bare().simNumber(), "+318001234").size());
    }

    @Test
    void everyViewOfANumberIsRead() {
        People people = new People(bare().simNumber(), List.of(),
                List.of(new Call("+3199001", ago(3, 0), 5, CallType.INCOMING)),
                List.of(thread("+3199002", "x"), thread("Bank", "x"), thread(number(1) + ";+3199003", "x")),
                List.of(new Voicemail("+3199004", ago(4, 0), 20)));
        assertEquals(4, count(with(bare(), people), "N-NUMBER"));
    }

    @Test
    void inventedOwnNumberIsReported() {
        assertEquals(1, numberReports(number(7), number(1)).size());
    }

    @Test
    void localBluetoothAddressIsReported() {
        assertEquals(1, count(with(bare(), new NetworkPlan(List.of(), List.of(new BluetoothPair("Speaker", "02:11:22:33:44:55", ago(9, 0))))),
                "H-HARDWARE"));
        assertEquals(0, count(with(bare(), new NetworkPlan(List.of(), List.of(new BluetoothPair("Speaker", "00:1A:7D:33:44:55", ago(9, 0))))),
                "H-HARDWARE"));
    }
}
