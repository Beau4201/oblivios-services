// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.pool.gate;

import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.oblivios.decoy.content.pool.PoolConversation;
import org.oblivios.decoy.content.pool.PoolMessage;
import org.oblivios.decoy.content.pool.PoolRead;
import org.oblivios.decoy.content.pool.PoolReader;

/**
 * Reads every {@code *.pool} file of a folder, drops what fails a gate, writes the rest to one pool file and reports
 * the count per gate and per situation and language. The run fails (status 1) when a situation of the taxonomy has
 * fewer conversations than asked in any language that was read, so a language whose conversations all fail is still
 * short. The rejected ids go before the near duplicates are sought, so that a rejected conversation takes no good
 * twin with it.
 */
public final class PoolGate {
    private static final double DUPLICATE = 0.5;
    private static final String USAGE = "PoolGate <in-dir> <out-file> --taxonomy <file> --min <k> [--deny <names-file>]"
            + " [--reject <ids-file>] [--sample <n> <sample-file>]";

    private PoolGate() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    private record Options(Path in, Path out, Path taxonomy, int min, Path deny, Path reject, int sample, Path sampleFile) {}

    static int run(String[] args, PrintStream out, PrintStream err) {
        Options o;
        try {
            o = options(args);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            err.println(e.getMessage() + "\n" + USAGE);
            return 2;
        }
        try {
            return gate(o, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Options options(String[] args) {
        List<String> plain = new ArrayList<>();
        Path taxonomy = null;
        Path deny = null;
        Path reject = null;
        Path sampleFile = null;
        int sample = 0;
        Integer min = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--taxonomy" -> taxonomy = Path.of(args[++i]);
                case "--min" -> min = Integer.valueOf(args[++i]);
                case "--deny" -> deny = Path.of(args[++i]);
                case "--reject" -> reject = Path.of(args[++i]);
                case "--sample" -> {
                    sample = Integer.parseInt(args[++i]);
                    sampleFile = Path.of(args[++i]);
                }
                default -> {
                    if (args[i].startsWith("--")) {
                        throw new IllegalArgumentException("unknown option " + args[i]);
                    }
                    plain.add(args[i]);
                }
            }
        }
        if (plain.size() != 2 || taxonomy == null || min == null) {
            throw new IllegalArgumentException("an input folder, an output file, --taxonomy and --min are needed");
        }
        return new Options(Path.of(plain.get(0)), Path.of(plain.get(1)), taxonomy, min, deny, reject, sample, sampleFile);
    }

    private static int gate(Options o, PrintStream out) throws IOException {
        Set<String> situations = situations(o.taxonomy());
        Map<String, Integer> dropped = new LinkedHashMap<>();
        Stream.of("schema", "situation", "language", "entities", "denied", "duplicates", "rejected").forEach(g -> dropped.put(g, 0));

        List<PoolConversation> kept = read(o.in(), dropped, out);
        Set<String> languages = new TreeSet<>();
        kept.forEach(c -> languages.add(c.language()));

        kept = filter(kept, "situation", dropped, out, c -> situations.contains(c.situation()) ? null : "unknown situation " + c.situation());
        kept = filter(kept, "language", dropped, out,
                c -> LanguageCheck.isLanguage(c, c.language()) ? null : "does not read as " + c.language());
        kept = filter(kept, "entities", dropped, out, c -> {
            List<String> found = EntityScan.findings(c);
            return found.isEmpty() ? null : found.get(0);
        });
        Set<String> deny = o.deny() == null ? Set.of() : denied(o.deny());
        int longest = Words.longest(deny);
        kept = filter(kept, "denied", dropped, out, c -> c.messages().stream()
                .flatMap(m -> Words.found(Words.of(m.text().toLowerCase(Locale.ROOT)), deny, longest).stream())
                .findFirst().orElse(null));
        Set<String> rejected = o.reject() == null ? Set.of() : firstWords(o.reject());
        kept = filter(kept, "rejected", dropped, out, c -> rejected.contains(c.id()) ? "rejected by the judge" : null);
        List<PoolConversation> unique = NearDuplicates.dedupe(kept, DUPLICATE);
        Set<String> uniqueIds = unique.stream().map(PoolConversation::id).collect(Collectors.toSet());
        kept = filter(kept, "duplicates", dropped, out, c -> uniqueIds.contains(c.id()) ? null : "near duplicate");

        Files.writeString(o.out(), write(kept), StandardCharsets.UTF_8);
        dropped.forEach((gate, n) -> out.println(gate + " " + n));
        out.println("kept " + kept.size());
        if (o.sampleFile() != null) {
            List<PoolConversation> shuffled = new ArrayList<>(kept);
            Collections.shuffle(shuffled, new SecureRandom()); // a sample the writer of the pool cannot foresee
            Files.writeString(o.sampleFile(), write(shuffled.subList(0, Math.min(o.sample(), shuffled.size()))), StandardCharsets.UTF_8);
        }
        return coverage(kept, situations, languages, o.min(), out);
    }

    private static String write(List<PoolConversation> conversations) {
        return conversations.stream().map(PoolWriter::write).collect(Collectors.joining("\n"));
    }

    private static List<PoolConversation> read(Path in, Map<String, Integer> dropped, PrintStream out) throws IOException {
        List<Path> files;
        try (Stream<Path> list = Files.list(in)) {
            files = list.filter(f -> f.getFileName().toString().endsWith(".pool")).sorted().toList();
        }
        List<PoolConversation> kept = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Path file : files) {
            // a model wraps its reply in a code fence now and then; the line stays, empty, so line numbers hold
            String text = Files.readString(file, StandardCharsets.UTF_8).replace(String.valueOf((char) 0xFEFF), "")
                    .replaceAll("(?m)^[ \\t]*```.*$", "");
            PoolRead read = PoolReader.read(new StringReader(text));
            for (String error : read.errors()) {
                drop("schema", file.getFileName() + " " + error, dropped, out);
            }
            for (PoolConversation c : read.conversations()) {
                if (ids.add(c.id())) {
                    kept.add(c);
                } else {
                    drop("schema", file.getFileName() + " " + c.id() + ": an id used twice", dropped, out);
                }
            }
        }
        return kept;
    }

    /** Keeps each conversation for which {@code reason} gives null. */
    private static List<PoolConversation> filter(List<PoolConversation> in, String gate, Map<String, Integer> dropped, PrintStream out,
            Function<PoolConversation, String> reason) {
        List<PoolConversation> kept = new ArrayList<>();
        for (PoolConversation c : in) {
            String why = reason.apply(c);
            if (why == null) {
                kept.add(c);
            } else {
                drop(gate, c.id() + " (" + c.situation() + "): " + why, dropped, out);
            }
        }
        return kept;
    }

    private static void drop(String gate, String what, Map<String, Integer> dropped, PrintStream out) {
        dropped.merge(gate, 1, Integer::sum);
        out.println("dropped " + gate + " " + what);
    }

    private static int coverage(List<PoolConversation> kept, Set<String> situations, Set<String> languages, int min, PrintStream out) {
        Map<String, Integer> count = new HashMap<>();
        kept.forEach(c -> count.merge(c.situation() + " " + c.language(), 1, Integer::sum));
        int status = 0;
        if (languages.isEmpty()) {
            out.println("short: no conversation was read");
            status = 1;
        }
        for (String situation : situations) {
            for (String language : languages) {
                int n = count.getOrDefault(situation + " " + language, 0);
                out.println(situation + " " + language + " " + n);
                if (n < min) {
                    out.println("short: " + situation + " " + language + " " + n);
                    status = 1;
                }
            }
        }
        return status;
    }

    /** The ids of the taxonomy, in its order: the first column of each line that is no comment. */
    private static Set<String> situations(Path taxonomy) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        for (String line : lines(taxonomy)) {
            String id = line.split("\t", 2)[0].trim();
            if (!out.add(id)) {
                throw new IOException("the taxonomy names " + id + " twice");
            }
        }
        return out;
    }

    /** The denied names as their words, lower case, one space apart. */
    private static Set<String> denied(Path file) throws IOException {
        return lines(file).stream().map(l -> String.join(" ", Words.of(l.toLowerCase(Locale.ROOT)))).filter(l -> !l.isEmpty())
                .collect(Collectors.toSet());
    }

    private static Set<String> firstWords(Path file) throws IOException {
        return lines(file).stream().map(l -> l.trim().split("\\s+", 2)[0]).collect(Collectors.toSet());
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
    }
}
