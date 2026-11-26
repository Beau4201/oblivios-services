// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.pool.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.pool.PoolConversation;
import org.oblivios.decoy.content.pool.PoolMessage;
import org.oblivios.decoy.content.pool.PoolRead;
import org.oblivios.decoy.content.pool.PoolReader;

class PoolGateTest {
    @TempDir
    Path dir;

    private record Result(int status, String out, List<PoolConversation> kept) {
        long count(String gate) {
            return out.lines().filter(l -> l.startsWith(gate + " ")).map(l -> Long.parseLong(l.substring(gate.length() + 1))).findFirst()
                    .orElseThrow(() -> new AssertionError("no line for gate " + gate + " in:\n" + out));
        }

        List<String> ids() {
            return kept.stream().map(PoolConversation::id).toList();
        }
    }

    /** A sentence of the language: three of its stop words and three made-up words, so no two sentences are alike. */
    private static String sentence(String lang, Random r) {
        List<String> stop = LanguagePack.of(lang).list("stopwords");
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            words.add(stop.get(r.nextInt(stop.size())));
            StringBuilder made = new StringBuilder("q");
            for (int k = 0; k < 6; k++) {
                made.append("abcdefgijklmnopqrstuvxyz".charAt(r.nextInt(24))); // no h: "http" would be a link
            }
            words.add(made.toString());
        }
        return String.join(" ", words);
    }

    private static List<String> texts(String lang, long seed) {
        Random r = new Random(seed);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            out.add(sentence(lang, r));
        }
        return out;
    }

    private static String block(String id, String situation, String lang, List<String> texts) {
        StringBuilder sb = new StringBuilder("#conv id=" + id + " sit=" + situation + " lang=" + lang
                + " group=- rel=-/FRIEND age=20-30/20-30 gender=FEMALE/MALE reg=CASUAL/CASUAL topics=weekend requires=- mix=-\n");
        for (int i = 0; i < texts.size(); i++) {
            sb.append("0 ").append(String.format("%02d:%02d", 9 + i / 4, (i % 4) * 10)).append(i % 2 == 0 ? " A " : " B ")
                    .append(texts.get(i)).append('\n');
        }
        return sb.toString();
    }

    private static String good(String id, long seed) {
        return block(id, "weekend-plans", "nl", texts("nl", seed));
    }

    /** The conversation of the seed with one message replaced. */
    private static String withLine(String id, long seed, String text) {
        List<String> t = texts("nl", seed);
        t.set(4, text);
        return block(id, "weekend-plans", "nl", t);
    }

    private Result gate(String pool, String... more) throws IOException {
        return gate(pool, List.of("weekend-plans"), 1, more);
    }

    private Result gate(String pool, List<String> situations, int min, String... more) throws IOException {
        Path in = Files.createDirectories(dir.resolve("in"));
        Files.writeString(in.resolve("batch-0001.pool"), pool);
        StringBuilder taxonomy = new StringBuilder("# id kind stages requires topics min\n");
        situations.forEach(s -> taxonomy.append(s).append("\tFRIEND\tANY\t-\tweekend\t3\n"));
        Files.writeString(dir.resolve("situations.tsv"), taxonomy);
        Path out = dir.resolve("out.pool");
        List<String> args = new ArrayList<>(List.of(in.toString(), out.toString(), "--taxonomy",
                dir.resolve("situations.tsv").toString(), "--min", String.valueOf(min)));
        args.addAll(List.of(more));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int status = PoolGate.run(args.toArray(String[]::new), print, print);
        List<PoolConversation> kept = List.of();
        if (Files.exists(out)) {
            try (Reader r = Files.newBufferedReader(out)) {
                PoolRead read = PoolReader.read(r);
                assertTrue(read.errors().isEmpty(), "the written pool does not read back: " + read.errors());
                kept = read.conversations();
            }
        }
        return new Result(status, bytes.toString(StandardCharsets.UTF_8), kept);
    }

    @Test
    void schemaFailuresAreDropped() throws IOException {
        Result r = gate(good("ok", 1) + withLine("slot", 2, "see {FOO}") + good("ok", 3));
        assertEquals(List.of("ok"), r.ids());
        assertEquals(2, r.count("schema"), "one broken conversation and one id used twice\n" + r.out());
    }

    @Test
    void codeFencesAroundABatchAreIgnored() throws IOException {
        Result r = gate("```\n" + good("a", 1) + good("b", 2) + "```\n");
        assertEquals(List.of("a", "b"), r.ids());
        assertEquals(0, r.count("schema"), r.out());
    }

    @Test
    void theWrittenPoolKeepsEveryLabel() throws IOException {
        String group = "#conv id=g sit=weekend-plans lang=nl group=FRIENDS rel=-/FRIEND/BEST_FRIEND age=20-30/25-35/20-22 "
                + "gender=FEMALE/MALE/FEMALE reg=CASUAL/STREET/NEUTRAL topics=weekend,food requires=DOG,CAR mix=en\n";
        StringBuilder sb = new StringBuilder(group);
        List<String> t = texts("nl", 9);
        for (int i = 0; i < t.size(); i++) {
            sb.append("1 ").append(String.format("%02d:%02d", 9 + i / 4, (i % 4) * 10)).append(' ').append((char) ('A' + i % 3))
                    .append(' ').append(t.get(i)).append('\n');
        }
        Result r = gate(sb.toString());
        try (Reader in = new java.io.StringReader(sb.toString())) {
            assertEquals(PoolReader.read(in).conversations(), r.kept());
        }
    }

    @Test
    void wrongLanguageIsDropped() throws IOException {
        Result r = gate(good("ok", 1) + block("english", "weekend-plans", "nl", texts("en", 2)));
        assertEquals(List.of("ok"), r.ids());
        assertEquals(1, r.count("language"), r.out());
    }

    @Test
    void nearDuplicateIsDropped() throws IOException {
        List<String> t = texts("nl", 5);
        List<String> almost = new ArrayList<>(t);
        almost.set(7, almost.get(7).replaceFirst("q\\p{L}+", "qanders"));
        Result r = gate(block("first", "weekend-plans", "nl", t) + block("second", "weekend-plans", "nl", almost));
        assertEquals(List.of("first"), r.ids());
        assertEquals(1, r.count("duplicates"), r.out());
    }

    @Test
    void distinctConversationsAreKept() throws IOException {
        Result r = gate(good("a", 1) + good("b", 2) + good("c", 3));
        assertEquals(List.of("a", "b", "c"), r.ids());
        assertEquals(0, r.count("duplicates"), r.out());
    }

    @Test
    void nameOfAPackOutsideASlotIsDropped() throws IOException {
        Result r = gate(good("ok", 1) + withLine("given", 2, "heb je Sanne al gesproken")
                + withLine("family", 3, "die van De Jong is er ook") + withLine("slot", 4, "heb je {NAME_B} al gesproken"));
        assertEquals(List.of("ok", "slot"), r.ids());
        assertEquals(2, r.count("entities"), r.out());
    }

    @Test
    void aWordThatIsAlsoANameIsNoName() throws IOException {
        // two names of the neutral pack are stop words of the language, and family names are ordinary words in lower case
        Result r = gate(withLine("a", 1, "Dan zie ik je daar") + withLine("b", 2, "Ben je er al bij de bakker"));
        assertEquals(List.of("a", "b"), r.ids(), r.out());
    }

    @Test
    void aFamilyNameThatStartsASentenceIsLeftToTheJudge() throws IOException {
        // the Dutch round of 2026-11-26 lost 8 conversations this way: "Lang geleden" is "long ago", "Bakker" the shop
        Result r = gate(withLine("start", 1, "Lang. Veel vergaderd") + withLine("after", 2, "Hoe is het met je? Lang geleden")
                + withLine("shop", 3, "Bakker had er nog maar 4") + withLine("inside", 4, "ik heb Bakker gisteren gesproken")
                + withLine("given", 5, "Sanne komt ook") + withLine("again", 6, "Lang geleden dat ik Lang zag")
                + withLine("next", 7, "Lang verhaal. Ik zag Lang daar"));
        assertEquals(List.of("start", "after", "shop"), r.ids(), r.out());
        assertEquals(4, r.count("entities"), r.out());
    }

    @Test
    void contactDataIsAnEntity() throws IOException {
        // the reader refuses these as well; the scan holds them for a conversation that did not come through the reader
        PoolConversation good;
        try (Reader in = new java.io.StringReader(good("ok", 2))) {
            good = PoolReader.read(in).conversations().get(0);
        }
        assertEquals(List.of(), EntityScan.findings(good));
        for (String reach : List.of("bel 0612345678 maar", "zie www.example", "mail me@example")) {
            List<PoolMessage> messages = new ArrayList<>(good.messages());
            PoolMessage m = messages.get(0);
            messages.set(0, new PoolMessage(m.day(), m.minute(), m.side(), reach));
            PoolConversation c = new PoolConversation(good.id(), good.situation(), good.language(), good.group(), good.sides(),
                    good.topics(), good.requires(), good.mix(), messages);
            assertEquals(1, EntityScan.findings(c).size(), reach);
        }
    }

    @Test
    void rejectListDropsItsIds() throws IOException {
        Path reject = Files.writeString(dir.resolve("reject.txt"), "b does not read as real\n\nzz not in the pool\n");
        Result r = gate(good("a", 1) + good("b", 2), "--reject", reject.toString());
        assertEquals(List.of("a"), r.ids());
        assertEquals(1, r.count("rejected"), r.out());
    }

    @Test
    void aRejectedConversationDoesNotTakeItsNearDuplicateWithIt() throws IOException {
        Path reject = Files.writeString(dir.resolve("reject.txt"), "first reads as false\n");
        Result r = gate(block("first", "weekend-plans", "nl", texts("nl", 5)) + block("second", "weekend-plans", "nl", texts("nl", 5)),
                "--reject", reject.toString());
        assertEquals(List.of("second"), r.ids(), r.out());
    }

    @Test
    void sampleHasTheRequestedSizeAndDiffersPerRun() throws IOException {
        StringBuilder pool = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            pool.append(good("c" + i, i));
        }
        List<List<String>> samples = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            Path sample = dir.resolve("sample-" + run + ".txt");
            gate(pool.toString(), "--sample", "5", sample.toString());
            try (Reader in = Files.newBufferedReader(sample)) {
                PoolRead read = PoolReader.read(in);
                assertTrue(read.errors().isEmpty(), read.errors().toString());
                assertEquals(5, read.conversations().size());
                samples.add(read.conversations().stream().map(PoolConversation::id).toList());
            }
        }
        assertNotEquals(samples.get(0), samples.get(1));
    }

    @Test
    void tenThousandConversationsPassInUnderAMinute() {
        StringBuilder pool = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            pool.append(good("c" + i, i));
        }
        assertTimeout(Duration.ofSeconds(60), () -> {
            Result r = gate(pool.toString());
            assertEquals(10_000, r.kept().size(), () -> r.out().lines().filter(l -> l.startsWith("dropped ")).limit(5).toList().toString());
        });
    }

    @Test
    void unknownSituationIsDropped() throws IOException {
        Result r = gate(good("ok", 1) + block("odd", "moon-landing", "nl", texts("nl", 2)));
        assertEquals(List.of("ok"), r.ids());
        assertEquals(1, r.count("situation"), r.out());
    }

    @Test
    void deniedNameIsDropped() throws IOException {
        Path deny = Files.writeString(dir.resolve("deny.txt"), "# clubs\nQuietford Rovers\nAjax\n");
        Result r = gate(good("ok", 1) + withLine("club", 2, "gaan we naar quietford  rovers") + withLine("one", 3, "AJAX speelt")
                + withLine("inside", 4, "dat is ajaxachtig"), "--deny", deny.toString());
        assertEquals(List.of("ok", "inside"), r.ids(), r.out());
        assertEquals(2, r.count("denied"), r.out());
    }

    @Test
    void coverageBelowTheMinimumFailsTheRun() throws IOException {
        Result r = gate(good("a", 1) + good("b", 2), List.of("weekend-plans", "running-late"), 1);
        assertEquals(1, r.status(), r.out());
        assertTrue(r.out().contains("short: running-late nl 0"), r.out());
        assertEquals(List.of("a", "b"), r.ids(), "the pool is written all the same");
    }

    @Test
    void aLanguageWhoseConversationsAllFailIsStillShort() throws IOException {
        Result r = gate(good("a", 1) + block("lost", "weekend-plans", "en", texts("nl", 2)));
        assertEquals(1, r.status(), r.out());
        assertTrue(r.out().contains("short: weekend-plans en 0"), r.out());
    }

    @Test
    void anEmptyRunFails() throws IOException {
        assertEquals(1, gate("").status());
    }

    @Test
    void fullCoveragePasses() throws IOException {
        Result r = gate(good("a", 1) + good("b", 2), List.of("weekend-plans"), 2);
        assertEquals(0, r.status(), r.out());
        assertTrue(r.out().contains("weekend-plans nl 2"), r.out());
    }
}
