// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.check.ExaminerRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.data.Spreads;
import org.oblivios.decoy.content.surface.CalEvent;
import org.oblivios.decoy.content.surface.SmsMessage;
import org.oblivios.decoy.content.surface.SmsThread;

/**
 * {@code G-TIDY}: a phone whose loose ends lie outside the ranges {@code tidiness.*} of the spreads, too tidy as much
 * as too loose (ADR-337). Three shares, each judged from 20 items on ({@code [assumption]}): contacts that no call,
 * voicemail or thread reaches; service texts and mails whose sender shows nowhere else, in no visit, saved login,
 * calendar entry or note; and calendar entries that left every trace the views can show, a photo within 3 hours of
 * their start and their title in a note, mail or text ({@code [assumption]}: an examiner cannot know the habits).
 */
public final class GTidy implements ExaminerRule {
    private static final int ENOUGH = 20;
    private static final Duration AT_THE_EVENT = Duration.ofHours(3);

    @Override
    public String id() {
        return "G-TIDY";
    }

    @Override
    public void check(PhoneViews v, Persona persona, CountryPack pack, Instant now, List<Violation> out) {
        Spreads spreads = Spreads.load(pack.code());
        unusedContacts(v, spreads, out);
        unexplainedMessages(v, spreads, out);
        fullTraces(v, spreads, out);
    }

    private void unusedContacts(PhoneViews v, Spreads spreads, List<Violation> out) {
        Set<String> reached = new HashSet<>();
        v.people().calls().forEach(c -> reached.add(c.number()));
        v.people().voicemails().forEach(m -> reached.add(m.fromNumber()));
        v.people().threads().forEach(t -> {
            reached.addAll(Arrays.asList(t.address().split(";")));
            reached.addAll(t.members());
        });
        long unused = v.people().contacts().stream().filter(c -> !reached.contains(c.number())).count();
        judge(unused, v.people().contacts().size(), "tidiness.unusedContacts", "contacts never used", spreads, out);
    }

    private void unexplainedMessages(PhoneViews v, Spreads spreads, List<Violation> out) {
        List<String> senders = new ArrayList<>();
        for (SmsThread t : v.people().threads()) {
            if (t.senderKind() != null) {
                t.messages().stream().filter(SmsMessage::incoming).forEach(m -> senders.add(t.address()));
            }
        }
        v.mail().messages().forEach(m -> senders.add(m.from()));
        String elsewhere = joined(Stream.of(
                v.browser().history().stream().map(x -> x.domain() + " " + x.url()),
                v.vault().entries().stream().map(x -> x.domain()),
                v.calendar().events().stream().map(CalEvent::title),
                v.calendar().notes().stream().map(x -> x.text())).flatMap(s -> s));
        long unexplained = senders.stream().filter(s -> !elsewhere.contains(plain(s))).count();
        judge(unexplained, senders.size(), "tidiness.unexplainedMessages", "service messages whose cause shows nowhere", spreads,
                out);
    }

    private void fullTraces(PhoneViews v, Spreads spreads, List<Violation> out) {
        String written = joined(Stream.of(
                v.calendar().notes().stream().map(x -> x.text()),
                v.mail().messages().stream().map(x -> x.subject() + " " + x.body()),
                v.people().threads().stream().flatMap(t -> t.messages().stream()).map(SmsMessage::body)).flatMap(s -> s));
        List<CalEvent> entries = v.calendar().events().stream().filter(e -> !e.yearly()).toList();
        long full = entries.stream()
                .filter(e -> !plain(e.title()).isEmpty() && written.contains(plain(e.title()))
                        && v.media().items().stream()
                                .anyMatch(m -> Duration.between(e.start(), m.taken()).abs().compareTo(AT_THE_EVENT) <= 0))
                .count();
        judge(full, entries.size(), "tidiness.fullTraceEvents", "calendar entries with every trace", spreads, out);
    }

    /** One line for each text, so that nothing is found across two of them. */
    private static String joined(Stream<String> texts) {
        return texts.map(GTidy::plain).collect(Collectors.joining("\n"));
    }

    private static String plain(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private void judge(long part, int all, String key, String what, Spreads spreads, List<Violation> out) {
        if (all < ENOUGH) {
            return;
        }
        double share = (double) part / all;
        double low = spreads.value(key + ".low");
        double high = spreads.value(key + ".high");
        if (share < low || share > high) {
            out.add(new Violation(id(), String.format(Locale.ROOT, "%d of %d %s: %.2f, outside %.2f to %.2f", part, all, what,
                    share, low, high)));
        }
    }
}
