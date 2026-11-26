// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.pool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.oblivios.decoy.content.persona.PersonaProfile.Fact;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.style.StyleVector.Register;
import org.oblivios.decoy.content.surface.Relation;

/**
 * Reads a pool file, format version 1 (UTF-8, one conversation per block):
 *
 * <pre>
 * #conv id=&lt;id&gt; sit=&lt;situation&gt; lang=&lt;tag&gt; group=&lt;GROUP_KIND&gt; rel=-/&lt;RELATION&gt;/... age=&lt;from&gt;-&lt;to&gt;/...
 *       gender=&lt;G&gt;/... reg=&lt;R&gt;/... topics=&lt;a,b&gt; requires=&lt;FACT,...&gt; mix=&lt;tag&gt;
 * &lt;day&gt; &lt;HH:MM&gt; &lt;A-H&gt; &lt;text&gt;
 * </pre>
 *
 * ({@code -} for an empty list; the header is one line). A byte-order mark and CRLF line ends are accepted. A
 * conversation with any error is skipped whole and gives one error that starts with its line; the rest is read.
 */
public final class PoolReader {
    private static final List<String> KEYS = List.of("id", "sit", "lang", "group", "rel", "age", "gender", "reg", "topics", "requires", "mix");

    private PoolReader() {}

    private record Line(int number, String text) {}

    /** A header or message that cannot be parsed. */
    private static final class Bad extends Exception {
        private static final long serialVersionUID = 1L;

        final int line;

        Bad(int line, String message) {
            super(message);
            this.line = line;
        }
    }

    public static PoolRead read(Reader in) {
        List<PoolConversation> conversations = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<Line> block = null;
        try (BufferedReader r = new BufferedReader(in)) {
            String text;
            int number = 0;
            while ((text = r.readLine()) != null) {
                number++;
                if (number == 1 && text.startsWith("﻿")) {
                    text = text.substring(1);
                }
                if (text.startsWith("#conv")) {
                    flush(block, conversations, errors);
                    block = new ArrayList<>();
                    block.add(new Line(number, text));
                } else if (text.isBlank()) {
                    continue;
                } else if (block == null) {
                    errors.add("line " + number + ": text outside a conversation");
                } else {
                    block.add(new Line(number, text));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        flush(block, conversations, errors);
        return new PoolRead(conversations, errors);
    }

    private static void flush(List<Line> block, List<PoolConversation> out, List<String> errors) {
        if (block == null) {
            return;
        }
        try {
            PoolConversation c = parse(block);
            List<PoolSchema.Problem> problems = PoolSchema.problems(c);
            if (problems.isEmpty()) {
                out.add(c);
            } else {
                PoolSchema.Problem p = problems.get(0);
                int line = p.message() < 0 ? block.get(0).number() : block.get(p.message() + 1).number();
                errors.add("line " + line + ": " + c.id() + ": " + p.text());
            }
        } catch (Bad e) {
            errors.add("line " + e.line + ": " + e.getMessage());
        }
    }

    private static PoolConversation parse(List<Line> block) throws Bad {
        Line head = block.get(0);
        int h = head.number();
        Map<String, String> v = new HashMap<>();
        String[] tokens = head.text().trim().split(" +");
        if (!tokens[0].equals("#conv")) {
            throw new Bad(h, "header does not start with #conv");
        }
        for (int i = 1; i < tokens.length; i++) {
            int eq = tokens[i].indexOf('=');
            if (eq <= 0) {
                throw new Bad(h, "header token '" + tokens[i] + "' is not key=value");
            }
            String key = tokens[i].substring(0, eq);
            if (!KEYS.contains(key)) {
                throw new Bad(h, "unknown header key '" + key + "'");
            }
            if (v.put(key, tokens[i].substring(eq + 1)) != null) {
                throw new Bad(h, "header key '" + key + "' twice");
            }
        }
        for (String key : KEYS) {
            if (!v.containsKey(key) || v.get(key).isEmpty()) {
                throw new Bad(h, "header lacks '" + key + "'");
            }
        }
        String id = v.get("id");
        String[] rel = v.get("rel").split("/", -1);
        String[] age = v.get("age").split("/", -1);
        String[] gender = v.get("gender").split("/", -1);
        String[] reg = v.get("reg").split("/", -1);
        int n = rel.length;
        if (age.length != n || gender.length != n || reg.length != n) {
            throw new Bad(h, id + ": rel, age, gender and reg have " + n + ", " + age.length + ", " + gender.length + " and "
                    + reg.length + " entries");
        }
        if (n > 26) {
            throw new Bad(h, id + ": " + n + " sides");
        }
        List<PoolSide> sides = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Relation relation = null;
            if (i == 0) {
                if (!rel[0].equals("-")) {
                    throw new Bad(h, id + ": side A is the persona, its relation is '-'");
                }
            } else {
                relation = value(Relation.class, rel[i], h, id);
            }
            sides.add(new PoolSide((char) ('A' + i), relation, band(age[i], h, id), value(Gender.class, gender[i], h, id),
                    value(Register.class, reg[i], h, id)));
        }
        GroupKind group = v.get("group").equals("-") ? null : value(GroupKind.class, v.get("group"), h, id);
        List<String> topics = v.get("topics").equals("-") ? List.of() : List.of(v.get("topics").split(","));
        Set<Fact> requires = EnumSet.noneOf(Fact.class);
        if (!v.get("requires").equals("-")) {
            for (String f : v.get("requires").split(",")) {
                requires.add(value(Fact.class, f, h, id));
            }
        }
        String mix = v.get("mix").equals("-") ? "" : v.get("mix");

        List<PoolMessage> messages = new ArrayList<>();
        for (int i = 1; i < block.size(); i++) {
            messages.add(message(block.get(i), id));
        }
        return new PoolConversation(id, v.get("sit"), v.get("lang"), group, sides, topics, requires, mix, messages);
    }

    private static PoolMessage message(Line line, String id) throws Bad {
        String[] p = line.text().split(" ", 4);
        if (p.length < 4 || p[3].isEmpty()) {
            throw new Bad(line.number(), id + ": message without day, time, side and text");
        }
        try {
            int day = Integer.parseInt(p[0]);
            String[] hm = p[1].split(":");
            if (hm.length != 2 || hm[0].length() != 2 || hm[1].length() != 2) {
                throw new NumberFormatException("time is not HH:MM");
            }
            int hour = Integer.parseInt(hm[0]);
            int minute = Integer.parseInt(hm[1]);
            if (hour > 23 || minute > 59 || hour < 0 || minute < 0) {
                throw new NumberFormatException("time " + p[1] + " outside the day");
            }
            if (p[2].length() != 1) {
                throw new NumberFormatException("side '" + p[2] + "' is not one letter");
            }
            return new PoolMessage(day, hour * 60 + minute, p[2].charAt(0), p[3]);
        } catch (NumberFormatException e) {
            throw new Bad(line.number(), id + ": " + e.getMessage());
        }
    }

    private static AgeBand band(String s, int h, String id) throws Bad {
        String[] ends = s.split("-");
        try {
            return new AgeBand(Integer.parseInt(ends[0]), Integer.parseInt(ends[ends.length == 2 ? 1 : 0]));
        } catch (RuntimeException e) {
            throw new Bad(h, id + ": age band '" + s + "'");
        }
    }

    private static <E extends Enum<E>> E value(Class<E> type, String name, int h, String id) throws Bad {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new Bad(h, id + ": '" + name + "' is no " + type.getSimpleName());
        }
    }
}
