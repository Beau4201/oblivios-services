// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;

/**
 * The text of a message between the decoy owner and a contact. There is no shared list of sentences: a message is a
 * short universal reply (about one in four, two words at most) or a template whose slots come from the persona's own
 * world, so a message names a contact, a cafe or shop of the timeline, a weekday, a time or a number of minutes.
 * Every longer template has at least two slots, which keeps the same sentence from recurring on many phones.
 * Limit: the templates themselves are public in the source, so an examiner who reads it can match their shape; the
 * slots make exact strings differ per device and tie each message to the rest of the story, they do not make the
 * text indistinguishable from a person's.
 */
final class SmsText {
    private static final List<String> SHORT_NL = List.of("Ok", "Top", "Haha ja", "Prima", "Doen we", "Klopt", "Zeker", "Dankjewel");
    private static final List<String> SHORT_EN = List.of("Ok", "Sure", "Haha yes", "Fine", "Deal", "Right", "Thanks", "Perfect");
    private static final List<String> DAYS_NL = List.of("maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag", "zondag");
    private static final List<String> DAYS_EN = List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");

    private static final List<String> NL = List.of(
            "Zie je me {day} om {time}?",
            "Lukt {day} om {time} voor jou?",
            "{name} komt {day} om {time}, past dat?",
            "Heb je {name} nog gesproken sinds {day}? Bel me rond {time}",
            "Ik ben {n} minuten later dan {time}, sorry!",
            "Ik heb {name} gezegd dat we er {day} om {time} zijn",
            "Ik ben er rond {time}, wacht je op me bij {place}?",
            "Ik ben over {n} minuten bij {place}, kom je rond {time} ook?",
            "Afspraak bij {place} op {day} om {time}, niet vergeten");
    private static final List<String> EN = List.of(
            "See you {day} at {time}?",
            "Does {day} at {time} work for you?",
            "{name} is coming {day} at {time}, is that ok?",
            "Did you talk to {name} since {day}? Call me around {time}",
            "I am {n} minutes later than {time}, sorry!",
            "I told {name} we will be there {day} at {time}",
            "I will be there around {time}, will you wait for me at {place}?",
            "I will be at {place} in {n} minutes, are you coming around {time}?",
            "Appointment at {place} on {day} at {time}, do not forget");

    private SmsText() {}

    /** {@code names} are first names of contacts, {@code places} names of cafes and shops of the story; either may be empty. */
    static String message(boolean dutch, RandomGenerator r, List<String> names, List<String> places) {
        if (r.nextInt(4) == 0) {
            List<String> shortReplies = dutch ? SHORT_NL : SHORT_EN;
            return shortReplies.get(r.nextInt(shortReplies.size()));
        }
        List<String> usable = (dutch ? NL : EN).stream()
                .filter(t -> !t.contains("{place}") || !places.isEmpty())
                .filter(t -> !t.contains("{name}") || !names.isEmpty())
                .toList();
        String text = usable.get(r.nextInt(usable.size()));
        List<String> days = dutch ? DAYS_NL : DAYS_EN;
        while (text.contains("{")) {
            if (text.contains("{day}")) {
                text = text.replaceFirst("\\{day}", days.get(r.nextInt(days.size())));
            } else if (text.contains("{time}")) {
                text = text.replaceFirst("\\{time}", String.format(Locale.ROOT, "%02d:%02d", 7 + r.nextInt(16), 5 * r.nextInt(12)));
            } else if (text.contains("{name}")) {
                text = text.replaceFirst("\\{name}", Matcher.quoteReplacement(names.get(r.nextInt(names.size()))));
            } else if (text.contains("{place}")) {
                text = text.replaceFirst("\\{place}", Matcher.quoteReplacement(places.get(r.nextInt(places.size()))));
            } else {
                text = text.replaceFirst("\\{n}", String.valueOf(5 * (1 + r.nextInt(11))));
            }
        }
        return text;
    }
}
