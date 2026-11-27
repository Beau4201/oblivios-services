// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.persona.PersonaProfile.Fact;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.style.StyleVector;
import org.oblivios.decoy.content.style.StyleVector.Register;
import org.oblivios.decoy.content.surface.Contact;
import org.oblivios.decoy.content.surface.Relation;

/** Test support: pools of random-token conversations, and the identity rewriter and accept-all judge. */
public final class TestPool {
    private static final List<String> TOPICS = List.of("food", "sport", "work", "music", "weekend", "travel", "family", "money",
            "health", "school");
    private static final String[] SLOTS = {"{NAME_B}", "{DAY}", "{TIME}", "{AMOUNT}", "{PLACE_CAFE}", "{NAME_OTHER}", "{NAME_A}"};

    private TestPool() {}

    /** Returns its input, marked as rewritten. */
    public static final class IdentityRewriter implements Rewriter {
        @Override
        public FilledConversation rewrite(FilledConversation c, StyleVector owner, List<StyleVector> others, String language) {
            return new FilledConversation(c.poolId(), c.situation(), c.group(), c.contactIds(), c.requires(), c.messages(), true);
        }
    }

    /** Accepts every conversation. */
    public static final class AcceptAllJudge implements Judge {
        @Override
        public Verdict judge(FilledConversation c, Persona p, List<Contact> others) {
            return new Verdict(true, "");
        }
    }

    /**
     * {@code n} conversations of random tokens over every relation, group kind, age band and register, in both
     * languages; every one passes the schema.
     */
    public static List<PoolConversation> random(int n, Seed s) {
        RandomGenerator r = s.stream("testpool");
        List<PoolConversation> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String lang = i % 2 == 0 ? "en" : "nl";
            boolean group = r.nextInt(5) == 0;
            List<PoolSide> sides = new ArrayList<>();
            sides.add(new PoolSide('A', null, band(r), gender(r), register(r)));
            GroupKind kind = null;
            if (group) {
                kind = GroupKind.values()[r.nextInt(GroupKind.values().length)];
                int count = 3 + r.nextInt(4);
                for (int k = 1; k < count; k++) {
                    sides.add(new PoolSide((char) ('A' + k), Relation.values()[r.nextInt(Relation.SELF.ordinal())], band(r), gender(r), register(r)));
                }
            } else {
                sides.add(new PoolSide('B', Relation.values()[r.nextInt(Relation.SELF.ordinal())], band(r), gender(r), register(r)));
            }
            Set<Fact> requires = r.nextInt(100) < 85 ? Set.of() : Set.of(Fact.values()[r.nextInt(Fact.values().length)]);
            List<String> topics = List.of(TOPICS.get(r.nextInt(TOPICS.size())));
            out.add(conversation(lang + "-t-" + i, lang, kind, sides, requires, topics, true, r));
        }
        return out;
    }

    /** A conversation with the given labels and random-token texts that pass the schema; voice messages only when asked. */
    public static PoolConversation conversation(String id, String lang, GroupKind kind, List<PoolSide> sides, Set<Fact> requires,
            List<String> topics, boolean voice, RandomGenerator r) {
        int n = sides.size();
        int count = kind == null ? 15 + r.nextInt(26) : 15 + r.nextInt(46);
        int lastDay = kind == null ? 2 : 6;
        List<PoolMessage> messages = new ArrayList<>();
        int day = -1;
        int j = 0;
        for (int k = 0; k < count; k++) {
            int d = k * (lastDay + 1) / count;
            j = d == day ? j + 1 : 0;
            day = d;
            char side = k < n ? (char) ('A' + k) : (char) ('A' + r.nextInt(n));
            messages.add(new PoolMessage(d, 8 * 60 + j * 6 + r.nextInt(5), side, text(r, voice)));
        }
        PoolConversation c = new PoolConversation(id, "test-situation", lang, kind, sides, topics, requires, "", messages);
        List<String> problems = PoolSchema.check(c);
        if (!problems.isEmpty()) {
            throw new AssertionError("test pool breaks the schema: " + problems);
        }
        return c;
    }

    /** The side that fits a contact exactly. */
    public static PoolSide sideFor(char letter, Contact c, StyleVector v, int year) {
        int age = year - c.birthYear();
        return new PoolSide(letter, c.relation(), new AgeBand(age, age), c.gender(), v.register());
    }

    /** Side A that fits the persona exactly, writing in the given register. */
    public static PoolSide personaSide(Persona p, Register register) {
        return new PoolSide('A', null, new AgeBand(p.age(), p.age()), p.gender(), register);
    }

    private static String text(RandomGenerator r, boolean voice) {
        if (voice && r.nextInt(30) == 0) {
            return "[voice 0:" + (10 + r.nextInt(50)) + "]";
        }
        StringBuilder sb = new StringBuilder("tok" + r.nextInt(100_000));
        int words = 2 + r.nextInt(6);
        for (int w = 0; w < words; w++) {
            sb.append(' ');
            if (r.nextInt(6) == 0) {
                sb.append(SLOTS[r.nextInt(SLOTS.length)]);
            } else {
                sb.append("tok").append(r.nextInt(100_000));
            }
        }
        return sb.toString();
    }

    private static AgeBand band(RandomGenerator r) {
        if (r.nextInt(100) < 60) {
            return new AgeBand(0, 110);
        }
        int decade = 1 + r.nextInt(8);
        return new AgeBand(decade * 10, decade * 10 + 9);
    }

    private static Gender gender(RandomGenerator r) {
        return r.nextBoolean() ? Gender.FEMALE : Gender.MALE;
    }

    private static Register register(RandomGenerator r) {
        return Register.values()[r.nextInt(Register.values().length)];
    }

}
