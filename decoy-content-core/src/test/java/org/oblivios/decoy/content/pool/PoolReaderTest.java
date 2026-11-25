// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.persona.PersonaProfile.Fact;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.style.StyleVector.Register;
import org.oblivios.decoy.content.surface.Relation;

class PoolReaderTest {

    private static final String ONE_TO_ONE = "#conv id=%s sit=plan-a-drink lang=en group=- rel=-/FRIEND age=25-34/25-34 "
            + "gender=FEMALE/MALE reg=CASUAL/STREET topics=drinks,weekend requires=CAR,DOG mix=-";

    /** A one-to-one conversation of {@code n} messages, A and B in turn, one minute apart on day 0. */
    private static String oneToOne(String id, int n) {
        StringBuilder sb = new StringBuilder(String.format(ONE_TO_ONE, id)).append('\n');
        for (int i = 0; i < n; i++) {
            sb.append(String.format("0 %02d:%02d %s %s%n", 10 + i / 60, i % 60, i % 2 == 0 ? "A" : "B",
                    i == 0 ? "drinks at {PLACE_CAFE} on {DAY}?" : "message " + i));
        }
        return sb.toString();
    }

    /** A group conversation of {@code sides} sides and {@code n} messages, written in turn by every side. */
    private static String group(String id, int sides, int n, String kind) {
        StringBuilder rel = new StringBuilder("-");
        StringBuilder age = new StringBuilder("30-39");
        StringBuilder gender = new StringBuilder("FEMALE");
        StringBuilder reg = new StringBuilder("CASUAL");
        for (int i = 1; i < sides; i++) {
            rel.append("/FRIEND");
            age.append("/30-39");
            gender.append(i % 2 == 0 ? "/FEMALE" : "/MALE");
            reg.append("/CASUAL");
        }
        StringBuilder sb = new StringBuilder("#conv id=" + id + " sit=friends-weekend lang=nl group=" + kind + " rel=" + rel + " age=" + age
                + " gender=" + gender + " reg=" + reg + " topics=weekend requires=- mix=-\n");
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%d %02d:%02d %c bericht %d%n", i / 20, 9 + (i % 20) / 4, (i % 4) * 10, (char) ('A' + i % sides), i));
        }
        return sb.toString();
    }

    private static PoolRead read(String text) {
        return PoolReader.read(new StringReader(text));
    }

    private static void assertRejected(String text, String reason) {
        PoolRead r = read(text);
        assertTrue(r.conversations().isEmpty(), "accepted: " + reason);
        assertFalse(r.errors().isEmpty(), "no error for: " + reason);
    }

    @Test
    void readsAWellFormedConversation() {
        PoolRead r = read(oneToOne("en-1-1", 15));
        assertEquals(List.of(), r.errors());
        PoolConversation c = r.conversations().get(0);
        assertEquals("en-1-1", c.id());
        assertEquals("plan-a-drink", c.situation());
        assertEquals("en", c.language());
        assertNull(c.group());
        assertFalse(c.isGroup());
        assertEquals(2, c.sides().size());
        PoolSide a = c.sides().get(0);
        PoolSide b = c.sides().get(1);
        assertEquals('A', a.letter());
        assertNull(a.relation());
        assertEquals(new AgeBand(25, 34), a.age());
        assertEquals(Gender.FEMALE, a.gender());
        assertEquals(Register.CASUAL, a.register());
        assertEquals('B', b.letter());
        assertEquals(Relation.FRIEND, b.relation());
        assertEquals(Gender.MALE, b.gender());
        assertEquals(Register.STREET, b.register());
        assertEquals(List.of("drinks", "weekend"), c.topics());
        assertEquals(Set.of(Fact.CAR, Fact.DOG), c.requires());
        assertEquals("", c.mix());
        assertEquals(15, c.messages().size());
        assertEquals(new PoolMessage(0, 600, 'A', "drinks at {PLACE_CAFE} on {DAY}?"), c.messages().get(0));
        assertEquals(new PoolMessage(0, 601, 'B', "message 1"), c.messages().get(1));
    }

    @Test
    void malformedConversationIsSkippedWithItsLine() {
        String bad = oneToOne("en-1-2", 15).replace("message 3", "see {FOO} there");
        PoolRead r = read(oneToOne("en-1-1", 15) + bad + oneToOne("en-1-3", 15));
        assertEquals(List.of("en-1-1", "en-1-3"), r.conversations().stream().map(PoolConversation::id).toList());
        assertEquals(1, r.errors().size(), r.errors().toString());
        String e = r.errors().get(0);
        assertTrue(e.startsWith("line "), e);
        assertTrue(e.contains("{FOO}"), e);
        assertTrue(e.startsWith("line 21:"), "the message line of the second block: " + e);
    }

    @Test
    void bomAndCrlfAreAccepted() {
        PoolRead r = read("\uFEFF" + oneToOne("en-1-1", 15).replace("\n", "\r\n"));
        assertEquals(List.of(), r.errors());
        assertEquals(1, r.conversations().size());
        assertFalse(r.conversations().get(0).messages().get(1).text().endsWith("\r"));
    }

    @Test
    void messageCountOutsideFifteenToFortyIsRejected() {
        assertRejected(oneToOne("en-1-1", 14), "14 messages");
        assertRejected(oneToOne("en-1-1", 41), "41 messages");
        assertEquals(1, read(oneToOne("en-1-1", 40)).conversations().size());
    }

    @Test
    void phoneLikeDigitsUrlsAndAddressesAreRejected() {
        assertRejected(oneToOne("x", 15).replace("message 3", "call 0612345678"), "phone number");
        assertRejected(oneToOne("x", 15).replace("message 3", "see http://example.org"), "url");
        assertRejected(oneToOne("x", 15).replace("message 3", "see www.example.org"), "www");
        assertRejected(oneToOne("x", 15).replace("message 3", "mail me at sam@example"), "address");
        assertEquals(1, read(oneToOne("x", 15).replace("message 3", "at 12345 and @ 5")).conversations().size());
    }

    @Test
    void timesMustNotGoBack() {
        assertRejected(oneToOne("x", 15).replace("0 10:05 B message 5", "0 09:05 B message 5"), "time back");
        assertRejected(oneToOne("x", 15).replace("0 10:05 B message 5", "3 10:05 B message 5"), "day 3 in a one-to-one");
    }

    @Test
    void unknownLanguageIsRejected() {
        assertRejected(oneToOne("x", 15).replace("lang=en", "lang=de"), "de");
    }

    @Test
    void bothSidesOfAOneToOneWrite() {
        assertRejected(oneToOne("x", 15).replaceAll("(?m)^(0 \\d\\d:\\d\\d) B ", "$1 A "), "only A writes");
    }

    @Test
    void tabsAndEmptyOrLongTextsAreRejected() {
        assertRejected(oneToOne("x", 15).replace("message 3", "a\tb"), "tab");
        assertRejected(oneToOne("x", 15).replace("message 3", "x".repeat(501)), "501 characters");
        assertRejected(oneToOne("x", 15).replace(" B message 3", " B"), "no text");
    }

    @Test
    void readsAGroupConversation() {
        PoolRead r = read(group("nl-1-1", 5, 30, "FRIENDS"));
        assertEquals(List.of(), r.errors());
        PoolConversation c = r.conversations().get(0);
        assertTrue(c.isGroup());
        assertEquals(GroupKind.FRIENDS, c.group());
        assertEquals(5, c.sides().size());
        for (int i = 0; i < 5; i++) {
            PoolSide s = c.sides().get(i);
            assertEquals((char) ('A' + i), s.letter());
            assertEquals(i == 0 ? null : Relation.FRIEND, s.relation());
            assertEquals(new AgeBand(30, 39), s.age());
            assertEquals(i == 0 || i % 2 == 0 ? Gender.FEMALE : Gender.MALE, s.gender());
        }
        assertEquals(Set.of(), c.requires());
    }

    @Test
    void groupWithMoreThanEightSidesIsRejected() {
        assertRejected(group("x", 9, 30, "FRIENDS"), "nine sides");
        assertEquals(1, read(group("x", 8, 30, "FRIENDS")).conversations().size());
    }

    @Test
    void oneToOneOverFortyIsRejectedAndGroupUpToSixtyIsAccepted() {
        assertRejected(oneToOne("x", 41), "41 one-to-one");
        assertEquals(1, read(group("x", 4, 60, "FAMILY")).conversations().size());
        assertRejected(group("x", 4, 61, "FAMILY"), "61 in a group");
    }

    @Test
    void groupNeedsTheOwnerAndTwoOthersWriting() {
        String onlyTwo = group("x", 4, 30, "FRIENDS").replaceAll("(?m)^(\\d \\d\\d:\\d\\d) [CD] ", "$1 B ");
        assertRejected(onlyTwo, "only A and B write");
    }

    @Test
    void groupKindOnlyWithMoreThanTwoSides() {
        assertRejected(oneToOne("x", 15).replace("group=-", "group=FRIENDS"), "a group kind on two sides");
        assertRejected(group("x", 4, 30, "FRIENDS").replace("group=FRIENDS", "group=-"), "three or more sides without a kind");
        assertRejected(group("x", 4, 30, "FRIENDS").replace("group=FRIENDS", "group=PARTY"), "unknown kind");
    }

    @Test
    void listLengthsMustMatchTheSides() {
        assertRejected(oneToOne("x", 15).replace("age=25-34/25-34", "age=25-34"), "one age for two sides");
        assertRejected(oneToOne("x", 15).replace("reg=CASUAL/STREET", "reg=CASUAL/STREET/CASUAL"), "three registers");
        assertRejected(oneToOne("x", 15).replace("rel=-/FRIEND", "rel=FRIEND/FRIEND"), "a relation for the persona");
        assertRejected(oneToOne("x", 15).replace("0 10:05 B", "0 10:05 C"), "a message of a side that does not exist");
    }

    @Test
    void brokenHeaderIsRejected() {
        assertRejected(oneToOne("x", 15).replace(" mix=-", ""), "missing key");
        assertRejected(oneToOne("x", 15).replace(" mix=-", " mix=- colour=red"), "unknown key");
        assertRejected(oneToOne("x", 15).replace("sit=plan-a-drink", "sit=Plan_A"), "situation id");
        assertRejected(oneToOne("x", 15).replace("requires=CAR,DOG", "requires=CAR,UNICORN"), "unknown fact");
    }
}
