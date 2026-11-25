// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Sender;
import org.oblivios.decoy.content.country.SenderKind;
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

    private static final Map<SenderKind, List<String[]>> EN = new EnumMap<>(SenderKind.class);
    private static final Map<SenderKind, List<String[]>> NL = new EnumMap<>(SenderKind.class);

    static {
        EN.put(SenderKind.BANK, List.of(
                new String[] {"Your statement is ready", "Your latest statement is ready in your online banking."},
                new String[] {"New sign-in to online banking", "We noticed a new sign-in to your online banking. If this was you, no action is needed."},
                new String[] {"Payment received", "A payment was credited to your account."}));
        NL.put(SenderKind.BANK, List.of(
                new String[] {"Uw afschrift staat klaar", "Uw nieuwste afschrift staat klaar in internetbankieren."},
                new String[] {"Nieuwe inlog bij internetbankieren", "Er is een nieuwe inlog bij uw internetbankieren gezien. Was u dit zelf, dan hoeft u niets te doen."},
                new String[] {"Betaling ontvangen", "Er is een betaling op uw rekening bijgeschreven."}));
        EN.put(SenderKind.PARCEL, List.of(
                new String[] {"Your parcel is on its way", "Your parcel has left the depot and will be delivered soon."},
                new String[] {"Delivery notification", "Your parcel was delivered to your address."},
                new String[] {"We missed you", "We could not deliver your parcel. You can pick it up at a nearby service point."}));
        NL.put(SenderKind.PARCEL, List.of(
                new String[] {"Uw pakket is onderweg", "Uw pakket heeft het depot verlaten en wordt binnenkort bezorgd."},
                new String[] {"Bezorgmelding", "Uw pakket is op uw adres bezorgd."},
                new String[] {"We troffen u niet thuis", "We konden uw pakket niet bezorgen. U kunt het ophalen bij een afhaalpunt in de buurt."}));
        EN.put(SenderKind.OPERATOR, List.of(
                new String[] {"Your monthly invoice is ready", "Your invoice for last month is available in your account."},
                new String[] {"Your data bundle is almost used", "You have used most of your mobile data this month."},
                new String[] {"Your plan has been renewed", "Your plan was renewed for another month."}));
        NL.put(SenderKind.OPERATOR, List.of(
                new String[] {"Uw maandfactuur staat klaar", "Uw factuur van vorige maand staat klaar in uw account."},
                new String[] {"Uw databundel is bijna op", "U heeft het grootste deel van uw mobiele data van deze maand gebruikt."},
                new String[] {"Uw abonnement is verlengd", "Uw abonnement is met een maand verlengd."}));
        EN.put(SenderKind.SHOP, List.of(
                new String[] {"Your order has shipped", "Your order is on its way."},
                new String[] {"Your receipt", "Thank you for your order. Your receipt is available in your account."},
                new String[] {"Weekend deals", "This weekend: a selection of offers just for you."}));
        NL.put(SenderKind.SHOP, List.of(
                new String[] {"Uw bestelling is verzonden", "Uw bestelling is onderweg."},
                new String[] {"Uw bon", "Bedankt voor uw bestelling. Uw bon staat klaar in uw account."},
                new String[] {"Weekendaanbiedingen", "Dit weekend: een selectie aanbiedingen speciaal voor u."}));
        EN.put(SenderKind.GLOBAL, List.of(
                new String[] {"Security alert", "We noticed a new sign-in to your account."},
                new String[] {"Confirm your email address", "Please confirm this email address to keep your account secure."},
                new String[] {"Your password was changed", "The password of your account was changed. If this was not you, reset it now."}));
        NL.put(SenderKind.GLOBAL, List.of(
                new String[] {"Beveiligingsmelding", "We zagen een nieuwe inlog bij uw account."},
                new String[] {"Bevestig uw e-mailadres", "Bevestig dit e-mailadres om uw account veilig te houden."},
                new String[] {"Uw wachtwoord is gewijzigd", "Het wachtwoord van uw account is gewijzigd. Was u dit niet, stel het dan nu opnieuw in."}));
    }

    private MailSurface() {}

    /** The IMAP internal date of an appended message: {@code 01-Jan-2023 09:15:00 +0100}. */
    public static String appendDate(Instant when, ZoneId zone) {
        return APPEND.withZone(zone).format(when);
    }

    public static MailPlan generate(Persona p, Timeline t, CountryPack c, ServerProbe probe, Seed s) {
        if (probe == ServerProbe.IGNORED) {
            return new MailPlan(List.of(), Optional.of("The mail server ignores the date of an appended message, so a mailbox of old mail "
                    + "cannot be built here: every message would carry today's date, which is a tell. Use another address, or skip the mailbox step."));
        }
        RandomGenerator r = s.stream("mail");
        ZoneId zone = t.zone();
        boolean dutch = "nl".equals(p.inputs().locale().getLanguage());

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
        String first = p.inputs().ownerName().split(" ")[0];
        int count = 25 + r.nextInt(120);
        List<MailMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SenderKind kind = kinds.get(r.nextInt(kinds.size()));
            List<Sender> senders = c.senders(kind);
            Sender sender = senders.get(r.nextInt(senders.size()));
            List<String[]> templates = (dutch ? NL : EN).get(kind);
            String[] template = templates.get(r.nextInt(templates.size()));
            String body = dutch
                    ? "Beste " + first + ",\n\n" + template[1] + "\n\nMet vriendelijke groet,\n" + sender.name()
                    : "Hello " + first + ",\n\n" + template[1] + "\n\nKind regards,\n" + sender.name();
            Instant at = Events.randomInstant(r, start, end, zone, LocalTime.of(0, 0), LocalTime.of(23, 59));
            messages.add(new MailMessage(sender.name(), template[0], body, at));
        }
        messages.sort(Comparator.comparing(MailMessage::date).thenComparing(MailMessage::from).thenComparing(MailMessage::subject));
        return new MailPlan(messages, Optional.empty());
    }
}
