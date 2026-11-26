// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Sender;
import org.oblivios.decoy.content.country.SenderKind;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.story.Timeline;

/**
 * Old mail for the decoy mailbox: automated mail of the country pack's senders, dated from 6 years back to 6 months
 * back and never before the phone existed, so the inbox is not all dated today. There is no personal mail, because
 * a contact has no address and an invented one could belong to a real person, and no welcome mail, because the mail
 * of the apps the owner signs in to is real (ADR-317). When the server ignores the date of an appended message the
 * plan is empty and carries the notice that refuses the step.
 */
public final class MailSurface {
    private static final DateTimeFormatter APPEND = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss Z", Locale.US);

    private MailSurface() {}

    /** The IMAP internal date of an appended message: {@code 01-Jan-2023 09:15:00 +0100}. */
    public static String appendDate(Instant when, ZoneId zone) {
        return APPEND.withZone(zone).format(when);
    }

    public static MailPlan generate(Persona p, Timeline t, CountryPack c, ServerProbe probe, Draws s) {
        if (probe == ServerProbe.IGNORED) {
            return new MailPlan(List.of(), Optional.of("The mail server ignores the date of an appended message, so a mailbox of old mail "
                    + "cannot be built here: every message would carry today's date, which is a tell. Use another address, or skip the mailbox step."));
        }
        RandomGenerator r = s.stream("mail");
        ZoneId zone = t.zone();
        // mail from a sender of the country is written in the country's language (ADR-321)
        LanguagePack lang = LanguagePack.forService(c, p.language());

        List<SenderKind> kinds = new ArrayList<>();
        for (SenderKind k : SenderKind.values()) {
            if (!c.senders(k).isEmpty()) {
                kinds.add(k);
            }
        }
        if (kinds.isEmpty()) {
            throw new IllegalArgumentException("country pack " + c.code() + " has no sender: no mail can be created");
        }

        Instant start = Instant.ofEpochMilli(Math.max(t.now().atZone(zone).minusYears(6).toInstant().toEpochMilli(), t.phoneStart().toEpochMilli()));
        Instant end = t.now().atZone(zone).minusMonths(6).toInstant();
        // shortcut: a story under six months long has no room for the rule, so the window opens at the phone start; the timeline guarantees 180 days
        if (!start.isBefore(end)) {
            end = start.plus(Duration.ofDays(30));
        }
        String first = p.inputs().givenName().split(" ")[0];
        int count = 25 + r.nextInt(120);
        List<MailMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SenderKind kind = kinds.get(r.nextInt(kinds.size()));
            List<Sender> senders = c.senders(kind);
            Sender sender = senders.get(r.nextInt(senders.size()));
            int templates = 0;
            while (lang.has("mail." + kind.name() + "." + (templates + 1) + ".subject")) {
                templates++;
            }
            String template = "mail." + kind.name() + "." + (1 + r.nextInt(templates));
            String body = lang.format("mail.greeting", Map.of("name", first)) + "\n\n" + lang.text(template + ".body")
                    + "\n\n" + lang.text("mail.closing") + "\n" + sender.name();
            Instant at = Events.randomInstant(r, start, end, zone, LocalTime.of(0, 0), LocalTime.of(23, 59));
            messages.add(new MailMessage(sender.name(), lang.text(template + ".subject"), body, at));
        }
        messages.sort(Comparator.comparing(MailMessage::date).thenComparing(MailMessage::from).thenComparing(MailMessage::subject));
        return new MailPlan(messages, Optional.empty());
    }
}
