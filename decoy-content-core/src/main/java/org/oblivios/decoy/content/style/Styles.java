// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.style;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.style.StyleVector.Caps;
import org.oblivios.decoy.content.surface.Contact;
import org.oblivios.decoy.content.surface.People;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.style.StyleVector.Length;
import org.oblivios.decoy.content.style.StyleVector.Punctuation;
import org.oblivios.decoy.content.style.StyleVector.Register;

/** The styles of the persona and its contacts (the style section of the persona design). */
public final class Styles {
    /** Relations towards which the persona writes one register step more formally. */
    private static final Set<Relation> FORMAL_TOWARDS = EnumSet.of(Relation.PARENT, Relation.STEP_PARENT, Relation.GRANDPARENT,
            Relation.IN_LAW, Relation.RELATIVE, Relation.COLLEAGUE, Relation.MANAGER, Relation.REPORT, Relation.CLIENT, Relation.TEACHER,
            Relation.CHILDMINDER, Relation.LANDLORD, Relation.TENANT, Relation.TRADESPERSON, Relation.MARKETPLACE);
    private static final Set<Relation> FRIENDS = EnumSet.of(Relation.FRIEND, Relation.BEST_FRIEND, Relation.OLD_FRIEND);

    private Styles() {}

    /**
     * The style of the persona (the chosen one, or its age band, with no voice when it sends none), its style towards
     * each relation, and the style of every contact, each contact from its own named stream.
     */
    public static StyleBook book(Persona p, People people, Seed s) {
        StyleVector own = p.voiceNotes() ? p.style() : p.style().withVoiceRate(0);
        Map<Relation, StyleVector> towards = new EnumMap<>(Relation.class);
        for (Relation r : Relation.values()) {
            towards.put(r, FORMAL_TOWARDS.contains(r) ? own.withRegister(step(own.register(), -1)) : own);
        }
        Map<String, StyleVector> contacts = new HashMap<>();
        for (Contact c : people.contacts()) {
            RandomGenerator r = s.stream("style." + c.id());
            int age = p.year() - c.birthYear();
            StyleVector v = ageBand(age, r);
            if (FRIENDS.contains(c.relation())) {
                Register reg = step(own.register(), r.nextInt(3) - 1);
                if (reg == Register.STREET && own.register() != Register.STREET) {
                    reg = Register.CASUAL; // street slang comes only from a street persona's friends
                }
                v = v.withRegister(reg);
            } else if (c.relation() == Relation.COLLEAGUE && v.register().ordinal() > Register.NEUTRAL.ordinal()) {
                v = v.withRegister(Register.NEUTRAL);
            }
            if (age >= 55) {
                v = new StyleVector(v.register(), Caps.STANDARD, Punctuation.FULL, v.emojiRate(), v.burst(), v.length(),
                        v.typoRate(), v.voiceRate());
            }
            contacts.put(c.id(), v);
        }
        return new StyleBook(own, towards, contacts);
    }

    /**
     * The style of a writer of this age: the band's values, each varied by at most one step or 30 percent per writer.
     * {@code STREET} never comes from the age band.
     */
    public static StyleVector ageBand(int age, RandomGenerator r) {
        StyleVector base;
        if (age < 25) {
            base = new StyleVector(Register.CASUAL, Caps.LOWER, Punctuation.LIGHT, 0.3, 3, Length.SHORT, 0.05, 0.10);
        } else if (age < 45) {
            base = new StyleVector(Register.CASUAL, Caps.MIXED, Punctuation.LIGHT, 0.2, 2, Length.MEDIUM, 0.03, 0.08);
        } else if (age < 65) {
            base = new StyleVector(Register.NEUTRAL, Caps.STANDARD, Punctuation.FULL, 0.1, 1, Length.MEDIUM, 0.02, 0.05);
        } else {
            base = new StyleVector(Register.NEUTRAL, Caps.STANDARD, Punctuation.FULL, 0.05, 1, Length.LONG, 0.03, 0.03);
        }
        Register reg = base.register();
        int roll = r.nextInt(4); // one step either way in half the writers, never into STREET
        if (roll == 0) {
            reg = step(reg, -1);
        } else if (roll == 1 && reg != Register.CASUAL) {
            reg = step(reg, 1);
        }
        Caps caps = r.nextInt(4) == 0 ? Caps.values()[r.nextInt(Caps.values().length)] : base.caps();
        Punctuation punct = r.nextInt(4) == 0 ? neighbour(base.punctuation(), r) : base.punctuation();
        Length length = r.nextInt(4) == 0 ? neighbour(base.length(), r) : base.length();
        int burst = Math.max(1, base.burst() + r.nextInt(3) - 1);
        return new StyleVector(reg, caps, punct, vary(base.emojiRate(), r), burst, length, vary(base.typoRate(), r),
                Math.min(1.0, vary(base.voiceRate(), r)));
    }

    /** The register {@code steps} away (negative is more formal), bounded at both ends. */
    public static Register step(Register r, int steps) {
        int i = Math.max(0, Math.min(Register.values().length - 1, r.ordinal() + steps));
        return Register.values()[i];
    }

    private static double vary(double v, RandomGenerator r) {
        return v * (0.7 + 0.6 * r.nextDouble());
    }

    private static <E extends Enum<E>> E neighbour(E e, RandomGenerator r) {
        E[] all = e.getDeclaringClass().getEnumConstants();
        int i = Math.max(0, Math.min(all.length - 1, e.ordinal() + (r.nextBoolean() ? 1 : -1)));
        return all[i];
    }
}
