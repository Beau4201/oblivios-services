// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.country;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * A country pack read from a data file ({@code country/<code>.properties}). Lists are separated by {@code |}. Every
 * key is checked against the known set, so a typo in a data file fails loudly instead of leaving a field empty.
 */
public final class DataPack implements CountryPack {
    private static final Set<String> KEYS = Set.of(
            "code", "language", "family.child", "family.married", "senders.BANK", "senders.PARCEL",
            "senders.OPERATOR", "senders.SHOP", "senders.SHOP.online", "supermarkets", "senders.GLOBAL", "holidays", "wifi", "amount",
            "mms.groups", "sites.GLOBAL", "sites.LOCAL", "trips.domestic", "trips.abroad");

    private final String code;
    private final String language;
    private final Map<NameForm.Child, Integer> childForms;
    private final Map<NameForm.Married, Integer> marriedForms;
    private final Map<SenderKind, List<Sender>> senders = new EnumMap<>(SenderKind.class);
    private final List<String> onlineShops;
    private final List<String> supermarkets;
    private final List<HolidayRule> holidays;
    private final List<String> wifi;
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
        childForms = weights(p, "family.child", NameForm.Child.class);
        marriedForms = weights(p, "family.married", NameForm.Married.class);
        for (SenderKind kind : SenderKind.values()) {
            List<Sender> out = new ArrayList<>();
            for (String name : list(p, "senders." + kind.name())) {
                out.add(new Sender(name, kind));
            }
            senders.put(kind, List.copyOf(out));
        }
        supermarkets = list(p, "supermarkets");
        onlineShops = list(p, "senders.SHOP.online");
        for (String shop : onlineShops) {
            if (senders.get(SenderKind.SHOP).stream().noneMatch(s -> s.name().equals(shop))) {
                throw new IllegalStateException("senders.SHOP.online names '" + shop + "', which is no shop of pack " + code);
            }
        }
        List<HolidayRule> rules = new ArrayList<>();
        for (String rule : list(p, "holidays")) {
            rules.add(HolidayRule.parse(rule));
        }
        holidays = List.copyOf(rules);
        wifi = list(p, "wifi");
        List<String> allSites = new ArrayList<>(list(p, "sites.GLOBAL"));
        allSites.addAll(list(p, "sites.LOCAL"));
        sites = List.copyOf(allSites);
        domesticTrips = cities(p, "trips.domestic");
        abroadTrips = cities(p, "trips.abroad");
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

    /** Entries {@code <form>:<weight above 0>}, in the order of the forms. */
    private <E extends Enum<E>> Map<E, Integer> weights(Properties p, String key, Class<E> forms) {
        Map<E, Integer> out = new EnumMap<>(forms);
        for (String entry : list(p, key)) {
            String[] t = entry.split(":", -1);
            try {
                int weight = Integer.parseInt(t.length == 2 ? t[1].trim() : "");
                if (weight < 1 || out.put(Enum.valueOf(forms, t[0].trim()), weight) != null) {
                    throw new IllegalArgumentException("a weight below 1 or a form twice");
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(key + " entry is not '<form>:<weight>' in pack " + code + ": '" + entry + "'", e);
            }
        }
        return java.util.Collections.unmodifiableMap(out);
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

    @Override
    public String code() {
        return code;
    }

    @Override
    public String language() {
        return language;
    }

    @Override
    public Map<NameForm.Child, Integer> childNameForms() {
        return childForms;
    }

    @Override
    public Map<NameForm.Married, Integer> marriedNameForms() {
        return marriedForms;
    }

    @Override
    public List<Sender> senders(SenderKind kind) {
        return senders.get(kind);
    }

    @Override
    public List<String> supermarkets() {
        return supermarkets;
    }

    @Override
    public List<String> onlineShops() {
        return onlineShops;
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
