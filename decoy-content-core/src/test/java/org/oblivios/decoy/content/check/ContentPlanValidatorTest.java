// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.persona.PersonaProfile.Fact;
import org.oblivios.decoy.content.pool.FilledConversation;
import org.oblivios.decoy.content.style.StyleBook;
import org.oblivios.decoy.content.style.StyleVector;
import org.oblivios.decoy.content.style.StyleVector.Register;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.pool.PoolConversation;
import org.oblivios.decoy.content.pool.TestPool;
import org.oblivios.decoy.content.pool.TestPool.AcceptAllJudge;
import org.oblivios.decoy.content.pool.TestPool.IdentityRewriter;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.plan.ContentPlan;
import org.oblivios.decoy.content.plan.ContentPlanner;
import org.oblivios.decoy.content.story.MonthCount;
import org.oblivios.decoy.content.story.Place;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.story.VolumeCounts;
import org.oblivios.decoy.content.surface.AppEntry;
import org.oblivios.decoy.content.surface.AppSpec;
import org.oblivios.decoy.content.surface.AppsPlan;
import org.oblivios.decoy.content.surface.BluetoothPair;
import org.oblivios.decoy.content.surface.BrowserPlan;
import org.oblivios.decoy.content.surface.CalEvent;
import org.oblivios.decoy.content.surface.CalendarSurface;
import org.oblivios.decoy.content.surface.CalendarPlan;
import org.oblivios.decoy.content.surface.Call;
import org.oblivios.decoy.content.surface.CallType;
import org.oblivios.decoy.content.surface.ChosenPhoto;
import org.oblivios.decoy.content.surface.Contact;
import org.oblivios.decoy.content.surface.DeviceCameraProfile;
import org.oblivios.decoy.content.surface.Note;
import org.oblivios.decoy.content.surface.NotificationRecord;
import org.oblivios.decoy.content.surface.TracesPlan;
import org.oblivios.decoy.content.surface.TravelPlan;
import org.oblivios.decoy.content.surface.Trip;
import org.oblivios.decoy.content.surface.UsagePlan;
import org.oblivios.decoy.content.surface.UsageSession;
import org.oblivios.decoy.content.surface.Visit;
import org.oblivios.decoy.content.surface.Voicemail;
import org.oblivios.decoy.content.surface.MailMessage;
import org.oblivios.decoy.content.surface.MailPlan;
import org.oblivios.decoy.content.surface.MediaItem;
import org.oblivios.decoy.content.surface.MediaKind;
import org.oblivios.decoy.content.surface.MediaPlan;
import org.oblivios.decoy.content.surface.NetworkPlan;
import org.oblivios.decoy.content.surface.People;
import org.oblivios.decoy.content.surface.ServerProbe;
import org.oblivios.decoy.content.surface.SmsMessage;
import org.oblivios.decoy.content.surface.SmsThread;
import org.oblivios.decoy.content.surface.WifiNetwork;

class ContentPlanValidatorTest {
    private static final List<PoolConversation> POOL = TestPool.random(5000, Seed.of(17, 17));
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    /** A test fixture, not a measured profile: the real one comes from the owner's sample photos (Task 5). */
    private static final DeviceCameraProfile CAM = new DeviceCameraProfile(4080, 3072, List.of(table()),
            "PXL_\\d{8}_\\d{9}\\.jpg", "'PXL_'yyyyMMdd_HHmmssSSS'.jpg'", true);

    private static int[] table() {
        int[] t = new int[64];
        for (int i = 0; i < 64; i++) {
            t[i] = 8 + i;
        }
        return t;
    }

    private static PersonaInputs inputs() {
        return new PersonaInputs("Sam", "Example", "Utrecht", "NL", ZONE, Locale.forLanguageTag("nl-NL"));
    }

    private static VolumeCounts counts() {
        List<MonthCount> months = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            months.add(new MonthCount(YearMonth.of(2026, 10).minusMonths(29 - i), 60 + i * 5, 10, 8, 1_000_000L));
        }
        return new VolumeCounts(months);
    }

    private static List<ChosenPhoto> chosen() {
        List<ChosenPhoto> out = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            out.add(new ChosenPhoto("/sdcard/DCIM/Camera/own_" + i + ".jpg", Instant.parse("2025-03-01T08:00:00Z").plusSeconds(i * 300_000L), Set.of()));
        }
        return out;
    }

    private static List<AppSpec> apps() {
        return List.of(new AppSpec("org.example.chat", "chat.example.org", true), new AppSpec("org.example.notes", "notes.example.org", false));
    }

    private static ContentPlan plan(long n) {
        return ContentPlanner.build(PersonaProfile.open(inputs()), org.oblivios.decoy.content.TestSetup.inputs(counts(), chosen(), apps(), "AndroidWifi", CAM, ServerProbe.HONOURED, NOW),
                org.oblivios.decoy.content.review.ReviewEdits.none(), Seed.of(n, n * 31 + 7),
                // a pool per plan: with the identity rewriter a shared pool would repeat its texts, which the real rewriter prevents
                TestPool.random(5000, Seed.of(n, 5000)), new IdentityRewriter(), new AcceptAllJudge());
    }

    private static Set<String> ids(List<Violation> v) {
        return v.stream().map(Violation::ruleId).collect(Collectors.toSet());
    }

    /** A copy of {@code p} with the given parts replaced; null keeps the original. */
    private static ContentPlan with(ContentPlan p, People people, CalendarPlan calendar, NetworkPlan network, MediaPlan media, MailPlan mail) {
        return new ContentPlan(p.persona(), p.timeline(), people != null ? people : p.people(), calendar != null ? calendar : p.calendar(),
                p.travel(), p.device(), network != null ? network : p.network(), p.usage(), p.apps(), p.browser(), p.vault(), p.traces(),
                media != null ? media : p.media(), mail != null ? mail : p.mail(), p.styles(), p.conversations(), p.reserve(), p.warnings());
    }

    private static People withPeople(People p, List<Call> calls, List<SmsThread> threads) {
        return new People(p.ownNumber(), p.contacts(), calls != null ? calls : p.calls(), threads != null ? threads : p.threads(), p.voicemails());
    }

    private static void assertOnly(String rule, ContentPlan planted) {
        List<Violation> found = ContentPlanValidator.check(planted);
        assertEquals(Set.of(rule), ids(found), "violations " + found);
    }

    @Test
    void cleanPlanHasNoViolation() {
        for (long n = 0; n < 20; n++) {
            List<Violation> found = ContentPlanValidator.check(plan(n));
            assertTrue(found.isEmpty(), "seed " + n + ": " + found);
        }
    }

    @Test
    void photoGpsOutsideThePlaceOfTheDay() {
        ContentPlan p = plan(1);
        List<MediaItem> items = new ArrayList<>(p.media().items());
        int at = 0;
        while (items.get(at).kind() != MediaKind.CAMERA_FILL || items.get(at).lat() == null) {
            at++;
        }
        MediaItem i = items.get(at);
        items.set(at, new MediaItem(i.kind(), i.name(), i.taken(), i.added(), i.sourceId(), i.copyOf(), i.width(), i.height(), i.exif(),
                i.profileId(), i.lat() + 1.0, i.lon(), i.stripFields()));
        assertOnly("R-PHOTO-PLACE", with(p, null, null, null, new MediaPlan(items, p.media().warnings()), null));
    }

    @Test
    void callToUnknownNumber() {
        ContentPlan p = plan(2);
        List<Call> calls = new ArrayList<>(p.people().calls());
        calls.add(new Call("+31699999999", NOW.minus(Duration.ofDays(3)), 60, CallType.OUTGOING));
        assertOnly("R-CALL-CONTACT", with(p, withPeople(p.people(), calls, null), null, null, null, null));
    }

    @Test
    void smsFromUnknownSender() {
        ContentPlan p = plan(3);
        List<SmsThread> threads = new ArrayList<>(p.people().threads());
        threads.add(new SmsThread("Mallory Bank", "Mallory Bank", null, List.of(new SmsMessage(NOW.minus(Duration.ofDays(3)), true, "hello", true, null)), List.of()));
        assertOnly("R-SMS-SENDER", with(p, withPeople(p.people(), null, threads), null, null, null, null));
    }

    @Test
    void birthdayWithoutContact() {
        ContentPlan p = plan(4);
        List<CalEvent> events = new ArrayList<>(p.calendar().events());
        events.add(new CalEvent("Birthday: Nobody Known", Instant.parse("1980-03-14T00:00:00Z"), true));
        assertOnly("R-BIRTHDAY", with(p, null, new CalendarPlan(events, p.calendar().alarms(), p.calendar().notes()), null, null, null));
    }

    /** Moves the yearly event of one contact's birthday to another date; the contact stays. */
    private static void assertBirthdayMoved(java.util.function.UnaryOperator<java.time.LocalDate> move) {
        ContentPlan p = plan(4);
        Contact c = p.people().contacts().stream().filter(x -> x.birthday() != null).findFirst().orElseThrow();
        List<CalEvent> events = new ArrayList<>();
        for (CalEvent e : p.calendar().events()) {
            boolean mine = e.yearly() && e.title().contains(c.name()) && e.start().equals(c.birthday().atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
            events.add(mine ? new CalEvent(e.title(), move.apply(c.birthday()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), true) : e);
        }
        assertOnly("R-BIRTHDAY", with(p, null, new CalendarPlan(events, p.calendar().alarms(), p.calendar().notes()), null, null, null));
    }

    @Test
    void birthdayInTheWrongMonth() {
        assertBirthdayMoved(d -> d.plusMonths(1)); // the generator draws days 1 to 28, so the day survives
    }

    @Test
    void birthdayOnTheWrongDayOfTheMonth() {
        assertBirthdayMoved(d -> d.withDayOfMonth(d.getDayOfMonth() == 1 ? 2 : 1));
    }

    @Test
    void surplusChosenPhotosWarnAtThePlanLevel() {
        List<ChosenPhoto> many = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            many.add(new ChosenPhoto("/sdcard/DCIM/Camera/many_" + i + ".jpg", Instant.parse("2025-03-01T08:00:00Z").plusSeconds(i * 61L), Set.of()));
        }
        ContentPlan p = ContentPlanner.build(PersonaProfile.open(inputs()), org.oblivios.decoy.content.TestSetup.inputs(counts(), many, apps(), null, CAM, ServerProbe.HONOURED, NOW),
                org.oblivios.decoy.content.review.ReviewEdits.none(), Seed.of(3, 3), POOL, new IdentityRewriter(), new AcceptAllJudge());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("chosen photos")), "warnings " + p.warnings());
        assertTrue(ContentPlanValidator.check(p).isEmpty());
    }

    @Test
    void contactBirthdayWithoutEvent() {
        ContentPlan p = plan(4);
        List<CalEvent> events = p.calendar().events().stream().filter(e -> !e.yearly()).toList();
        assertTrue(p.people().contacts().stream().anyMatch(c -> c.birthday() != null));
        assertOnly("R-BIRTHDAY", with(p, null, new CalendarPlan(events, p.calendar().alarms(), p.calendar().notes()), null, null, null));
    }

    @Test
    void wifiAtNoPlace() {
        ContentPlan p = plan(5);
        List<WifiNetwork> wifi = new ArrayList<>(p.network().wifi());
        wifi.add(new WifiNetwork("Nowhere", new Place("Elsewhere", 10.0, 10.0, PlaceKind.CAFE), p.timeline().phoneStart().plus(Duration.ofDays(2))));
        assertOnly("R-WIFI-PLACE", with(p, null, null, new NetworkPlan(wifi, p.network().bluetooth()), null, null));
    }

    @Test
    void twoNetworksWithoutAPlace() {
        ContentPlan p = plan(5);
        List<WifiNetwork> wifi = new ArrayList<>(p.network().wifi().stream().filter(w -> w.place() != null).toList());
        wifi.add(new WifiNetwork("One", null, p.timeline().phoneStart().plus(Duration.ofDays(2))));
        wifi.add(new WifiNetwork("Two", null, p.timeline().phoneStart().plus(Duration.ofDays(3))));
        assertOnly("R-WIFI-PLACE", with(p, null, null, new NetworkPlan(wifi, p.network().bluetooth()), null, null));
    }

    /** A copy of {@code p} with each given part (found by its type) replacing the plan's own. */
    private static ContentPlan swap(ContentPlan p, Object... parts) {
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
        for (Object part : parts) {
            if (part instanceof People x) {
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
            } else {
                throw new IllegalArgumentException(part.getClass().getName());
            }
        }
        return new ContentPlan(p.persona(), p.timeline(), people, calendar, travel, p.device(), network, usage, apps, browser, p.vault(),
                traces, media, mail, p.styles(), p.conversations(), p.reserve(), p.warnings());
    }

    private static <T> List<T> plus(List<T> list, T extra) {
        List<T> out = new ArrayList<>(list);
        out.add(extra);
        return out;
    }

    @Test
    void futureInEverySurface() {
        ContentPlan p = plan(6);
        Instant late = NOW.plus(Duration.ofDays(1));
        People pe = p.people();
        String number = pe.contacts().get(0).number();
        Map<String, ContentPlan> planted = new java.util.LinkedHashMap<>();
        planted.put("call", swap(p, new People(pe.ownNumber(), pe.contacts(), plus(pe.calls(), new Call(number, late, 5, CallType.INCOMING)), pe.threads(), pe.voicemails())));
        planted.put("sms", swap(p, new People(pe.ownNumber(), pe.contacts(), pe.calls(),
                plus(pe.threads(), new SmsThread(number, "x", null, List.of(new SmsMessage(late, true, "hi", true, null)), List.of())), pe.voicemails())));
        planted.put("voicemail", swap(p, new People(pe.ownNumber(), pe.contacts(), pe.calls(), pe.threads(), plus(pe.voicemails(), new Voicemail(number, late, 5)))));
        Contact c0 = pe.contacts().get(0);
        List<Contact> contacts = new ArrayList<>(pe.contacts());
        contacts.set(0, new Contact(c0.id(), c0.name(), c0.number(), c0.favourite(), c0.group(), c0.birthday(), late, c0.photo(),
                c0.relation(), c0.birthYear(), c0.gender(), c0.origin()));
        planted.put("last contact", swap(p, new People(pe.ownNumber(), contacts, pe.calls(), pe.threads(), pe.voicemails())));
        planted.put("note", swap(p, new CalendarPlan(p.calendar().events(), p.calendar().alarms(), plus(p.calendar().notes(), new Note("x", late, false)))));
        planted.put("trip", swap(p, new TravelPlan(plus(p.travel().trips(), new Trip("tx", List.of(p.timeline().places().get(0)), false, late, late.plus(Duration.ofDays(1)))),
                p.travel().boardingPassTrips())));
        planted.put("taken", swap(p, new MediaPlan(plus(p.media().items(), new MediaItem(MediaKind.DOCUMENT, "Scan_a.pdf", late, NOW.minus(Duration.ofHours(1)),
                "doc:scan", null, 0, 0, false, null, null, null, Set.of())), p.media().warnings())));
        planted.put("added", swap(p, new MediaPlan(plus(p.media().items(), new MediaItem(MediaKind.DOCUMENT, "Scan_b.pdf", NOW.minus(Duration.ofDays(1)), late,
                "doc:scan", null, 0, 0, false, null, null, null, Set.of())), p.media().warnings())));
        planted.put("mail", swap(p, new MailPlan(plus(p.mail().messages(), new MailMessage("ING", "Future", "x", late)), p.mail().refusedNotice())));
        planted.put("wifi", swap(p, new NetworkPlan(plus(p.network().wifi(), new WifiNetwork("Late", p.timeline().places().get(0), late)), p.network().bluetooth())));
        planted.put("bluetooth", swap(p, new NetworkPlan(p.network().wifi(), plus(p.network().bluetooth(), new BluetoothPair("Car", "02:00:00:00:00:01", late)))));
        planted.put("usage session", swap(p, new UsagePlan(plus(p.usage().sessions(), new UsageSession(late, 5, "org.example.chat")), p.usage().lastUsed())));
        Map<String, Instant> lastUsed = new HashMap<>(p.usage().lastUsed());
        lastUsed.put("org.example.chat", late);
        planted.put("last use", swap(p, new UsagePlan(p.usage().sessions(), lastUsed)));
        planted.put("install", swap(p, new AppsPlan(plus(p.apps().apps(), new AppEntry("org.example.late", late, "1.0.0", 1, 1)))));
        planted.put("visit", swap(p, new BrowserPlan(plus(p.browser().history(), new Visit("late.example", "https://late.example/", late)), p.browser().cookies(), p.browser().bookmarks())));
        planted.put("notification", swap(p, new TracesPlan(plus(p.traces().notifications(), new NotificationRecord("org.example.chat", "x", late)), p.traces().cacheDirTimes())));
        planted.put("event", swap(p, new CalendarPlan(plus(p.calendar().events(), new CalEvent("Late", NOW.plus(Duration.ofDays(CalendarSurface.HORIZON_DAYS + 10)), false)), p.calendar().alarms(), p.calendar().notes())));
        for (Map.Entry<String, ContentPlan> e : planted.entrySet()) {
            List<Violation> found = ContentPlanValidator.check(e.getValue());
            assertEquals(Set.of("R-FUTURE"), ids(found), e.getKey() + ": " + found);
        }
    }

    @Test
    void eventAfterNow() {
        // a calendar entry may lie ahead, up to the horizon; past it is a contradiction
        ContentPlan p = plan(6);
        List<CalEvent> events = new ArrayList<>(p.calendar().events());
        events.add(new CalEvent("Dentist", NOW.plus(Duration.ofDays(CalendarSurface.HORIZON_DAYS + 10)), false));
        assertOnly("R-FUTURE", with(p, null, new CalendarPlan(events, p.calendar().alarms(), p.calendar().notes()), null, null, null));
    }

    @Test
    void upcomingAppointmentIsNoContradiction() {
        ContentPlan p = plan(6);
        List<CalEvent> events = new ArrayList<>(p.calendar().events());
        events.add(new CalEvent("Dentist", NOW.plus(Duration.ofDays(CalendarSurface.HORIZON_DAYS - 1)), false));
        assertTrue(ContentPlanValidator.check(with(p, null, new CalendarPlan(events, p.calendar().alarms(), p.calendar().notes()), null, null, null)).isEmpty());
    }

    @Test
    void mailBeforePhoneStart() {
        ContentPlan p = plan(7);
        List<MailMessage> messages = new ArrayList<>(p.mail().messages());
        messages.add(0, new MailMessage("ING", "Old", "old", p.timeline().phoneStart().minus(Duration.ofDays(30))));
        assertOnly("R-MAIL-AGE", with(p, null, null, null, null, new MailPlan(messages, p.mail().refusedNotice())));
    }

    /** A seed whose persona has one-to-one conversations and a reserve; with the random test pool some personas get none. */
    private static final long WITH_CONVERSATIONS = 6;

    /** A copy of {@code p} with other conversations, reserve and style book; null keeps the original. */
    private static ContentPlan withPool(ContentPlan p, List<FilledConversation> conversations, List<FilledConversation> reserve, StyleBook styles) {
        return new ContentPlan(p.persona(), p.timeline(), p.people(), p.calendar(), p.travel(), p.device(), p.network(), p.usage(), p.apps(),
                p.browser(), p.vault(), p.traces(), p.media(), p.mail(), styles != null ? styles : p.styles(),
                conversations != null ? conversations : p.conversations(), reserve != null ? reserve : p.reserve(), p.warnings());
    }

    private static List<FilledConversation> replaced(List<FilledConversation> in, int at, FilledConversation with) {
        List<FilledConversation> out = new ArrayList<>(in);
        out.set(at, with);
        return out;
    }

    /** The conversation with one message of the persona's side, or of the other side, replaced by the body. */
    private static FilledConversation said(FilledConversation c, boolean incoming, String body) {
        List<SmsMessage> messages = new ArrayList<>(c.messages());
        for (int i = 0; i < messages.size(); i++) {
            SmsMessage m = messages.get(i);
            if (m.incoming() == incoming) {
                messages.set(i, new SmsMessage(m.at(), m.incoming(), body, m.read(), m.from()));
                return new FilledConversation(c.poolId(), c.situation(), c.group(), c.contactIds(), c.requires(), messages, c.rewritten());
            }
        }
        throw new AssertionError("no such message in " + c.poolId());
    }

    private static FilledConversation requiring(FilledConversation c, Set<Fact> requires, boolean rewritten) {
        return new FilledConversation(c.poolId(), c.situation(), c.group(), c.contactIds(), requires, c.messages(), rewritten);
    }

    @Test
    void unfilledSlotIsAViolation() {
        ContentPlan p = plan(WITH_CONVERSATIONS);
        List<SmsThread> threads = new ArrayList<>(p.people().threads());
        SmsThread t = threads.get(0);
        List<SmsMessage> messages = new ArrayList<>(t.messages());
        SmsMessage m = messages.get(0);
        messages.set(0, new SmsMessage(m.at(), m.incoming(), "see you at {PLACE_CAFE}", m.read(), m.from()));
        threads.set(0, new SmsThread(t.address(), t.title(), t.senderKind(), messages, t.members()));
        assertOnly("R-SLOT", with(p, withPeople(p.people(), null, threads), null, null, null, null));
        FilledConversation held = said(p.reserve().get(0), true, "{TIME} then");
        assertOnly("R-SLOT", withPool(p, null, replaced(p.reserve(), 0, held), null));
    }

    @Test
    void presumedFactMissingIsAViolation() {
        ContentPlan p = plan(WITH_CONVERSATIONS);
        Fact missing = java.util.Arrays.stream(Fact.values()).filter(f -> !p.persona().facts().contains(f)).findFirst().orElseThrow();
        FilledConversation c = requiring(p.conversations().get(0), Set.of(missing), true);
        assertOnly("R-REQUIRES", withPool(p, replaced(p.conversations(), 0, c), null, null));
        FilledConversation held = requiring(p.reserve().get(0), Set.of(missing), true);
        assertOnly("R-REQUIRES", withPool(p, null, replaced(p.reserve(), 0, held), null));
    }

    @Test
    void streetWordFromAFormalPersonaIsAViolation() {
        ContentPlan p = plan(WITH_CONVERSATIONS);
        FilledConversation c = p.conversations().stream().filter(x -> x.group() == null).findFirst().orElseThrow();
        int at = p.conversations().indexOf(c);
        Relation relation = p.people().contacts().stream().filter(x -> x.id().equals(c.contactIds().get(0))).findFirst().orElseThrow().relation();
        String word = LanguagePack.of(p.persona().language()).list("register.STREET.words").get(0);
        List<FilledConversation> street = replaced(p.conversations(), at, said(c, false, "Ok " + word.toUpperCase(Locale.ROOT) + "!"));
        for (Register formal : List.of(Register.FORMAL, Register.NEUTRAL)) {
            assertOnly("R-REGISTER", withPool(p, street, null, towards(p, relation, formal)));
        }
        assertTrue(ContentPlanValidator.check(withPool(p, street, null, towards(p, relation, Register.STREET))).isEmpty());
        // what the contact writes is the contact's own, and a word inside another word is not that word
        for (FilledConversation harmless : List.of(said(c, true, word), said(c, false, "x" + word + "x"))) {
            ContentPlan q = withPool(p, replaced(p.conversations(), at, harmless), null, towards(p, relation, Register.FORMAL));
            assertEquals(List.of(), ContentPlanValidator.check(q));
        }
    }

    private static StyleBook towards(ContentPlan p, Relation relation, Register register) {
        Map<Relation, StyleVector> towards = new HashMap<>();
        p.styles().personaTowards().forEach((r, style) -> towards.put(r, style.withRegister(Register.STREET)));
        towards.put(relation, p.styles().persona().withRegister(register));
        return new StyleBook(p.styles().persona().withRegister(Register.STREET), towards, p.styles().contacts());
    }

    @Test
    void unrewrittenConversationIsAViolation() {
        ContentPlan p = plan(WITH_CONVERSATIONS);
        FilledConversation c = requiring(p.conversations().get(0), p.conversations().get(0).requires(), false);
        assertOnly("R-UNREWRITTEN", withPool(p, replaced(p.conversations(), 0, c), null, null));
        FilledConversation held = requiring(p.reserve().get(0), p.reserve().get(0).requires(), false);
        assertOnly("R-UNREWRITTEN", withPool(p, null, replaced(p.reserve(), 0, held), null));
    }

    private static ContentPlan withGroup(ContentPlan p, List<String> members, boolean incoming, String from) {
        List<SmsThread> threads = new ArrayList<>(p.people().threads());
        threads.add(new SmsThread("a;b", "a, b", null,
                List.of(new SmsMessage(NOW.minus(Duration.ofDays(3)), incoming, "hello", true, from)), members));
        return with(p, withPeople(p.people(), null, threads), null, null, null, null);
    }

    @Test
    void groupMemberWhoIsNoContactIsAViolation() {
        ContentPlan p = plan(WITH_CONVERSATIONS);
        String a = p.people().contacts().get(0).id();
        String b = p.people().contacts().get(1).id();
        String outsider = p.people().contacts().get(2).id();
        assertTrue(ContentPlanValidator.check(withGroup(p, List.of(a, b), true, b)).isEmpty());
        assertTrue(ContentPlanValidator.check(withGroup(p, List.of(a, b), false, null)).isEmpty());
        assertOnly("R-GROUP-MEMBER", withGroup(p, List.of(a, "nobody"), true, a));
        assertOnly("R-GROUP-MEMBER", withGroup(p, List.of(a, b), true, outsider));
        assertOnly("R-GROUP-MEMBER", withGroup(p, List.of(a, b), true, null));
    }

    @Test
    void plannerIsDeterministicPerSeed() {
        assertEquals(plan(8), plan(8));
        assertNotEquals(plan(8), plan(9));
    }

    @Test
    void thinCountsStillGiveACleanPlanWithWarnings() {
        VolumeCounts thin = new VolumeCounts(List.of(new MonthCount(YearMonth.of(2026, 10), 30, 2, 1, 100_000L)));
        ContentPlan p = ContentPlanner.build(PersonaProfile.open(inputs()), org.oblivios.decoy.content.TestSetup.inputs(thin, List.of(), apps(), null, CAM, ServerProbe.HONOURED, NOW),
                org.oblivios.decoy.content.review.ReviewEdits.none(), Seed.of(3, 3), POOL, new IdentityRewriter(), new AcceptAllJudge());
        assertFalse(p.warnings().isEmpty());
        assertTrue(ContentPlanValidator.check(p).isEmpty());
    }

    @Test
    void aCountryWithoutVerifiedNumbersGivesNoPlan() {
        PersonaInputs de = new PersonaInputs("Sam", "Example", "Berlin", "DE", ZoneId.of("Europe/Berlin"), Locale.forLanguageTag("en-DE"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ContentPlanner.build(PersonaProfile.open(de), org.oblivios.decoy.content.TestSetup.inputs(counts(), List.of(), apps(), null, CAM, ServerProbe.HONOURED, NOW),
                org.oblivios.decoy.content.review.ReviewEdits.none(), Seed.of(3, 3), POOL, new IdentityRewriter(), new AcceptAllJudge()));
        // the neutral pack has no places either, and the city is read before any number
        assertTrue(e.getMessage().startsWith("city: "), e.getMessage());
    }

    @Test
    void refusedMailboxStillGivesAPlanWithoutMail() {
        ContentPlan p = ContentPlanner.build(PersonaProfile.open(inputs()), org.oblivios.decoy.content.TestSetup.inputs(counts(), List.of(), apps(), null, CAM, ServerProbe.IGNORED, NOW),
                org.oblivios.decoy.content.review.ReviewEdits.none(), Seed.of(3, 3), POOL, new IdentityRewriter(), new AcceptAllJudge());
        assertTrue(p.mail().messages().isEmpty());
        assertTrue(p.mail().refusedNotice().isPresent());
        assertTrue(ContentPlanValidator.check(p).isEmpty());
    }

    @Test
    void noFixedSignature() {
        // how many of 50 plans contain each value: none may be in more than 10 percent
        Map<String, Integer> numbers = new HashMap<>();
        Map<String, Integer> fileNames = new HashMap<>();
        Map<String, Integer> conversations = new HashMap<>();
        Map<String, Integer> sentences = new HashMap<>();
        List<Set<String>> sentencesOfPlan = new ArrayList<>();
        for (long n = 0; n < 50; n++) {
            ContentPlan p = plan(n + 100);
            // sentences of four words or more between the owner and a contact; a short reply ("Ok") is universal by nature
            Set<String> longTexts = p.people().threads().stream().filter(t -> t.senderKind() == null)
                    .flatMap(t -> t.messages().stream()).map(SmsMessage::body)
                    .filter(b -> b.trim().split("\\s+").length >= 4).collect(Collectors.toSet());
            longTexts.forEach(x -> sentences.merge(x, 1, Integer::sum));
            sentencesOfPlan.add(longTexts);
            Set<String> nums = new HashSet<>();
            p.people().contacts().forEach(c -> nums.add(c.number()));
            nums.add(p.people().ownNumber());
            // saved-image names are what a browser writes, the same on every phone by nature; the rest is generated
            Set<String> names = p.media().items().stream()
                    .filter(i -> i.kind() != MediaKind.SAVED && !(i.kind() == MediaKind.COPY && i.sourceId().startsWith("saved:")))
                    .map(MediaItem::name).collect(Collectors.toSet());
            // a single short phrase is drawn from a shared list of common ones; a whole conversation is the signature
            Set<String> threads = p.people().threads().stream()
                    .map(t -> t.messages().stream().map(SmsMessage::body).collect(Collectors.joining("\n"))).collect(Collectors.toSet());
            nums.forEach(x -> numbers.merge(x, 1, Integer::sum));
            names.forEach(x -> fileNames.merge(x, 1, Integer::sum));
            threads.forEach(x -> conversations.merge(x, 1, Integer::sum));
        }
        assertTrue(maxCount(numbers) <= 5, "a number in " + maxCount(numbers) + " of 50 plans");
        assertTrue(maxCount(fileNames) <= 5, "a file name in " + maxCount(fileNames) + " of 50 plans");
        assertTrue(maxCount(conversations) <= 5, "a conversation in " + maxCount(conversations) + " of 50 plans");
        // no finite generator keeps every sentence under 10 percent of the plans; what counts is how much of a plan is
        // made of sentences that many other plans share
        for (Set<String> own : sentencesOfPlan) {
            long shared = own.stream().filter(x -> sentences.get(x) > 10).count();
            assertTrue(own.isEmpty() || (double) shared / own.size() < 0.05, shared + " of " + own.size() + " sentences are shared by more than 10 of 50 plans");
        }
    }

    private static int maxCount(Map<String, Integer> m) {
        return m.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }
}
