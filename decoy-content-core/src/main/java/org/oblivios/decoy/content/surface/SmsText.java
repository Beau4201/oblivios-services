// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import org.oblivios.decoy.content.lang.LanguagePack;

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
    private SmsText() {}

    /** {@code names} are first names of contacts, {@code places} names of cafes and shops of the story; either may be empty. */
    static String message(LanguagePack lang, RandomGenerator r, List<String> names, List<String> places) {
        if (r.nextInt(4) == 0) {
            List<String> shortReplies = lang.list("sms.short");
            return shortReplies.get(r.nextInt(shortReplies.size()));
        }
        List<String> usable = lang.list("sms.templates").stream()
                .filter(t -> !t.contains("{place}") || !places.isEmpty())
                .filter(t -> !t.contains("{name}") || !names.isEmpty())
                .toList();
        String text = usable.get(r.nextInt(usable.size()));
        List<String> days = lang.list("weekdays");
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
