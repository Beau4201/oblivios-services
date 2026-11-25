// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.pool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.oblivios.decoy.content.lang.LanguagePack;

/**
 * The rules a pool conversation must meet (format version 1). A one-to-one conversation has two sides and 15 to 40
 * messages over days 0 to 2; a group has three to eight sides, a group kind and 15 to 60 messages over days 0 to 6, in
 * which the persona and at least two others write. Texts carry names, places and clubs only as slots, and no number,
 * address or link that could reach a real person.
 */
public final class PoolSchema {
    /** A rule a conversation breaks; {@code message} is the index of the message, or -1 for the header. */
    record Problem(int message, String text) {}

    private static final Pattern SITUATION = Pattern.compile("[a-z0-9-]+");
    private static final Pattern SLOT = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern DIGITS = Pattern.compile("\\d{6,}");
    private static final Pattern LINK = Pattern.compile("(?i)http|www\\.");
    private static final Pattern ADDRESS = Pattern.compile("@\\p{L}");
    private static final Set<String> SLOTS = new HashSet<>();

    static {
        for (Slot s : Slot.values()) {
            SLOTS.add(s.name());
        }
    }

    private PoolSchema() {}

    /** Every rule the conversation breaks, in reading order; empty when it passes. */
    public static List<String> check(PoolConversation c) {
        return problems(c).stream().map(p -> (p.message() < 0 ? "header" : "message " + (p.message() + 1)) + ": " + p.text()).toList();
    }

    static List<Problem> problems(PoolConversation c) {
        List<Problem> out = new ArrayList<>();
        if (!LanguagePack.available().contains(c.language())) {
            out.add(new Problem(-1, "language '" + c.language() + "' has no data"));
        }
        if (c.situation() == null || !SITUATION.matcher(c.situation()).matches()) {
            out.add(new Problem(-1, "situation id '" + c.situation() + "' is not [a-z0-9-]+"));
        }
        int n = c.sides().size();
        if (n < 2 || n > 8) {
            out.add(new Problem(-1, n + " sides: a conversation has 2 to 8"));
        }
        for (int i = 0; i < n; i++) {
            PoolSide s = c.sides().get(i);
            if (s.letter() != (char) ('A' + i)) {
                out.add(new Problem(-1, "side " + (i + 1) + " is '" + s.letter() + "', expected '" + (char) ('A' + i) + "'"));
            }
            if ((i == 0) != (s.relation() == null)) {
                out.add(new Problem(-1, i == 0 ? "side A is the persona and has no relation" : "side " + s.letter() + " has no relation"));
            }
            if (s.age() == null || s.gender() == null || s.register() == null) {
                out.add(new Problem(-1, "side " + s.letter() + " lacks a label"));
            }
        }
        boolean group = n > 2;
        if (group != c.isGroup()) {
            out.add(new Problem(-1, group ? n + " sides need a group kind" : "a group kind needs more than two sides"));
        }
        int min = 15;
        int max = group ? 60 : 40;
        int lastDay = group ? 6 : 2;
        if (c.messages().size() < min || c.messages().size() > max) {
            out.add(new Problem(-1, c.messages().size() + " messages, the rule is " + min + " to " + max));
        }
        Set<Character> writers = new HashSet<>();
        for (PoolMessage m : c.messages()) {
            writers.add(m.side());
        }
        if (!group && n == 2 && writers.size() < 2) {
            out.add(new Problem(-1, "only one side writes"));
        }
        if (group && (!writers.contains('A') || writers.size() < 3)) {
            out.add(new Problem(-1, "the persona and at least two others must write in a group"));
        }
        int previous = -1;
        for (int i = 0; i < c.messages().size(); i++) {
            PoolMessage m = c.messages().get(i);
            int at = m.day() * 1440 + m.minute();
            if (m.day() < 0 || m.day() > lastDay) {
                out.add(new Problem(i, "day " + m.day() + " outside 0 to " + lastDay));
            }
            if (m.minute() < 0 || m.minute() >= 1440) {
                out.add(new Problem(i, "minute " + m.minute() + " outside the day"));
            }
            if (at < previous) {
                out.add(new Problem(i, "time goes back"));
            }
            previous = Math.max(previous, at);
            if (m.side() < 'A' || m.side() >= 'A' + n) {
                out.add(new Problem(i, "side " + m.side() + " is not a side of the conversation"));
            }
            text(m.text(), i, out);
        }
        return out;
    }

    private static void text(String t, int i, List<Problem> out) {
        if (t == null || t.isEmpty() || t.length() > 500) {
            out.add(new Problem(i, "text of " + (t == null ? 0 : t.length()) + " characters, the rule is 1 to 500"));
            return;
        }
        if (t.indexOf('\t') >= 0) {
            out.add(new Problem(i, "tab in the text"));
        }
        Matcher m = SLOT.matcher(t);
        while (m.find()) {
            if (!SLOTS.contains(m.group(1))) {
                out.add(new Problem(i, "unknown slot " + m.group()));
            }
        }
        String rest = SLOT.matcher(t).replaceAll("");
        if (rest.indexOf('{') >= 0 || rest.indexOf('}') >= 0) {
            out.add(new Problem(i, "unbalanced brace in '" + t + "'"));
        }
        contactData(t).forEach(found -> out.add(new Problem(i, found)));
    }

    /** What in a text could reach a real person: a run of 6 or more digits, a link or an address. */
    public static List<String> contactData(String text) {
        List<String> out = new ArrayList<>();
        if (DIGITS.matcher(text).find()) {
            out.add("a run of 6 or more digits");
        }
        if (LINK.matcher(text).find()) {
            out.add("a link");
        }
        if (ADDRESS.matcher(text).find()) {
            out.add("an address");
        }
        return out;
    }
}
