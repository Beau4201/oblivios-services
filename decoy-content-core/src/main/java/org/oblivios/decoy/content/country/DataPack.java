// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * A country pack read from a data file ({@code country/<code>.properties}). Lists are separated by {@code |}. Every
 * key is checked against the known set, so a typo in a data file fails loudly instead of leaving a field empty.
 */
public final class DataPack implements CountryPack {
    private static final Set<String> KEYS = Set.of(
            "code", "language", "given", "family", "mobile.prefix", "mobile.blocks", "senders.BANK", "senders.PARCEL",
            "senders.OPERATOR", "senders.SHOP", "senders.GLOBAL", "holidays", "wifi", "cities", "capital", "amount",
            "mms.groups", "sites.GLOBAL", "sites.LOCAL", "trips.domestic", "trips.abroad");

    private final String code;
    private final String language;
    private final List<String> given;
    private final List<String> family;
    private final String mobilePrefix;
    private final long[][] blocks;
    private final Map<SenderKind, List<Sender>> senders = new EnumMap<>(SenderKind.class);
    private final List<HolidayRule> holidays;
    private final List<String> wifi;
    private final Map<String, double[]> cities = new HashMap<>();
    private final String capital;
    private final String amount;
    private final int[] mmsGroups;
    private final List<String> sites;
    private final List<City> domesticTrips;
    private final List<City> abroadTrips;

    public DataPack(Properties p) {
        for (String key : p.stringPropertyNames()) {
            if (!KEYS.contains(key)) {
                throw new IllegalStateException("unknown key '" + key + "' in country pack " + p.getProperty("code"));
            }
        }
        code = p.getProperty("code", "").trim();
        if (code.isEmpty()) {
            throw new IllegalStateException("country pack without a code");
        }
        language = p.getProperty("language", "").trim();
        given = list(p, "given");
        family = list(p, "family");
        mobilePrefix = p.getProperty("mobile.prefix", "").trim();
        List<String> blockList = list(p, "mobile.blocks");
        blocks = new long[blockList.size()][];
        for (int i = 0; i < blocks.length; i++) {
            long[] b = range(blockList.get(i), "mobile.blocks");
            blocks[i] = b;
        }
        for (SenderKind kind : SenderKind.values()) {
            List<Sender> out = new ArrayList<>();
            for (String name : list(p, "senders." + kind.name())) {
                out.add(new Sender(name, kind));
            }
            senders.put(kind, List.copyOf(out));
        }
        List<HolidayRule> rules = new ArrayList<>();
        for (String rule : list(p, "holidays")) {
            rules.add(HolidayRule.parse(rule));
        }
        holidays = List.copyOf(rules);
        wifi = list(p, "wifi");
        for (City c : cities(p, "cities")) {
            cities.put(key(c.name()), new double[] {c.lat(), c.lon()});
        }
        List<String> allSites = new ArrayList<>(list(p, "sites.GLOBAL"));
        allSites.addAll(list(p, "sites.LOCAL"));
        sites = List.copyOf(allSites);
        domesticTrips = cities(p, "trips.domestic");
        abroadTrips = cities(p, "trips.abroad");
        capital = key(p.getProperty("capital", ""));
        if (!capital.isEmpty() && !cities.containsKey(capital)) {
            throw new IllegalStateException("capital '" + capital + "' of pack " + code + " is not in its cities");
        }
        amount = p.getProperty("amount", "{n}").trim();
        if (!amount.contains("{n}")) {
            throw new IllegalStateException("amount pattern of pack " + code + " has no {n}");
        }
        long[] mms = range(p.getProperty("mms.groups", "0-0").trim(), "mms.groups");
        mmsGroups = new int[] {(int) mms[0], (int) mms[1]};
    }

    private static List<String> list(Properties p, String key) {
        List<String> out = new ArrayList<>();
        for (String v : p.getProperty(key, "").split("\\|")) {
            String t = v.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    /** Entries {@code <name> <lat> <lon>}; the name may hold spaces. */
    private List<City> cities(Properties p, String key) {
        List<City> out = new ArrayList<>();
        for (String entry : list(p, key)) {
            String[] t = entry.split("\\s+");
            if (t.length < 3) {
                throw new IllegalStateException(key + " entry is not '<name> <lat> <lon>' in pack " + code + ": '" + entry + "'");
            }
            String name = String.join(" ", java.util.Arrays.copyOf(t, t.length - 2));
            try {
                out.add(new City(name, Double.parseDouble(t[t.length - 2]), Double.parseDouble(t[t.length - 1])));
            } catch (NumberFormatException e) {
                throw new IllegalStateException(key + " entry with a bad coordinate in pack " + code + ": '" + entry + "'", e);
            }
        }
        return List.copyOf(out);
    }

    /** {@code <first>-<last>}, inclusive. */
    private long[] range(String v, String key) {
        String[] ends = v.trim().split("-");
        try {
            if (ends.length != 2) {
                throw new NumberFormatException("not <first>-<last>");
            }
            long from = Long.parseLong(ends[0].trim());
            long to = Long.parseLong(ends[1].trim());
            if (from > to || from < 0) {
                throw new NumberFormatException("first above last");
            }
            return new long[] {from, to};
        } catch (NumberFormatException e) {
            throw new IllegalStateException("bad range '" + v + "' for " + key + " in pack " + code, e);
        }
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String language() {
        return language;
    }

    @Override
    public String givenName(RandomGenerator r) {
        return given.get(r.nextInt(given.size()));
    }

    @Override
    public String familyName(RandomGenerator r) {
        return family.get(r.nextInt(family.size()));
    }

    @Override
    public PhoneNumber mobileNumber(RandomGenerator r) throws NoVerifiedRange {
        if (blocks.length == 0 || mobilePrefix.isEmpty()) {
            throw new NoVerifiedRange("pack " + code + " has no verified number range");
        }
        long total = 0;
        for (long[] b : blocks) {
            total += b[1] - b[0] + 1;
        }
        long pick = r.nextLong(total);
        for (long[] b : blocks) {
            long size = b[1] - b[0] + 1;
            if (pick < size) {
                return new PhoneNumber(mobilePrefix + (b[0] + pick));
            }
            pick -= size;
        }
        throw new IllegalStateException("unreachable: pick is below the total");
    }

    @Override
    public List<Sender> senders(SenderKind kind) {
        return senders.get(kind);
    }

    @Override
    public List<LocalDate> publicHolidays(int year) {
        List<LocalDate> out = new ArrayList<>();
        for (HolidayRule h : holidays) {
            out.add(h.on(year));
        }
        return List.copyOf(out);
    }

    @Override
    public Optional<String> holidayName(LocalDate date) {
        for (HolidayRule h : holidays) {
            if (h.on(date.getYear()).equals(date) && !h.name().isEmpty()) {
                return Optional.of(h.name());
            }
        }
        return Optional.empty();
    }

    @Override
    public List<String> homeWifiPatterns() {
        return wifi;
    }

    @Override
    public Optional<double[]> city(String name) {
        double[] c = cities.get(key(name));
        return c == null ? Optional.empty() : Optional.of(c.clone());
    }

    @Override
    public double[] capital() {
        if (capital.isEmpty()) {
            throw new IllegalArgumentException("no place data for country " + code);
        }
        return cities.get(capital).clone();
    }

    @Override
    public String amount(int value) {
        return amount.replace("{n}", Integer.toString(value));
    }

    @Override
    public int[] mmsGroups() {
        return mmsGroups.clone();
    }

    @Override
    public List<String> sites() {
        return sites;
    }

    @Override
    public List<City> domesticTrips() {
        return domesticTrips;
    }

    @Override
    public List<City> abroadTrips() {
        return abroadTrips;
    }
}
