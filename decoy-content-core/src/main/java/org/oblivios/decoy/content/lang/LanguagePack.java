// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.lang;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.oblivios.decoy.content.country.CountryPack;

/**
 * The texts of one language, read from a data file ({@code lang/<tag>.properties}, lists separated by {@code |}).
 * There is no default language: a language without data is refused, never replaced by another (ADR-321).
 */
public final class LanguagePack {
    private static final String DIR = "/org/oblivios/decoy/content/data/lang/";
    private static final String TEXT_DIR = "/org/oblivios/decoy/content/data/text/";
    private static final Map<String, LanguagePack> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> AVAILABLE = readAvailable();

    private final String tag;
    private final Properties values;

    private LanguagePack(String tag, Properties values) {
        this.tag = tag;
        this.values = values;
    }

    /** The pack of the language of a BCP 47 tag ({@code nl-NL} gives {@code nl}). */
    public static LanguagePack of(String tag) {
        String language = tag == null ? "" : Locale.forLanguageTag(tag.trim()).getLanguage();
        if (!AVAILABLE.contains(language)) {
            throw new IllegalArgumentException("no language data for " + (language.isEmpty() && tag != null ? tag.trim() : language));
        }
        return CACHE.computeIfAbsent(language, l -> new LanguagePack(l, readAll(l)));
    }

    /** The language file and the files a tool wrote from the sources ({@code text/<tag>/<kind>.properties}). */
    private static Properties readAll(String language) {
        Properties out = read(DIR + language + ".properties");
        String texts = TEXT_DIR + language + "/";
        for (String kind : read(texts + "index.properties").getProperty("kinds", "").split("\\|")) {
            merge(out, read(texts + kind + ".properties"), "text/" + language + "/" + kind + ".properties");
        }
        return out;
    }

    static void merge(Properties into, Properties add, String file) {
        for (String key : add.stringPropertyNames()) {
            if (into.put(key, add.getProperty(key)) != null) {
                throw new IllegalStateException(file + ": the key " + key + " is in two files");
            }
        }
    }

    /**
     * The language of the texts a sender of the country writes (service SMS, mail): the country's language, and the
     * persona's language when the country pack has none.
     */
    public static LanguagePack forService(CountryPack country, String personaTag) {
        return of(country.language().isEmpty() ? personaTag : country.language());
    }

    public static Set<String> available() {
        return AVAILABLE;
    }

    public String tag() {
        return tag;
    }

    public boolean has(String key) {
        return values.getProperty(key) != null;
    }

    public String text(String key) {
        String v = values.getProperty(key);
        if (v == null) {
            throw new IllegalStateException("language " + tag + " has no key " + key);
        }
        return v;
    }

    /** The entries of a {@code |}-separated list, trimmed; an empty entry is kept. */
    public List<String> list(String key) {
        List<String> out = new ArrayList<>();
        for (String v : text(key).split("\\|", -1)) {
            out.add(v.trim());
        }
        return List.copyOf(out);
    }

    /** The text of a key with each {@code {slot}} replaced; throws when a slot of the text has no value. */
    public String format(String key, Map<String, String> slots) {
        String out = text(key);
        for (Map.Entry<String, String> e : slots.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        if (out.matches("(?s).*\\{[a-z]+}.*")) {
            throw new IllegalStateException("text " + key + " of " + tag + " has an unfilled slot: " + out);
        }
        return out;
    }

    public String weekday(DayOfWeek d) {
        return list("weekdays").get(d.getValue() - 1);
    }

    private static Set<String> readAvailable() {
        List<String> tags = new ArrayList<>();
        for (String t : read(DIR + "index.properties").getProperty("available", "").split("\\|")) {
            if (!t.isBlank()) {
                tags.add(t.trim());
            }
        }
        return Set.copyOf(tags);
    }

    private static Properties read(String path) {
        try (InputStream in = LanguagePack.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing language data file " + path);
            }
            Properties p = new Properties();
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
