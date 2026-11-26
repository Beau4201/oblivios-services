// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.harness;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.check.Checks;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.plan.ContentPlan;
import org.oblivios.decoy.content.plan.ContentPlanner;
import org.oblivios.decoy.content.pool.PoolConversation;
import org.oblivios.decoy.content.pool.PoolRead;
import org.oblivios.decoy.content.pool.PoolReader;
import org.oblivios.decoy.content.pool.TestPool.AcceptAllJudge;
import org.oblivios.decoy.content.pool.TestPool.IdentityRewriter;
import org.oblivios.decoy.content.story.MonthCount;
import org.oblivios.decoy.content.story.VolumeCounts;
import org.oblivios.decoy.content.surface.DeviceCameraProfile;
import org.oblivios.decoy.content.surface.ServerProbe;

/**
 * Builds many plans and runs both checkers and the population rules over them, by persona class or with the inputs of
 * the audit ({@code decoy-generator-audit.md}). Seed {@code n} is {@code Seed.of(n, 7919 * n)} and takes class
 * {@code n % classes}; a plan refused with {@link IllegalArgumentException} counts as refused for its class. The rewriter
 * changes nothing and the judge accepts all, because the local model does not exist.
 */
public final class PlanHarness {
    public static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    /** The inputs of the audit: the owner, and the city and zone of each country the harness knows. */
    static final String OWNER = "Sanne de Vries";
    private static final Map<String, String[]> PLACES = Map.of("NL", new String[] {"utrecht", "Europe/Amsterdam"});
    private static final int EXAMPLES = 3;

    public record Config(List<PersonaClass> classes, int seeds, List<PoolConversation> pool, Instant now, boolean audit) {
        public Config {
            classes = List.copyOf(classes);
            pool = List.copyOf(pool);
        }
    }

    /**
     * {@code plansByRule}: per rule the plans that break it (a population rule: its violations). {@code classes}: per
     * class run, the plans built and refused. {@code examples}: up to three details per rule id, and the refusal
     * messages of a class under {@link #classKey}.
     */
    public record Report(Map<String, Integer> plansByRule, Map<PersonaClass, int[]> classes, int plans, int refused,
            Map<String, List<String>> examples) {
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Report r) || plans != r.plans || refused != r.refused || !plansByRule.equals(r.plansByRule)
                    || !examples.equals(r.examples) || !classes.keySet().equals(r.classes.keySet())) {
                return false;
            }
            return classes.entrySet().stream().allMatch(e -> Arrays.equals(e.getValue(), r.classes.get(e.getKey())));
        }

        @Override
        public int hashCode() {
            return Objects.hash(plansByRule, plans, refused, examples, classes.keySet());
        }
    }

    private PlanHarness() {}

    public static String classKey(PersonaClass c) {
        return "class " + c.name();
    }

    public static PersonaInputs inputs(String language, String country) {
        String[] place = PLACES.get(country);
        if (place == null) {
            throw new IllegalArgumentException("country: the harness has no city for " + country);
        }
        return new PersonaInputs(OWNER, place[0], country, ZoneId.of(place[1]), Locale.forLanguageTag(language + "-" + country));
    }

    /** 30 months ending 2026-10, each 90 camera photos, 10 screenshots and 8 saved images, as the audit. */
    static VolumeCounts counts() {
        List<MonthCount> months = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            months.add(new MonthCount(YearMonth.of(2026, 10).minusMonths(29 - i), 90, 10, 8, 1_000_000L));
        }
        return new VolumeCounts(months);
    }

    /** A 4080 x 3072 camera with GPS, as the audit; its quantisation table is a test value, not a measured one. */
    static DeviceCameraProfile camera() {
        int[] table = new int[64];
        for (int i = 0; i < 64; i++) {
            table[i] = 8 + i;
        }
        return new DeviceCameraProfile(4080, 3072, List.of(table), "PXL_\\d{8}_\\d{9}\\.jpg", "'PXL_'yyyyMMdd_HHmmssSSS'.jpg'", true);
    }

    public static Report run(Config c) {
        Map<String, Integer> byRule = new TreeMap<>();
        Map<PersonaClass, int[]> classes = new LinkedHashMap<>();
        Map<String, List<String>> examples = new TreeMap<>();
        // shortcut: the views of every plan stay in memory for the population rules; count values per plan instead
        // when a run of 10,000 plans passes the heap
        List<PhoneViews> views = new ArrayList<>();
        int plans = 0;
        int refused = 0;
        int year = c.now().atZone(ZoneOffset.UTC).getYear();
        for (int n = 0; n < c.seeds(); n++) {
            Seed seed = Seed.of(n, 7919L * n);
            PersonaClass cls = c.audit() ? null : c.classes().get(n % c.classes().size());
            PersonaProfile profile = cls == null ? PersonaProfile.open(inputs("nl", "NL"))
                    : cls.profile(inputs(cls.language(), cls.country()), seed.stream("harness.class"), year);
            int[] count = cls == null ? null : classes.computeIfAbsent(cls, k -> new int[2]);
            ContentPlan p;
            try {
                p = ContentPlanner.build(profile, counts(), List.of(), List.of(), "AndroidWifi", camera(), ServerProbe.HONOURED, c.now(), seed,
                        c.pool(), new IdentityRewriter(), new AcceptAllJudge());
            } catch (IllegalArgumentException e) {
                refused++;
                if (count != null) {
                    count[1]++;
                    add(examples, classKey(cls), e.getMessage());
                }
                continue;
            }
            plans++;
            if (count != null) {
                count[0]++;
            }
            Set<String> seen = new HashSet<>();
            for (Violation v : Checks.all(p)) {
                if (seen.add(v.ruleId())) {
                    byRule.merge(v.ruleId(), 1, Integer::sum);
                }
                add(examples, v.ruleId(), v.detail());
            }
            views.add(PhoneViews.of(p));
        }
        for (Violation v : Checks.population(views, Set.of())) {
            byRule.merge(v.ruleId(), 1, Integer::sum);
            add(examples, v.ruleId(), v.detail());
        }
        return new Report(byRule, classes, plans, refused, examples);
    }

    private static void add(Map<String, List<String>> examples, String key, String text) {
        List<String> list = examples.computeIfAbsent(key, k -> new ArrayList<>());
        if (list.size() < EXAMPLES) {
            list.add(text);
        }
    }

    /** {@code --seeds <n> --pool <file> --out <tsv> [--audit] [--language <tag>] [--country <cc>]}. */
    public static void main(String[] args) throws IOException {
        Map<String, String> a = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--audit")) {
                a.put("audit", "true");
            } else if (args[i].startsWith("--") && i + 1 < args.length) {
                a.put(args[i].substring(2), args[++i]);
            } else {
                throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        for (String need : List.of("seeds", "pool", "out")) {
            if (!a.containsKey(need)) {
                throw new IllegalArgumentException("--" + need + " is required");
            }
        }
        PoolRead read;
        try (Reader in = Files.newBufferedReader(Path.of(a.get("pool")), StandardCharsets.UTF_8)) {
            read = PoolReader.read(in);
        }
        String language = a.getOrDefault("language", "nl");
        String country = a.getOrDefault("country", "NL");
        Config c = new Config(PersonaClass.all(language, country), Integer.parseInt(a.get("seeds")), read.conversations(), NOW,
                a.containsKey("audit"));
        Report r = run(c);
        Path out = Path.of(a.get("out"));
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        write(r, out);
        System.out.println("pool: " + read.conversations().size() + " conversations, " + read.errors().size() + " errors; plans: " + r.plans()
                + ", refused: " + r.refused() + "; written to " + out);
    }

    /** One line per rule (every rule, 0 included), a blank line, then one line per class. */
    static void write(Report r, Path out) {
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("rule\tplans\texample\n");
            for (String id : new java.util.TreeSet<>(Checks.ids())) {
                List<String> ex = r.examples().getOrDefault(id, List.of());
                w.write(id + "\t" + r.plansByRule().getOrDefault(id, 0) + "\t" + (ex.isEmpty() ? "" : clean(ex.get(0))) + "\n");
            }
            w.write("\nclass\tplans\trefused\treason\n");
            for (Map.Entry<PersonaClass, int[]> e : r.classes().entrySet()) {
                List<String> why = r.examples().getOrDefault(classKey(e.getKey()), List.of());
                w.write(e.getKey().name() + "\t" + e.getValue()[0] + "\t" + e.getValue()[1] + "\t" + (why.isEmpty() ? "" : clean(why.get(0))) + "\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ');
    }
}
