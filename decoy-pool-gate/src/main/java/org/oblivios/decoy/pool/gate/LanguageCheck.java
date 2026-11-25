// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.pool.gate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.pool.PoolConversation;
import org.oblivios.decoy.content.pool.PoolMessage;

/** Whether a conversation is written in a language, by the share of its words that are stop words of that language. */
public final class LanguageCheck {
    private static final double LEAST_SHARE = 0.15;

    private LanguageCheck() {}

    /** True when the share for {@code tag} is at least 0.15 and above the share for every other available language. */
    public static boolean isLanguage(PoolConversation c, String tag) {
        List<String> words = Words.folded(c.messages().stream().map(PoolMessage::text).toList());
        if (words.isEmpty() || !LanguagePack.available().contains(tag)) {
            return false;
        }
        double own = share(words, tag);
        return own >= LEAST_SHARE
                && LanguagePack.available().stream().filter(other -> !other.equals(tag)).allMatch(other -> own > share(words, other));
    }

    private static double share(List<String> words, String tag) {
        Set<String> stop = new HashSet<>(LanguagePack.of(tag).list("stopwords"));
        return (double) words.stream().filter(stop::contains).count() / words.size();
    }
}
