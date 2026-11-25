// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.DataPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.persona.PersonaProfile.Fact;
import org.oblivios.decoy.content.plan.ContentPlan;
import org.oblivios.decoy.content.plan.ContentPlanner;
import org.oblivios.decoy.content.pool.TestPool.AcceptAllJudge;
import org.oblivios.decoy.content.pool.TestPool.IdentityRewriter;
import org.oblivios.decoy.content.story.MonthCount;
import org.oblivios.decoy.content.story.Timeline;
import org.oblivios.decoy.content.story.VolumeCounts;
import org.oblivios.decoy.content.story.VolumeModel;
import org.oblivios.decoy.content.style.StyleBook;
import org.oblivios.decoy.content.style.StyleVector.Register;
import org.oblivios.decoy.content.style.Styles;
import org.oblivios.decoy.content.surface.Contact;
import org.oblivios.decoy.content.surface.ContentSnapshot;
import org.oblivios.decoy.content.surface.DeviceCameraProfile;
import org.oblivios.decoy.content.surface.People;
import org.oblivios.decoy.content.surface.PeopleSurface;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.surface.RollForward;
import org.oblivios.decoy.content.surface.RollForwardEvent;
import org.oblivios.decoy.content.surface.ServerProbe;
import org.oblivios.decoy.content.surface.SmsMessage;
import org.oblivios.decoy.content.surface.SmsThread;

class ConversationPickerTest {
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final int YEAR = 2026;
    private static final List<PoolConversation> POOL = TestPool.random(8000, Seed.of(42, 42));

    private record Setup(Persona persona, Timeline timeline, People people, StyleBook styles, CountryPack pack) {}

    private static CountryPack nl() {
        return Packs.load("NL").orElseThrow();
    }

    private static Setup setup(long n, UnaryOperator<PersonaProfile.Builder> f) {
        return setup(n, f, nl());
    }

    private static Setup setup(long n, UnaryOperator<PersonaProfile.Builder> f, CountryPack pack) {
        PersonaInputs in = new PersonaInputs("Sam Example", "Utrecht", "NL", ZoneId.of("Europe/Amsterdam"), Locale.forLanguageTag("nl-NL"));
        Persona p = Persona.derive(Seed.of(n, 1), f.apply(PersonaProfile.builder(in)).build(), YEAR);
        List<MonthCount> months = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            months.add(new MonthCount(YearMonth.of(2026, 10).minusMonths(11 - i), 60, 10, 8, 1_000_000L));
        }
        Timeline t = Timeline.generate(p, VolumeModel.fromCounts(new VolumeCounts(months), Seed.of(n, 2)), pack, NOW, Seed.of(n, 3));
        People people = PeopleSurface.generate(p, t, pack, Seed.of(n, 4));
        return new Setup(p, t, people, Styles.book(p, people, Seed.of(n, 5)), pack);
    }

    private static Picked pick(Setup s, List<PoolConversation> pool, long n, Judge judge) {
        return ConversationPicker.pick(s.persona(), s.people(), s.styles(), s.timeline(), s.pack(), pool, Seed.of(n, 6),
                new IdentityRewriter(), judge);
    }

    private static Picked pick(Setup s, List<PoolConversation> pool, long n) {
        return pick(s, pool, n, new AcceptAllJudge());
    }

    private static List<FilledConversation> all(Picked p) {
        List<FilledConversation> out = new ArrayList<>();
        p.byContact().values().forEach(out::addAll);
        out.addAll(p.groups());
        return out;
    }

    private static Map<String, PoolConversation> byId(List<PoolConversation> pool) {
        Map<String, PoolConversation> m = new HashMap<>();
        pool.forEach(c -> m.put(c.id(), c));
        return m;
    }

    private static Contact contact(Setup s, Relation r) {
        return s.people().contacts().stream().filter(c -> c.relation() == r).findFirst().orElseThrow();
    }

    private static Contact byContactId(Setup s, String id) {
        return s.people().contacts().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /** {@code count} one-to-one conversations that fit the contact exactly, with the given text in one message. */
    private static List<PoolConversation> fitting(Setup s, Contact c, int count, Set<Fact> requires, String text, String prefix) {
        return fitting(s, c, count, requires, text, prefix, List.of("weekend"));
    }

    private static List<PoolConversation> fitting(Setup s, Contact c, int count, Set<Fact> requires, String text, String prefix,
            List<String> topics) {
        RandomGenerator r = Seed.of(prefix.hashCode(), count).stream("fit");
        List<PoolConversation> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<PoolSide> sides = List.of(TestPool.personaSide(s.persona(), s.styles().personaTowards().get(c.relation()).register()),
                    TestPool.sideFor('B', c, s.styles().contacts().get(c.id()), YEAR));
            PoolConversation base = TestPool.conversation(prefix + i, "nl", null, sides, requires, topics, false, r);
            out.add(withText(base, 3, text));
        }
        return out;
    }

    private static PoolConversation withText(PoolConversation c, int message, String text) {
        if (text == null) {
            return c;
        }
        List<PoolMessage> m = new ArrayList<>(c.messages());
        PoolMessage old = m.get(message);
        m.set(message, new PoolMessage(old.day(), old.minute(), old.side(), text));
        return new PoolConversation(c.id(), c.situation(), c.language(), c.group(), c.sides(), c.topics(), c.requires(), c.mix(), m);
    }

    /** Group conversations of a kind whose sides copy the given members exactly. */
    private static List<PoolConversation> groupsOf(Setup s, GroupKind kind, List<Contact> members, int count, String prefix) {
        RandomGenerator r = Seed.of(prefix.hashCode(), count).stream("group");
        List<PoolSide> sides = new ArrayList<>();
        sides.add(TestPool.personaSide(s.persona(), s.styles().persona().register()));
        for (int i = 0; i < members.size(); i++) {
            sides.add(TestPool.sideFor((char) ('B' + i), members.get(i), s.styles().contacts().get(members.get(i).id()), YEAR));
        }
        List<PoolConversation> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(TestPool.conversation(prefix + i, "nl", kind, sides, Set.of(), List.of("weekend"), false, r));
        }
        return out;
    }

    private static List<Contact> friendsOfOneOrigin(Setup s, int count) {
        Map<PersonaProfile.FriendGroup, List<Contact>> byOrigin = new HashMap<>();
        for (Contact c : s.people().contacts()) {
            if (c.relation() == Relation.FRIEND) {
                byOrigin.computeIfAbsent(c.origin(), k -> new ArrayList<>()).add(c);
            }
        }
        return byOrigin.values().stream().filter(l -> l.size() >= count).findFirst().orElseThrow().subList(0, count);
    }

    @Test
    void personalThreadsComeOnlyFromThePool() {
        Setup s = setup(1, b -> b);
        Picked p = pick(s, POOL, 1);
        Set<String> ids = byId(POOL).keySet();
        assertFalse(all(p).isEmpty());
        all(p).forEach(c -> assertTrue(ids.contains(c.poolId()), c.poolId()));
        People merged = PeopleSurface.withConversations(s.people(), all(p));
        Set<String> texts = new HashSet<>();
        all(p).forEach(c -> c.messages().forEach(m -> texts.add(m.body())));
        for (SmsThread t : merged.threads()) {
            if (t.senderKind() == null) {
                t.messages().forEach(m -> assertTrue(texts.contains(m.body()), "a personal text outside the pool: " + m.body()));
            }
        }
        assertTrue(s.people().threads().stream().allMatch(t -> t.senderKind() != null), "the people surface writes no personal text");
    }

    @Test
    void labelsMatchPersonaAndContact() {
        Map<String, PoolConversation> pool = byId(POOL);
        int checked = 0;
        for (long n = 0; n < 8; n++) {
            Setup s = setup(n, b -> b);
            for (Map.Entry<String, List<FilledConversation>> e : pick(s, POOL, n).byContact().entrySet()) {
                Contact c = byContactId(s, e.getKey());
                for (FilledConversation f : e.getValue()) {
                    PoolConversation pc = pool.get(f.poolId());
                    PoolSide a = pc.sides().get(0);
                    PoolSide b = pc.sides().get(1);
                    assertEquals(s.persona().language(), pc.language());
                    assertEquals(c.relation(), b.relation());
                    assertTrue(a.age().contains(s.persona().age()) && b.age().contains(YEAR - c.birthYear()));
                    assertEquals(s.persona().gender(), a.gender());
                    assertEquals(c.gender(), b.gender());
                    Register towards = s.styles().personaTowards().get(c.relation()).register();
                    assertTrue(Math.abs(a.register().ordinal() - towards.ordinal()) <= 1);
                    assertTrue(Math.abs(b.register().ordinal() - s.styles().contacts().get(c.id()).register().ordinal()) <= 1);
                    checked++;
                }
            }
        }
        assertTrue(checked > 20, "checked " + checked);
    }

    @Test
    void requiresHoldInThePlan() {
        Map<String, PoolConversation> pool = byId(POOL);
        for (long n = 0; n < 8; n++) {
            Setup s = setup(n, b -> b);
            for (FilledConversation f : all(pick(s, POOL, n))) {
                assertTrue(s.persona().facts().containsAll(pool.get(f.poolId()).requires()), f.poolId());
                assertTrue(s.persona().facts().containsAll(f.requires()));
            }
        }
        Setup s = setup(3, b -> b.relationship(PersonaProfile.Relationship.MARRIED).household(2).pets(Set.of()));
        Contact partner = contact(s, Relation.PARTNER);
        List<PoolConversation> dogs = fitting(s, partner, 5, Set.of(Fact.DOG), null, "dog-");
        assertTrue(all(pick(s, dogs, 3)).isEmpty(), "a conversation about a dog for a persona without one");
    }

    @Test
    void conversationNeedingAMissingPlaceIsNotPicked() {
        Setup s = setup(4, b -> b.relationship(PersonaProfile.Relationship.MARRIED).household(2).texting(PersonaProfile.Texting.HIGH));
        Contact partner = contact(s, Relation.PARTNER);
        List<PoolConversation> pool = new ArrayList<>();
        pool.addAll(fitting(s, partner, 3, Set.of(), "see you at {CLUB}", "club-"));
        pool.addAll(fitting(s, partner, 3, Set.of(), "ask {NAME_E} too", "fifth-"));
        pool.addAll(fitting(s, partner, 2, Set.of(), "at {PLACE_CAFE} then", "cafe-"));
        List<FilledConversation> got = pick(s, pool, 4).byContact().getOrDefault(partner.id(), List.of());
        assertEquals(2, got.size());
        got.forEach(f -> assertTrue(f.poolId().startsWith("cafe-"), f.poolId()));
    }

    @Test
    void noPoolIdTwicePerDevice() {
        for (long n = 0; n < 8; n++) {
            Picked p = pick(setup(n, b -> b), POOL, n);
            List<String> ids = new ArrayList<>();
            all(p).forEach(c -> ids.add(c.poolId()));
            p.reserve().forEach(c -> ids.add(c.poolId()));
            assertEquals(ids.size(), new HashSet<>(ids).size(), "a pool id twice");
        }
    }

    @Test
    void thinPoolGivesFewerConversationsAndAWarning() {
        Setup s = setup(5, b -> b.relationship(PersonaProfile.Relationship.MARRIED).household(2).texting(PersonaProfile.Texting.HIGH));
        Contact partner = contact(s, Relation.PARTNER);
        List<PoolConversation> pool = new ArrayList<>(fitting(s, partner, 1, Set.of(), null, "only-"));
        POOL.stream().filter(c -> c.isGroup() || c.sides().get(1).relation() != Relation.PARTNER).forEach(pool::add);
        Picked p = pick(s, pool, 5);
        assertEquals(List.of("only-0"), p.byContact().get(partner.id()).stream().map(FilledConversation::poolId).toList());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains(partner.id())), p.warnings().toString());
    }

    @Test
    void everySlotIsFilled() {
        for (long n = 0; n < 8; n++) {
            Picked p = pick(setup(n, b -> b), POOL, n);
            List<FilledConversation> every = new ArrayList<>(all(p));
            every.addAll(p.reserve());
            for (FilledConversation c : every) {
                for (SmsMessage m : c.messages()) {
                    assertFalse(m.body().contains("{") || m.body().contains("}"), m.body());
                }
            }
        }
    }

    @Test
    void namesInTheTextAreThoseOfTheConversation() {
        Setup s = setup(6, b -> b.relationship(PersonaProfile.Relationship.MARRIED).household(2));
        Contact partner = contact(s, Relation.PARTNER);
        Picked p = pick(s, fitting(s, partner, 3, Set.of(), "hi {NAME_B} it is {NAME_A}", "names-"), 6);
        String own = s.persona().inputs().ownerName().split(" ")[0];
        String first = partner.name().split(" ")[0];
        boolean seen = false;
        for (FilledConversation f : p.byContact().get(partner.id())) {
            for (SmsMessage m : f.messages()) {
                if (m.body().startsWith("hi ")) {
                    assertEquals("hi " + first + " it is " + own, m.body());
                    seen = true;
                }
            }
        }
        assertTrue(seen);
    }

    @Test
    void timesAreInTheStoryAndInWakingHours() {
        for (long n = 0; n < 6; n++) {
            Setup s = setup(n, b -> b);
            Timeline t = s.timeline();
            LocalTime wake = t.wakeTime();
            for (FilledConversation c : all(pick(s, POOL, n))) {
                Instant previous = Instant.MIN;
                for (SmsMessage m : c.messages()) {
                    assertTrue(!m.at().isBefore(t.phoneStart()) && !m.at().isAfter(t.now()), m.at().toString());
                    assertFalse(m.at().isBefore(previous), "order");
                    previous = m.at();
                    LocalTime local = m.at().atZone(t.zone()).toLocalTime();
                    assertFalse(local.isBefore(wake), local + " before waking at " + wake);
                }
            }
        }
    }

    @Test
    void judgeRejectionReplacesTheConversation() {
        Setup s = setup(7, b -> b.relationship(PersonaProfile.Relationship.MARRIED).household(2).texting(PersonaProfile.Texting.HIGH));
        Contact partner = contact(s, Relation.PARTNER);
        List<PoolConversation> pool = fitting(s, partner, 30, Set.of(), null, "p-");
        List<FilledConversation> accepted = pick(s, pool, 7).byContact().get(partner.id());
        String rejected = accepted.get(0).poolId();
        Judge once = (c, p, others) -> new Judge.Verdict(!c.poolId().equals(rejected), "rejected for the test");
        List<FilledConversation> replaced = pick(s, pool, 7, once).byContact().get(partner.id());
        assertEquals(accepted.size(), replaced.size());
        assertTrue(replaced.stream().noneMatch(c -> c.poolId().equals(rejected)));
        Judge never = (c, p, others) -> new Judge.Verdict(false, "never");
        Picked none = pick(s, pool, 7, never);
        assertTrue(none.byContact().getOrDefault(partner.id(), List.of()).isEmpty());
        assertTrue(none.warnings().stream().anyMatch(w -> w.contains(partner.id())));
    }

    @Test
    void conversationsAreRewrittenBeforeTheyAreJudged() {
        Setup s = setup(7, b -> b);
        Judge onlyRewritten = (c, p, others) -> new Judge.Verdict(c.rewritten(), "not rewritten");
        Picked p = pick(s, POOL, 7, onlyRewritten);
        assertFalse(all(p).isEmpty());
        all(p).forEach(c -> assertTrue(c.rewritten()));
        Picked raw = ConversationPicker.pick(s.persona(), s.people(), s.styles(), s.timeline(), s.pack(), POOL, Seed.of(7, 6));
        assertFalse(all(raw).isEmpty());
        all(raw).forEach(c -> assertFalse(c.rewritten()));
    }

    @Test
    void withoutPoolThePlanIsRefused() {
        PersonaInputs in = new PersonaInputs("Sam Example", "Utrecht", "NL", ZoneId.of("Europe/Amsterdam"), Locale.forLanguageTag("nl-NL"));
        List<PoolConversation> english = POOL.stream().filter(c -> c.language().equals("en")).toList();
        List<MonthCount> months = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            months.add(new MonthCount(YearMonth.of(2026, 10).minusMonths(11 - i), 60, 10, 8, 1_000_000L));
        }
        DeviceCameraProfile cam = new DeviceCameraProfile(4080, 3072, List.of(new int[64]), "PXL_\\d{8}_\\d{9}\\.jpg",
                "'PXL_'yyyyMMdd_HHmmssSSS'.jpg'", true);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ContentPlanner.build(PersonaProfile.open(in),
                new VolumeCounts(months), List.of(), List.of(), null, cam, ServerProbe.HONOURED, NOW, Seed.of(1, 1), english,
                new IdentityRewriter(), new AcceptAllJudge()));
        assertEquals("no conversation pool for nl", e.getMessage());
        ContentPlan ok = ContentPlanner.build(PersonaProfile.open(in), new VolumeCounts(months), List.of(), List.of(), null, cam,
                ServerProbe.HONOURED, NOW, Seed.of(1, 1), POOL, new IdentityRewriter(), new AcceptAllJudge());
        assertFalse(ok.conversations().isEmpty());
    }

    @Test
    void interestsSteerTopics() {
        // the partner has a hundred candidates, a tenth of them about music, and wants a handful
        int[] with = new int[2];
        int[] without = new int[2];
        for (long n = 0; n < 50; n++) {
            for (boolean steer : new boolean[] {true, false}) {
                Setup s = setup(n, b -> b.relationship(PersonaProfile.Relationship.MARRIED).household(2)
                        .texting(PersonaProfile.Texting.HIGH).interests(steer ? Set.of("music") : Set.of()));
                Contact partner = contact(s, Relation.PARTNER);
                List<PoolConversation> pool = new ArrayList<>(fitting(s, partner, 10, Set.of(), null, "music-", List.of("music")));
                pool.addAll(fitting(s, partner, 90, Set.of(), null, "other-", List.of("weekend")));
                for (FilledConversation c : pick(s, pool, n).byContact().getOrDefault(partner.id(), List.of())) {
                    int[] count = steer ? with : without;
                    count[0] += c.poolId().startsWith("music-") ? 1 : 0;
                    count[1]++;
                }
            }
        }
        double share = (double) with[0] / with[1];
        double base = (double) without[0] / without[1];
        assertTrue(share >= 2 * base, "music share " + share + " against " + base);
    }

    @Test
    void noVoiceMessagesWhenThePersonaSendsNone() {
        for (long n = 0; n < 10; n++) {
            Setup s = setup(n, b -> b.voiceNotes(false));
            for (FilledConversation c : all(pick(s, POOL, n))) {
                for (SmsMessage m : c.messages()) {
                    assertFalse(!m.incoming() && m.body().contains("[voice"), "the persona sends a voice message: " + m.body());
                }
            }
        }
    }

    @Test
    void groupsComeFromTheCircleByKind() {
        Setup s = setup(8, b -> b.friendGroups(Set.of(PersonaProfile.FriendGroup.SPORT)).circle(PersonaProfile.CircleSize.LARGE));
        List<Contact> friends = friendsOfOneOrigin(s, 3);
        List<PoolConversation> pool = new ArrayList<>(groupsOf(s, GroupKind.FRIENDS, friends, 4, "friends-"));
        pool.addAll(groupsOf(s, GroupKind.WORK_TEAM, friends, 4, "work-"));
        Picked p = pick(s, pool, 8);
        for (FilledConversation g : p.groups()) {
            assertTrue(g.poolId().startsWith("friends-"), "friends filled a work team: " + g.poolId());
            Set<PersonaProfile.FriendGroup> origins = new HashSet<>();
            for (String id : g.contactIds()) {
                Contact c = byContactId(s, id);
                assertEquals(Relation.FRIEND, c.relation());
                origins.add(c.origin());
            }
            assertEquals(1, origins.size());
        }
    }

    private static CountryPack packWithGroups(int min, int max) throws IOException {
        Properties p = new Properties();
        for (String code : List.of("XX", "NL")) {
            try (InputStream in = Packs.class.getResourceAsStream("/org/oblivios/decoy/content/data/country/" + code + ".properties")) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        }
        p.setProperty("mms.groups", min + "-" + max);
        return new DataPack(p);
    }

    @Test
    void groupCountFollowsThePack() throws IOException {
        for (int[] range : new int[][] {{2, 2}, {0, 0}}) {
            CountryPack pack = packWithGroups(range[0], range[1]);
            Setup s = setup(9, b -> b.friendGroups(Set.of(PersonaProfile.FriendGroup.SPORT)).circle(PersonaProfile.CircleSize.LARGE), pack);
            List<PoolConversation> pool = new ArrayList<>(groupsOf(s, GroupKind.FRIENDS, friendsOfOneOrigin(s, 3), 6, "g-"));
            assertEquals(range[0], pick(s, pool, 9).groups().size());
        }
    }

    @Test
    void noGroupWithoutAMemberForEverySide() throws IOException {
        CountryPack pack = packWithGroups(2, 2);
        Setup s = setup(10, b -> b.housing(PersonaProfile.Housing.OWN), pack);
        List<Contact> friends = s.people().contacts().stream().filter(c -> c.relation() == Relation.FRIEND).limit(3).toList();
        List<PoolConversation> pool = new ArrayList<>();
        for (PoolConversation c : groupsOf(s, GroupKind.HOUSE_SHARE, friends, 3, "hs-")) {
            List<PoolSide> sides = new ArrayList<>(c.sides());
            for (int i = 1; i < sides.size(); i++) {
                PoolSide x = sides.get(i);
                sides.set(i, new PoolSide(x.letter(), Relation.HOUSEMATE, x.age(), x.gender(), x.register()));
            }
            pool.add(new PoolConversation(c.id(), c.situation(), c.language(), c.group(), sides, c.topics(), c.requires(), c.mix(), c.messages()));
        }
        assertTrue(pick(s, pool, 10).groups().isEmpty());
    }

    @Test
    void groupMessagesNameTheirSender() throws IOException {
        CountryPack pack = packWithGroups(2, 2);
        Setup s = setup(11, b -> b.friendGroups(Set.of(PersonaProfile.FriendGroup.SPORT)).circle(PersonaProfile.CircleSize.LARGE), pack);
        Picked p = pick(s, groupsOf(s, GroupKind.FRIENDS, friendsOfOneOrigin(s, 4), 4, "m-"), 11);
        assertEquals(2, p.groups().size());
        for (FilledConversation g : p.groups()) {
            for (SmsMessage m : g.messages()) {
                if (m.incoming()) {
                    assertTrue(g.contactIds().contains(m.from()), "sender " + m.from());
                } else {
                    assertEquals(null, m.from());
                }
            }
        }
        People merged = PeopleSurface.withConversations(s.people(), all(p));
        List<SmsThread> groupThreads = merged.threads().stream().filter(t -> !t.members().isEmpty()).toList();
        assertEquals(2, groupThreads.size());
    }

    @Test
    void mergedThreadsKeepLastContactedTrue() {
        Setup s = setup(12, b -> b);
        People merged = PeopleSurface.withConversations(s.people(), all(pick(s, POOL, 12)));
        Map<String, Instant> latest = new HashMap<>();
        merged.calls().forEach(c -> latest.merge(c.number(), c.at(), (a, b) -> a.isAfter(b) ? a : b));
        for (SmsThread t : merged.threads()) {
            if (t.senderKind() == null && t.members().isEmpty()) {
                t.messages().forEach(m -> latest.merge(t.address(), m.at(), (a, b) -> a.isAfter(b) ? a : b));
            }
        }
        for (Contact c : merged.contacts()) {
            if (latest.containsKey(c.number())) {
                assertTrue(!c.lastContacted().isBefore(latest.get(c.number())), c.id());
            }
        }
    }

    @Test
    void rollForwardUsesOnlyTheReserve() {
        Setup s = setup(13, b -> b);
        Picked p = pick(s, POOL, 13);
        assertFalse(p.reserve().isEmpty());
        Set<String> texts = new HashSet<>();
        p.reserve().forEach(c -> c.messages().forEach(m -> texts.add(m.body())));
        ContentSnapshot snap = new ContentSnapshot(s.persona(), NOW.minus(Duration.ofDays(60)), s.people().contacts());
        int sms = 0;
        for (RollForwardEvent e : RollForward.plan(snap, NOW, Seed.of(1, 1), p.reserve()).events()) {
            if (e.kind().equals("SMS")) {
                assertTrue(texts.contains(e.detail().split("\\|", 3)[2]), e.detail());
                sms++;
            }
        }
        assertTrue(sms > 0);
        assertTrue(RollForward.plan(snap, NOW, Seed.of(1, 1), List.of()).events().stream().noneMatch(e -> e.kind().equals("SMS")));
    }

    @Test
    void sameSeedSamePick() {
        assertEquals(pick(setup(14, b -> b), POOL, 14), pick(setup(14, b -> b), POOL, 14));
    }
}
