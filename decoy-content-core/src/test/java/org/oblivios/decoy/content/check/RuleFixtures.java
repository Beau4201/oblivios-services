// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.chronicle.Chronicle;
import org.oblivios.decoy.content.chronicle.Event;
import org.oblivios.decoy.content.chronicle.EventKind;
import org.oblivios.decoy.content.chronicle.Trace;
import org.oblivios.decoy.content.surface.Note;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.PhoneNumber;
import org.oblivios.decoy.content.data.NameData;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.country.SenderKind;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.persona.PersonaProfile.Fact;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.persona.PersonaProfile.Transport;
import org.oblivios.decoy.content.plan.ContentPlan;
import org.oblivios.decoy.content.plan.ContentPlanner;
import org.oblivios.decoy.content.pool.FilledConversation;
import org.oblivios.decoy.content.pool.TestPool;
import org.oblivios.decoy.content.pool.TestPool.AcceptAllJudge;
import org.oblivios.decoy.content.pool.TestPool.IdentityRewriter;
import org.oblivios.decoy.content.story.MonthCount;
import org.oblivios.decoy.content.story.Place;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.story.VolumeCounts;
import org.oblivios.decoy.content.style.StyleBook;
import org.oblivios.decoy.content.style.StyleVector.Register;
import org.oblivios.decoy.content.surface.AppSpec;
import org.oblivios.decoy.content.surface.AppsPlan;
import org.oblivios.decoy.content.surface.BluetoothPair;
import org.oblivios.decoy.content.surface.BrowserPlan;
import org.oblivios.decoy.content.surface.CalEvent;
import org.oblivios.decoy.content.surface.CalendarPlan;
import org.oblivios.decoy.content.surface.Call;
import org.oblivios.decoy.content.surface.CallType;
import org.oblivios.decoy.content.surface.Contact;
import org.oblivios.decoy.content.surface.Cookie;
import org.oblivios.decoy.content.surface.DeviceCameraProfile;
import org.oblivios.decoy.content.surface.MailMessage;
import org.oblivios.decoy.content.surface.MailPlan;
import org.oblivios.decoy.content.surface.MediaItem;
import org.oblivios.decoy.content.surface.MediaKind;
import org.oblivios.decoy.content.surface.MediaPlan;
import org.oblivios.decoy.content.surface.NetworkPlan;
import org.oblivios.decoy.content.surface.People;
import org.oblivios.decoy.content.surface.PhotoKind;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.surface.ServerProbe;
import org.oblivios.decoy.content.surface.SmsMessage;
import org.oblivios.decoy.content.surface.SmsThread;
import org.oblivios.decoy.content.surface.TracesPlan;
import org.oblivios.decoy.content.surface.TravelPlan;
import org.oblivios.decoy.content.surface.Trip;
import org.oblivios.decoy.content.surface.UsagePlan;
import org.oblivios.decoy.content.surface.VaultPlan;
import org.oblivios.decoy.content.surface.Visit;
import org.oblivios.decoy.content.surface.WifiNetwork;
import org.oblivios.decoy.content.world.World;
import org.oblivios.decoy.content.world.WorldPerson;
import org.oblivios.decoy.content.world.WorldPlace;
import org.oblivios.decoy.content.persona.PersonaProfile.TravelMode;

/**
 * One planted case per rule of {@link Checks}: a plan whose views hold nothing but the items that break the rule, so a
 * test can switch each rule off and see its case go unreported (acceptance 4 of the life story design).
 */
public final class RuleFixtures {
    public static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    public static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final Map<String, Supplier<Planted>> CASES = new LinkedHashMap<>();
    private static ContentPlan base;

    /** The inputs of every planted case; a case changes only the persona or the views. */
    public record Planted(List<PhoneViews> plans, Persona persona, PlanStory story, CountryPack pack, Instant now) {
        public Planted {
            plans = List.copyOf(plans);
        }

        public PhoneViews views() {
            return plans.get(0);
        }

        /** What all three checkers report with the rules of {@code off} switched off. */
        public List<Violation> report(Set<String> off) {
            List<Violation> out = new ArrayList<>(Checks.examine(views(), persona, pack, now, off));
            out.addAll(Checks.origins(views(), story, off));
            out.addAll(Checks.population(plans, off));
            return out;
        }

        /** What the one rule {@code id} reports. */
        public List<Violation> only(String id) {
            return report(allBut(id));
        }
    }

    private RuleFixtures() {}

    public static Set<String> ids() {
        return CASES.keySet();
    }

    public static Planted of(String id) {
        Supplier<Planted> c = CASES.get(id);
        if (c == null) {
            throw new IllegalArgumentException("no planted case for " + id);
        }
        return c.get();
    }

    /** Every rule id but {@code id}: the set to switch off so that only {@code id} runs. */
    public static Set<String> allBut(String id) {
        Set<String> off = new HashSet<>(Checks.ids());
        off.remove(id);
        return off;
    }

    // ---- building blocks ----

    public static PersonaInputs inputs() {
        return new PersonaInputs("Sam", "Example", "Utrecht", "NL", ZONE, Locale.forLanguageTag("nl-NL"));
    }

    /** A persona of the inputs with the given answers; every other answer is drawn from a fixed seed. */
    public static Persona persona(UnaryOperator<PersonaProfile.Builder> answers) {
        return org.oblivios.decoy.content.TestSetup.derive(Seed.of(11, 11), answers.apply(PersonaProfile.builder(inputs())).build(), 2026);
    }

    /** A planner plan of the inputs, built once: its timeline gives the places, the zone and the start of the phone. */
    public static synchronized ContentPlan base() {
        if (base == null) {
            List<MonthCount> months = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                months.add(new MonthCount(YearMonth.of(2026, 10).minusMonths(29 - i), 60 + i * 5, 10, 8, 1_000_000L));
            }
            int[] table = new int[64];
            for (int i = 0; i < 64; i++) {
                table[i] = 8 + i;
            }
            DeviceCameraProfile cam = new DeviceCameraProfile(4080, 3072, List.of(table), "PXL_\\d{8}_\\d{9}\\.jpg",
                    "'PXL_'yyyyMMdd_HHmmssSSS'.jpg'", true);
            base = ContentPlanner.build(PersonaProfile.open(inputs()), org.oblivios.decoy.content.TestSetup.inputs(new VolumeCounts(months), List.of(),
                    List.of(new AppSpec("org.example.chat", "chat.example.org", true)), "AndroidWifi", cam, ServerProbe.HONOURED, NOW),
                org.oblivios.decoy.content.review.ReviewEdits.none(), Seed.of(3, 3), TestPool.random(5000, Seed.of(3, 5000)), new IdentityRewriter(), new AcceptAllJudge());
        }
        return base;
    }

    /** The base plan with this persona and every view, the travel plan and the conversations empty. */
    public static ContentPlan bare(Persona persona) {
        ContentPlan b = base();
        return new ContentPlan(persona, b.world(), b.chronicle(), b.timeline(), new People(b.people().ownNumber(), List.of(), List.of(), List.of(), List.of()),
                new CalendarPlan(List.of(), List.of(), List.of()), new TravelPlan(List.of()), b.device(),
                new NetworkPlan(List.of(), List.of()), new UsagePlan(List.of(), Map.of()), new AppsPlan(List.of()),
                new BrowserPlan(List.of(), List.of(), List.of()), new VaultPlan(List.of()), new TracesPlan(List.of(), Map.of()),
                new MediaPlan(List.of(), List.of()), new MailPlan(List.of(), Optional.empty()), b.styles(), List.of(), List.of(), List.of(),
                b.dataVersions(), b.simNumber());
    }

    public static ContentPlan bare() {
        return bare(base().persona());
    }

    /** A copy of {@code p} with each part (found by its type) replacing the plan's own. */
    public static ContentPlan with(ContentPlan p, Object... parts) {
        Persona persona = p.persona();
        World world = p.world();
        People people = p.people();
        CalendarPlan calendar = p.calendar();
        TravelPlan travel = p.travel();
        NetworkPlan network = p.network();
        UsagePlan usage = p.usage();
        AppsPlan apps = p.apps();
        BrowserPlan browser = p.browser();
        TracesPlan traces = p.traces();
        MediaPlan media = p.media();
        MailPlan mail = p.mail();
        StyleBook styles = p.styles();
        Chronicle chronicle = p.chronicle();
        List<FilledConversation> conversations = p.conversations();
        for (Object part : parts) {
            if (part instanceof Persona x) {
                persona = x;
            } else if (part instanceof World x) {
                world = x;
            } else if (part instanceof People x) {
                people = x;
            } else if (part instanceof CalendarPlan x) {
                calendar = x;
            } else if (part instanceof TravelPlan x) {
                travel = x;
            } else if (part instanceof NetworkPlan x) {
                network = x;
            } else if (part instanceof UsagePlan x) {
                usage = x;
            } else if (part instanceof AppsPlan x) {
                apps = x;
            } else if (part instanceof BrowserPlan x) {
                browser = x;
            } else if (part instanceof TracesPlan x) {
                traces = x;
            } else if (part instanceof MediaPlan x) {
                media = x;
            } else if (part instanceof MailPlan x) {
                mail = x;
            } else if (part instanceof Chronicle x) {
                chronicle = x;
            } else if (part instanceof StyleBook x) {
                styles = x;
            } else if (part instanceof FilledConversation x) {
                conversations = List.of(x);
            } else {
                throw new IllegalArgumentException(part.getClass().getName());
            }
        }
        return new ContentPlan(persona, world, chronicle, p.timeline(), people, calendar, travel, p.device(), network, usage, apps, browser, p.vault(),
                traces, media, mail, styles, conversations, p.reserve(), p.warnings(), p.dataVersions(),
                p.simNumber());
    }

    /** The checker inputs of one plan. */
    public static Planted planted(ContentPlan p) {
        return new Planted(List.of(PhoneViews.of(p)), p.persona(), PlanStory.of(p), Checks.packOf(p.persona()), p.timeline().now());
    }

    /** The checker inputs of several plans; the first gives the persona and the story. */
    public static Planted planted(List<ContentPlan> plans) {
        ContentPlan first = plans.get(0);
        return new Planted(plans.stream().map(PhoneViews::of).toList(), first.persona(), PlanStory.of(first),
                Checks.packOf(first.persona()), first.timeline().now());
    }

    public static People people(List<Contact> contacts, List<Call> calls, List<SmsThread> threads) {
        return new People(base().people().ownNumber(), contacts, calls, threads, List.of());
    }

    public static Contact contact(String id, String name, String number, Relation relation) {
        return new Contact(id, name, number, false, null, null, null, PhotoKind.NONE, relation, 1990, Gender.FEMALE, null);
    }

    /** A number of the base plan's country, unique per index. */
    public static String number(int i) {
        return String.format(Locale.ROOT, "+3162578%04d", i);
    }

    /** An instant {@code days} before {@link #NOW} with seconds and milliseconds, as a real log has them. */
    public static Instant ago(int days, int i) {
        return NOW.minus(Duration.ofDays(days)).minusSeconds(3_600L * i + 17).minusMillis(123 + i);
    }

    public static SmsThread thread(String address, String body) {
        return new SmsThread(address, address, null, List.of(new SmsMessage(ago(3, 0), true, body, true, null)), List.of());
    }

    public static Place home(ContentPlan p) {
        return p.timeline().places().stream().filter(x -> x.kind() == PlaceKind.HOME).findFirst().orElseThrow();
    }

    public static String firstWord(String language, String key) {
        return LanguagePack.of(language).list(key).stream().filter(w -> !w.isEmpty()).findFirst().orElseThrow();
    }

    /** A person of the base world's language with a phone, born in 1990, saved as {@code label}. */
    public static WorldPerson person(String id, Relation relation, String given, String family, Gender gender, String label) {
        return new WorldPerson(id, relation, given, family, family, gender, LocalDate.of(1990, 6, 1), "NL", List.of(), null,
                false, null, new PhoneNumber(number(Integer.parseInt(id.substring(1))), PhoneNumber.NumberType.MOBILE), label,
                given, "Sam", 0, null, null, Origin.answer("CIRCLE"));
    }

    /** The bare plan whose world holds exactly these people. */
    public static Planted world(WorldPerson... people) {
        World b = base().world();
        return planted(with(bare(), new World(b.self(), List.of(people), b.pets(), b.places(), b.choices(), b.habits())));
    }

    /** A given name of the name data for this sex, born in 1990. */
    private static String given(Gender gender) {
        return NameData.load("NL").given(gender, 1990, Seed.of(5, 5).stream("fixture." + gender));
    }

    // ---- the cases ----

    static {
        // the world
        CASES.put("N-GENDER", () -> world(person("p1", Relation.FRIEND, given(Gender.MALE), "Example", Gender.FEMALE, "x")));
        CASES.put("N-COHORT", () -> world(person("p1", Relation.FRIEND, "Qqqq", "Example", Gender.FEMALE, "Qqqq")));
        CASES.put("N-BACKGROUND", () -> world(person("p1", Relation.FRIEND, "Qqqq", "Example", Gender.FEMALE, "Qqqq")));
        CASES.put("N-FAMILY", () -> world(person("p1", Relation.SIBLING, given(Gender.FEMALE), "Other", Gender.FEMALE, "x")));
        CASES.put("N-LABEL", () -> world(person("p1", Relation.FRIEND, given(Gender.FEMALE), "Example", Gender.FEMALE, "Boss")));
        // examiner
        CASES.put("N-DISTINCT", () -> planted(with(bare(), people(List.of(contact("c1", "Sam Example", number(1), Relation.FRIEND),
                contact("c2", "Sam Example", number(2), Relation.COLLEAGUE)), List.of(), List.of()))));
        CASES.put("F-PRESUMES", () -> {
            Persona noCar = persona(b -> b.transport(Transport.BIKE));
            return planted(with(bare(noCar), new CalendarPlan(List.of(new CalEvent(firstWord("nl", "presumes.CAR"), ago(5, 0), false)),
                    List.of(), List.of())));
        });
        CASES.put("S-ONE", () -> {
            List<SmsThread> threads = new ArrayList<>();
            Checks.packOf(base().persona()).senders(SenderKind.BANK).stream().limit(2).forEach(s -> threads.add(thread(s.name(), "x")));
            return planted(with(bare(), people(List.of(), List.of(), threads)));
        });
        CASES.put("M-RAW", () -> planted(with(bare(), people(List.of(contact("c1", "Sam Example", number(1), Relation.FRIEND)), List.of(),
                List.of(thread(number(1), "[photo]"))))));
        CASES.put("G-CLOCK", () -> {
            List<Call> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                calls.add(new Call(number(1), NOW.minus(Duration.ofDays(i + 1)).minusSeconds(3_600L * i), 60, CallType.INCOMING));
            }
            return planted(with(bare(), people(List.of(contact("c1", "Sam Example", number(1), Relation.FRIEND)), calls, List.of())));
        });
        CASES.put("C-CALLS", () -> planted(with(bare(), people(List.of(contact("c1", "Sam Example", number(1), Relation.FRIEND)),
                outgoing(20, 45), List.of()))));
        CASES.put("E-STALE", () -> planted(with(bare(), new BrowserPlan(List.of(), List.of(new Cookie("example.org", "id", NOW.minus(Duration.ofDays(1)))),
                List.of()))));
        CASES.put("H-HARDWARE", () -> planted(with(bare(), new NetworkPlan(List.of(), List.of(new BluetoothPair("Speaker", "02:11:22:33:44:55",
                ago(30, 0)))))));
        CASES.put("R-SMS-SENDER", () -> planted(with(bare(), people(List.of(), List.of(), List.of(thread("Mallory Example", "hello"))))));
        CASES.put("R-BIRTHDAY", () -> planted(with(bare(), new CalendarPlan(List.of(new CalEvent("Nobody Known", Instant.parse("1980-03-14T00:00:00Z"),
                true)), List.of(), List.of()))));
        CASES.put("R-GROUP-MEMBER", () -> planted(with(bare(), people(List.of(contact("c1", "Sam Example", number(1), Relation.FRIEND)),
                List.of(), List.of(new SmsThread("a;b", "a, b", null, List.of(new SmsMessage(ago(3, 0), true, "hello", true, "c1")),
                        List.of("c1", "nobody")))))));
        CASES.put("N-NUMBER", () -> planted(with(bare(), people(List.of(contact("c1", "Sam Example", "+318001234", Relation.FRIEND)),
                List.of(), List.of()))));
        // origin
        CASES.put("G-ORIGIN", () -> planted(with(bare(), people(List.of(), List.of(new Call(number(1), ago(2, 0), 30, CallType.INCOMING)),
                List.of()))));
        CASES.put("C-CLOSENESS", () -> {
            List<Contact> contacts = new ArrayList<>();
            contacts.add(contact("c0", "Partner Example", number(0), Relation.PARTNER));
            for (int i = 1; i <= 12; i++) {
                contacts.add(contact("c" + i, "Person" + i + " Example", number(i), Relation.ACQUAINTANCE));
            }
            List<Call> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                calls.add(new Call(number(12), ago(1 + i, i), 60, CallType.INCOMING));
            }
            return planted(with(bare(), people(contacts, calls, List.of())));
        });
        CASES.put("T-AWAY", () -> planted(with(bare(), trip(), new CalendarPlan(List.of(new CalEvent("Meeting", ago(20, 0), false)),
                List.of(), List.of()))));
        CASES.put("S-PHYSICAL", () -> {
            String online = Checks.packOf(base().persona()).onlineShops().get(0);
            return planted(with(bare(), shopTrip(online)));
        });
        CASES.put("O-PRESUMES", () -> planted(bare(persona(b -> b.job(JobKind.RETIRED)))));
        CASES.put("R-PHOTO-PLACE", () -> {
            Place home = home(base());
            return planted(with(bare(), new MediaPlan(List.of(photo(home.lat() + 1.0, home.lon(), ago(9, 0))), List.of())));
        });
        CASES.put("R-CALL-CONTACT", () -> planted(with(bare(), people(List.of(), List.of(new Call("+31699999999", ago(3, 0), 60,
                CallType.OUTGOING)), List.of()))));
        CASES.put("R-WIFI-PLACE", () -> planted(with(bare(), new NetworkPlan(List.of(new WifiNetwork("Nowhere", new Place("Elsewhere", 10.0, 10.0,
                PlaceKind.CAFE), ago(50, 0))), List.of()))));
        CASES.put("R-MAIL-AGE", () -> planted(with(bare(), new MailPlan(List.of(new MailMessage("Example", "Old", "old",
                base().timeline().phoneStart().minus(Duration.ofDays(30)))), Optional.empty()))));
        CASES.put("R-REQUIRES", () -> {
            Persona p = persona(b -> b.transport(Transport.BIKE));
            return planted(with(bare(p), conversation("c1", Set.of(Fact.CAR), true, "hello")));
        });
        CASES.put("R-UNREWRITTEN", () -> planted(with(bare(), conversation("c1", Set.of(), false, "hello"))));
        CASES.put("R-FUTURE", () -> planted(with(bare(), people(List.of(contact("c1", "Sam Example", number(1), Relation.FRIEND)),
                List.of(new Call(number(1), NOW.plus(Duration.ofDays(1)), 5, CallType.INCOMING)), List.of()))));
        CASES.put("R-SLOT", () -> planted(with(bare(), people(List.of(contact("c1", "Sam Example", number(1), Relation.FRIEND)), List.of(),
                List.of(thread(number(1), "see you at {PLACE_CAFE}"))))));
        CASES.put("R-REGISTER", () -> {
            ContentPlan b = bare();
            String street = firstWord(b.persona().language(), "register.STREET.words");
            StyleBook formal = new StyleBook(b.styles().persona().withRegister(Register.FORMAL),
                    Map.of(Relation.FRIEND, b.styles().persona().withRegister(Register.FORMAL)), Map.of());
            return planted(with(b, people(List.of(contact("c1", "Sam Example", number(1), Relation.FRIEND)), List.of(), List.of()), formal,
                    conversation("c1", Set.of(), true, "ok " + street)));
        });
        // population
        CASES.put("G-SIGNATURE", () -> planted(List.of(sites(List.of("a.example", "b.example")), sites(List.of("a.example", "b.example")),
                sites(List.of("a.example", "b.example")))));
        CASES.put("G-SPREAD", () -> planted(List.of(birthdays(1000))));
        CASES.put("T-ASLEEP", () -> planted(with(bare(), chronicle(event("night", EventKind.SLEEP, ago(3, 0), ago(3, -8))),
                people(List.of(contact("c1", "Sam Example", number(1), Relation.FRIEND)),
                        List.of(new Call(number(1), ago(3, -4), 60, CallType.OUTGOING)), List.of()))));
        // King's Day 2026, a Monday
        CASES.put("T-DAY-KIND", () -> planted(with(bare(persona(b -> b.job(JobKind.OFFICE))), chronicle(event("work",
                EventKind.WORK, Instant.parse("2026-04-27T07:00:00Z"), Instant.parse("2026-04-27T15:00:00Z"))))));
        CASES.put("S-CAUSE", () -> planted(serviceText(SenderKind.PARCEL, "x", ago(3, 0))));
        CASES.put("G-TIDY", () -> planted(tidy(20, 20, 20, 20)));
        CASES.put("T-ONE-PLACE", () -> planted(with(bare(), new MediaPlan(List.of(photo(52.09, 5.12, ago(9, 0)),
                photo(51.92, 4.48, ago(9, 0).plus(Duration.ofMinutes(10)))), List.of()))));
    }

    public static Event event(String id, EventKind kind, Instant start, Instant end) {
        return new Event(id, kind, start, end, ZONE, "", List.of(), Event.Layer.ROUTINE, Set.of(), Map.of(), Origin.answer("JOB"));
    }

    /** The base plan's chronicle with these events and no other. */
    /** The name of the first sender of this kind in the base persona's country. */
    public static String sender(SenderKind kind) {
        return Checks.packOf(base().persona()).senders(kind).get(0).name();
    }

    /** An event done at {@code at}, with its company and the traces it left. */
    public static Event caused(String id, EventKind kind, Instant at, String company, Origin origin, Trace... traces) {
        return new Event(id, kind, at, at, ZONE, "", List.of(), Event.Layer.SMALL, Set.of(traces), Map.of("company", company),
                origin);
    }

    /** A plan with one incoming text of a service sender at {@code at}, over a chronicle of {@code story}. */
    public static ContentPlan serviceText(SenderKind kind, String body, Instant at, Event... story) {
        String name = sender(kind);
        return with(bare(), chronicle(story), people(List.of(), List.of(),
                List.of(new SmsThread(name, name, kind, List.of(new SmsMessage(at, true, body, true, null)), List.of()))));
    }

    /**
     * A phone of {@code contacts} contacts of which {@code used} were called, and {@code events} calendar entries of
     * which {@code full} left every trace: a photo at their start and their title in a note.
     */
    public static ContentPlan tidy(int contacts, int used, int events, int full) {
        List<Contact> saved = new ArrayList<>();
        List<Call> calls = new ArrayList<>();
        for (int i = 0; i < contacts; i++) {
            saved.add(contact("c" + i, "Name " + i, number(i), Relation.FRIEND));
            if (i < used) {
                calls.add(new Call(number(i), ago(2, i), 30, CallType.OUTGOING));
            }
        }
        List<CalEvent> entries = new ArrayList<>();
        List<Note> notes = new ArrayList<>();
        List<MediaItem> photos = new ArrayList<>();
        for (int i = 0; i < events; i++) {
            entries.add(new CalEvent("Plan " + i, ago(30 + i, 0), false));
            if (i < full) {
                notes.add(new Note("Plan " + i, ago(31 + i, 0), false));
                photos.add(photo(52.09, 5.12, ago(30 + i, 0).plus(Duration.ofMinutes(20))));
            }
        }
        return with(bare(), people(saved, calls, List.of()), new CalendarPlan(entries, List.of(), notes),
                new MediaPlan(photos, List.of()));
    }

    public static Chronicle chronicle(Event... events) {
        Chronicle b = base().chronicle();
        return new Chronicle(List.of(events), b.routine(), List.of(), b.phoneStart(), b.now(), b.zone());
    }

    /** {@code n} outgoing calls of {@code seconds} each to the number of index 1. */
    public static List<Call> outgoing(int n, int seconds) {
        List<Call> calls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            calls.add(new Call(number(1), ago(1 + i, i), seconds, CallType.OUTGOING));
        }
        return calls;
    }

    /** A trip of two days, 21 to 19 days before now, to a place far from home. */
    public static TravelPlan trip() {
        return oneTrip("Away", 41.39, 2.17, PlaceKind.TRAVEL, TravelMode.PLANE, NOW.minus(Duration.ofDays(21)),
                NOW.minus(Duration.ofDays(19)));
    }

    /** A trip of 40 to 38 days before now to a shop of this name. */
    public static TravelPlan shopTrip(String name) {
        return oneTrip(name, 52.1, 5.1, PlaceKind.SHOP, TravelMode.CAR, ago(40, 0), ago(38, 0));
    }

    private static TravelPlan oneTrip(String name, double lat, double lon, PlaceKind kind, TravelMode mode, Instant from,
            Instant to) {
        Origin answered = Origin.answer("TRIPS");
        return new TravelPlan(List.of(new Trip("trip-1",
                List.of(new WorldPlace("trip-1.0", kind, name, lat, lon, "", answered)), mode, from, to, ZONE, answered)));
    }

    public static MediaItem photo(double lat, double lon, Instant taken) {
        return new MediaItem(MediaKind.CAMERA_FILL, "PXL_x.jpg", taken, taken.plusSeconds(1), "fill:1", null, 4080, 3072, true, "cam", lat, lon,
                Set.of());
    }

    /** A one-to-one conversation with the contact, one outgoing message of the persona. */
    public static FilledConversation conversation(String contactId, Set<Fact> requires, boolean rewritten, String body) {
        return new FilledConversation("pool-1", "chat", null, List.of(contactId), requires,
                List.of(new SmsMessage(ago(4, 0), false, body, true, null)), rewritten);
    }

    public static ContentPlan sites(List<String> domains) {
        List<Visit> visits = new ArrayList<>();
        for (int i = 0; i < domains.size(); i++) {
            visits.add(new Visit(domains.get(i), "https://" + domains.get(i) + "/", ago(2 + i, i)));
        }
        return with(bare(), new BrowserPlan(visits, List.of(), List.of()));
    }

    /** One plan with {@code n} contacts whose birthdays fall on days 1 to 28 only. */
    public static ContentPlan birthdays(int n) {
        List<Contact> contacts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            contacts.add(new Contact("c" + i, "Person" + i + " Example", number(i), false, null, LocalDate.of(1990, 1 + i % 12, 1 + i % 28), null,
                    PhotoKind.NONE, Relation.ACQUAINTANCE, 1990, Gender.FEMALE, null));
        }
        return with(bare(), people(contacts, List.of(), List.of()));
    }
}
