// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import org.oblivios.decoy.content.country.PhoneNumber;
import org.oblivios.decoy.content.country.PhoneNumber.NumberType;

/**
 * The phone numbers of one country, read from {@code numbers/<CC>.properties}: numbers follow the country's own format
 * and may belong to a stranger (ADR-335), but are never one that costs, that is free to call, that is short or that
 * reaches an emergency service.
 */
public final class PhoneNumbers {
    /** What a type of number of a country looks like: only {@code drawLength} is drawn, any of {@code lengths} is valid. */
    record Shape(String pattern, Set<Integer> lengths, int drawLength, Pattern compiled) {}

    private static final Map<String, PhoneNumbers> LOADED = new ConcurrentHashMap<>();
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    /** A pattern of which almost every number is a never number is a fault of the data, not a reason to draw on. */
    private static final int DRAWS = 1_000;

    private final String country;
    private final String countryCode;
    private final String nationalPrefix;
    private final Shape mobile;
    private final Shape fixed;
    private final List<Pattern> never = new ArrayList<>();
    private final Map<String, String> areas = new HashMap<>();
    private final String version;
    private volatile Map<String, List<String>> areasByMunicipality;

    private PhoneNumbers(String country, Properties p) {
        this.country = country;
        countryCode = need(p, "country.code");
        nationalPrefix = p.getProperty("national.prefix", "");
        mobile = shape(p, "mobile");
        fixed = shape(p, "fixed");
        for (String n : p.getProperty("never.patterns", "").split(" ")) {
            if (!n.isEmpty()) {
                never.add(Pattern.compile(n));
            }
        }
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("area.")) {
                areas.put(key.substring("area.".length()), p.getProperty(key));
            }
        }
        version = need(p, "version");
    }

    private String need(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("numbers/" + country + ".properties has no " + key);
        }
        return value;
    }

    private Shape shape(Properties p, String type) {
        String pattern = p.getProperty(type + ".pattern");
        if (pattern == null) {
            return null;
        }
        Set<Integer> lengths = new LinkedHashSet<>();
        for (String n : need(p, type + ".lengths").split(",")) {
            lengths.add(Integer.parseInt(n));
        }
        return new Shape(pattern, lengths, Integer.parseInt(need(p, type + ".draw.length")), Pattern.compile(pattern));
    }

    /** Whether the country has a number file; the code is checked before it becomes part of a path. */
    public static boolean has(String country) {
        return country != null && COUNTRY.matcher(country).matches()
                && PhoneNumbers.class.getResource(Tables.DIR + "numbers/" + country + ".properties") != null;
    }

    /** Throws {@link IllegalArgumentException} for a country without number data: no number is invented. */
    public static PhoneNumbers of(String country) {
        if (!has(country)) {
            throw new IllegalArgumentException("no number data for country " + country);
        }
        return LOADED.computeIfAbsent(country, c -> {
            try (Reader r = Tables.open("numbers/" + c + ".properties")) {
                Properties p = new Properties();
                p.load(r);
                return new PhoneNumbers(c, p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    static PhoneNumbers parse(String country, String text) {
        try {
            Properties p = new Properties();
            p.load(new StringReader(text));
            return new PhoneNumbers(country, p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every country with a file, in the order of the index. */
    static List<String> countries() {
        try (Reader r = Tables.open("numbers/index.properties")) {
            Properties p = new Properties();
            p.load(r);
            return List.of(p.getProperty("available", "").split("\\|"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Shape mobileShape() {
        return mobile;
    }

    public PhoneNumber mobile(RandomGenerator r) {
        return drawn(mobile, "", NumberType.MOBILE, r);
    }

    /** A landline with the area code of the municipality, or of the nearest one that the area codes name. */
    public PhoneNumber landline(String municipality, RandomGenerator r) {
        List<String> codes = areasByMunicipality().get(municipality);
        if (codes == null) {
            throw new IllegalArgumentException("no area code for municipality " + municipality + " of country " + country);
        }
        return drawn(fixed, codes.get(r.nextInt(codes.size())), NumberType.FIXED, r);
    }

    private PhoneNumber drawn(Shape shape, String prefix, NumberType type, RandomGenerator r) {
        if (shape == null) {
            throw new IllegalStateException("country " + country + " has no " + type + " numbers");
        }
        for (int i = 0; i < DRAWS; i++) {
            String national = PatternDraw.draw(shape.pattern(), Set.of(shape.drawLength()), prefix, r);
            if (national == null) {
                break;
            }
            if (!isNever(national)) {
                return new PhoneNumber("+" + countryCode + national, type);
            }
        }
        throw new IllegalStateException("no " + type + " number of country " + country + " with the start '" + prefix + "'");
    }

    /**
     * For each municipality the area codes that a number of it can start with: those whose place the municipality is
     * named after. The geocoding file names towns, and many municipalities bear none of those names; such a one takes
     * the codes of the nearest that does.
     * shortcut: the nearest by the middles of the municipalities; a table of the codes of each village would replace it.
     */
    private Map<String, List<String>> areasByMunicipality() {
        Map<String, List<String>> found = areasByMunicipality;
        if (found != null) {
            return found;
        }
        PlaceData places = PlaceData.load(country);
        Map<String, List<String>> named = new HashMap<>();

        areas.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(a -> {
            if (starts(a.getKey())) {
                places.city(a.getValue())
                        .ifPresent(city -> named.computeIfAbsent(city.municipality(), m -> new ArrayList<>()).add(a.getKey()));
            }
        });
        if (named.isEmpty()) {
            throw new IllegalStateException("country " + country + " has no area code of a known place");
        }
        Map<String, List<String>> all = new HashMap<>();
        List<CityRef> cities = places.municipalities();
        for (CityRef city : cities) {
            List<String> own = named.get(city.municipality());
            if (own == null) {
                CityRef nearest = cities.stream().filter(c -> named.containsKey(c.municipality()))
                        .min(Comparator.comparingDouble((CityRef c) -> distance(city, c)).thenComparing(CityRef::municipality))
                        .orElseThrow();
                own = named.get(nearest.municipality());
            }
            all.put(city.municipality(), List.copyOf(own));
        }
        areasByMunicipality = Map.copyOf(all);
        return areasByMunicipality;
    }

    /** Whether the fixed pattern has a number of the drawn length that starts with the area code. */
    private boolean starts(String areaCode) {
        return fixed != null
                && PatternDraw.draw(fixed.pattern(), Set.of(fixed.drawLength()), areaCode, new SplittableRandom(0)) != null;
    }

    private static double distance(CityRef a, CityRef b) {
        double east = (a.lon() - b.lon()) * Math.cos(Math.toRadians(a.lat()));
        return Math.hypot(a.lat() - b.lat(), east);
    }

    private String national(String e164) {
        String start = "+" + countryCode;
        return e164 != null && e164.startsWith(start) ? e164.substring(start.length()) : null;
    }

    private boolean isNever(String national) {
        return never.stream().anyMatch(p -> p.matcher(national).matches());
    }

    /** Whether the number is one of this country that no person has: toll-free, paid, short or an emergency number. */
    public boolean never(String e164) {
        String national = national(e164);
        return national != null && isNever(national);
    }

    /** Whether the number is one of this country, of the form of its type, and not a never number. */
    public boolean valid(PhoneNumber n) {
        String national = national(n.e164());
        Shape shape = n.type() == NumberType.MOBILE ? mobile : fixed;
        return national != null && shape != null && shape.lengths().contains(national.length())
                && shape.compiled().matcher(national).matches() && !isNever(national);
    }

    /** The number as a phone stores it: with the country code, or with the national prefix. */
    public String stored(PhoneNumber n, boolean international) {
        String national = national(n.e164());
        if (national == null) {
            throw new IllegalArgumentException("not a number of country " + country + ": " + n.e164());
        }
        return international ? n.e164() : nationalPrefix + national;
    }

    public String version() {
        return version;
    }
}
