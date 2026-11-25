// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the country packs from their data files. {@code XX} is the neutral pack and the defaults of every other pack.
 * A country without a pack, or whose pack has no verified range, gets the neutral pack.
 */
public final class Packs {
    private static final String DIR = "/org/oblivios/decoy/content/data/country/";
    private static final String NEUTRAL = "XX";
    private static final Map<String, Optional<CountryPack>> CACHE = new ConcurrentHashMap<>();

    private Packs() {}

    public static CountryPack neutral() {
        return load(NEUTRAL).orElseThrow(() -> new IllegalStateException("the neutral country pack is missing"));
    }

    /** Every pack that has a data file, in the order of the index. */
    public static List<CountryPack> all() {
        Properties index = properties("index");
        if (index == null) {
            throw new IllegalStateException("the index of the country packs is missing");
        }
        return Arrays.stream(index.getProperty("available", "").split("\\|")).map(String::trim).filter(c -> !c.isEmpty())
                .map(c -> load(c).orElseThrow(() -> new IllegalStateException("the index names " + c + ", which has no file"))).toList();
    }

    /** The pack of a country code (ISO 3166-1 alpha-2), or empty when there is no data file for it. */
    public static Optional<CountryPack> load(String code) {
        if (code == null || !code.matches("[A-Za-z]{2}")) {
            return Optional.empty();
        }
        return CACHE.computeIfAbsent(code.toUpperCase(Locale.ROOT), Packs::read);
    }

    private static Optional<CountryPack> read(String code) {
        Properties base = code.equals(NEUTRAL) ? null : properties(NEUTRAL);
        if (!code.equals(NEUTRAL) && base == null) {
            throw new IllegalStateException("the neutral country pack is missing");
        }
        Properties p = new Properties(base);
        Properties own = properties(code);
        if (own == null) {
            return Optional.empty();
        }
        own.forEach((k, v) -> p.setProperty((String) k, (String) v));
        if (!code.equals(own.getProperty("code", "").trim())) {
            throw new IllegalStateException("pack file " + code + " declares code '" + own.getProperty("code") + "'");
        }
        return Optional.of(new DataPack(p));
    }

    private static Properties properties(String code) {
        try (InputStream in = Packs.class.getResourceAsStream(DIR + code + ".properties")) {
            if (in == null) {
                return null;
            }
            Properties p = new Properties();
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read country pack " + code, e);
        }
    }

    public static PackChoice forDevice(String simCountry, ZoneId zone, Locale locale) {
        String country = simCountry != null && !simCountry.isBlank() ? simCountry : locale.getCountry();
        Optional<CountryPack> p = NEUTRAL.equalsIgnoreCase(country) ? Optional.empty() : load(country);
        return forDevice(p.map(List::of).orElse(List.of()), country, zone, locale);
    }

    // shortcut: the zone is not used to pick a pack, only the SIM country and then the locale's country.
    static PackChoice forDevice(List<CountryPack> registry, String simCountry, ZoneId zone, Locale locale) {
        String country = simCountry != null && !simCountry.isBlank() ? simCountry : locale.getCountry();
        for (CountryPack p : registry) {
            if (p.code().equalsIgnoreCase(country)) {
                try {
                    p.mobileNumber(new SplittableRandom(0));
                    return new PackChoice(p, false);
                } catch (NoVerifiedRange e) {
                    return new PackChoice(neutral(), true);
                }
            }
        }
        return new PackChoice(neutral(), true);
    }
}
