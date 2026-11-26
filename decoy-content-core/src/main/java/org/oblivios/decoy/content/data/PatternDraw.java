// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/**
 * Draws digit strings that match a pattern of the phone number metadata. It reads only the constructs that metadata
 * uses: digits, {@code \d}, classes of digits with ranges, groups without a capture, alternatives, {@code {n}},
 * {@code {m,n}} and {@code ?}; anything else is refused. A pattern is unfolded into the sequences of digit sets it
 * stands for, so a draw is exact in its length and never has to be tried again.
 */
public final class PatternDraw {
    private static final int ALL = (1 << 10) - 1;
    /** No pattern of the metadata comes near these; a pattern that does is refused, not unfolded. */
    private static final int MAX_LENGTH = 20;
    private static final int MAX_SEQUENCES = 200_000;
    private static final Map<String, List<int[]>> UNFOLDED = new ConcurrentHashMap<>();

    private PatternDraw() {}

    /**
     * A string that matches {@code pattern} and has one of {@code lengths}; throws {@link IllegalArgumentException}
     * for a construct that is not read, and for a pattern that has no string of those lengths.
     */
    public static String draw(String pattern, Set<Integer> lengths, RandomGenerator r) {
        String found = draw(pattern, lengths, "", r);
        if (found == null) {
            throw new IllegalArgumentException("no string of length " + lengths + " matches " + pattern);
        }
        return found;
    }

    /** As {@link #draw}, among the strings that start with the digits of {@code prefix}; null when there is none. */
    static String draw(String pattern, Set<Integer> lengths, String prefix, RandomGenerator r) {
        List<int[]> fit = new ArrayList<>();
        List<Double> upTo = new ArrayList<>();
        double sum = 0;
        for (int[] sequence : UNFOLDED.computeIfAbsent(pattern, PatternDraw::unfold)) {
            if (!lengths.contains(sequence.length) || sequence.length < prefix.length()) {
                continue;
            }
            double strings = 1;
            for (int i = 0; i < sequence.length && strings > 0; i++) {
                strings *= i < prefix.length() ? (sequence[i] >> (prefix.charAt(i) - '0')) & 1
                        : Integer.bitCount(sequence[i]);
            }
            if (strings > 0) {
                sum += strings;
                fit.add(sequence);
                upTo.add(sum);
            }
        }
        if (fit.isEmpty()) {
            return null;
        }
        double roll = r.nextDouble() * sum;
        int chosen = 0;
        while (chosen < fit.size() - 1 && roll >= upTo.get(chosen)) {
            chosen++;
        }
        int[] sequence = fit.get(chosen);
        StringBuilder out = new StringBuilder(prefix);
        for (int i = prefix.length(); i < sequence.length; i++) {
            int nth = r.nextInt(Integer.bitCount(sequence[i]));
            int mask = sequence[i];
            for (int k = 0; k < nth; k++) {
                mask &= mask - 1;
            }
            out.append((char) ('0' + Integer.numberOfTrailingZeros(mask)));
        }
        return out.toString();
    }

    private static List<int[]> unfold(String pattern) {
        Parser p = new Parser(pattern);
        List<int[]> out = p.alternatives();
        if (p.at < pattern.length()) {
            throw p.refused();
        }
        return out;
    }

    private static final class Parser {
        private final String pattern;
        private int at;

        Parser(String pattern) {
            this.pattern = pattern;
        }

        IllegalArgumentException refused() {
            return new IllegalArgumentException("unsupported construct at " + at + " of pattern " + pattern);
        }

        private boolean take(char c) {
            if (at < pattern.length() && pattern.charAt(at) == c) {
                at++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!take(c)) {
                throw refused();
            }
        }

        private char next() {
            if (at >= pattern.length()) {
                throw refused();
            }
            return pattern.charAt(at++);
        }

        private int digit() {
            char c = next();
            if (c < '0' || c > '9') {
                at--;
                throw refused();
            }
            return c - '0';
        }

        List<int[]> alternatives() {
            List<int[]> out = new ArrayList<>(sequence());
            while (take('|')) {
                out.addAll(sequence());
                bounded(out.size());
            }
            return out;
        }

        private List<int[]> sequence() {
            List<int[]> out = List.of(new int[0]);
            while (at < pattern.length() && pattern.charAt(at) != '|' && pattern.charAt(at) != ')') {
                out = join(out, counted(atom()));
            }
            return out;
        }

        private List<int[]> atom() {
            char c = next();
            if (c >= '0' && c <= '9') {
                return List.<int[]>of(new int[] {1 << (c - '0')});
            }
            if (c == '\\') {
                expect('d');
                return List.<int[]>of(new int[] {ALL});
            }
            if (c == '[') {
                int mask = 0;
                do {
                    int first = digit();
                    int last = take('-') ? digit() : first;
                    if (last < first) {
                        throw refused();
                    }
                    for (int d = first; d <= last; d++) {
                        mask |= 1 << d;
                    }
                } while (!take(']'));
                return List.<int[]>of(new int[] {mask});
            }
            if (c == '(') {
                expect('?');
                expect(':');
                List<int[]> inside = alternatives();
                expect(')');
                return inside;
            }
            at--;
            throw refused();
        }

        private List<int[]> counted(List<int[]> atom) {
            int min = 1;
            int max = 1;
            if (take('?')) {
                min = 0;
            } else if (take('{')) {
                min = number();
                max = take(',') ? number() : min;
                expect('}');
                if (max < min) {
                    throw refused();
                }
            }
            List<int[]> repeated = List.of(new int[0]);
            List<int[]> out = new ArrayList<>();
            for (int n = 0; n <= max; n++) {
                if (n >= min) {
                    out.addAll(repeated);
                }
                if (n < max) {
                    repeated = join(repeated, atom);
                }
            }
            return out;
        }

        private int number() {
            int n = digit();
            while (at < pattern.length() && pattern.charAt(at) >= '0' && pattern.charAt(at) <= '9') {
                n = 10 * n + digit();
                if (n > MAX_LENGTH) {
                    throw refused();
                }
            }
            return n;
        }

        private List<int[]> join(List<int[]> heads, List<int[]> tails) {
            bounded((long) heads.size() * tails.size());
            Set<Joined> out = new LinkedHashSet<>();
            for (int[] head : heads) {
                for (int[] tail : tails) {
                    if (head.length + tail.length > MAX_LENGTH) {
                        throw refused();
                    }
                    int[] both = java.util.Arrays.copyOf(head, head.length + tail.length);
                    System.arraycopy(tail, 0, both, head.length, tail.length);
                    out.add(new Joined(both));
                }
            }
            return out.stream().map(Joined::masks).toList();
        }

        private void bounded(long sequences) {
            if (sequences > MAX_SEQUENCES) {
                throw refused();
            }
        }
    }

    /** A sequence compared by its digit sets, so one that two branches give is kept once. */
    private record Joined(int[] masks) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Joined j && java.util.Arrays.equals(masks, j.masks);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(masks);
        }
    }
}
