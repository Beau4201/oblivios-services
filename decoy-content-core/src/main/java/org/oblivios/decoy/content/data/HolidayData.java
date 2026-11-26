// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The school holidays of one country by region, from {@code holidays/<CC>.tsv}, and the holidays each background keeps,
 * from {@code holidays/kept.tsv}.
 */
public final class HolidayData {
    private static final Map<String, HolidayData> LOADED = new ConcurrentHashMap<>();

    private final Map<String, List<SchoolHoliday>> byRegion = new HashMap<>();
    private final Map<String, List<SchoolHoliday>> keptById = new HashMap<>();
    private final Map<String, Set<String>> keptBy = new HashMap<>();
    private final String version;

    private HolidayData(String country) {
        String file = "holidays/" + country + ".tsv";
        String school = Tables.read(file, Tables.open(file), 5, c -> byRegion
                .computeIfAbsent(c[1], k -> new ArrayList<>())
                .add(new SchoolHoliday(c[2], c[1], LocalDate.parse(c[3]), LocalDate.parse(c[4]))));
        String kept = Tables.read("holidays/kept.tsv", Tables.open("holidays/kept.tsv"), 5, c -> {
            keptById.computeIfAbsent(c[0], k -> new ArrayList<>())
                    .add(new SchoolHoliday(c[0], "", LocalDate.parse(c[3]), LocalDate.parse(c[4])));
            for (String background : c[1].split(",")) {
                keptBy.computeIfAbsent(background, k -> new HashSet<>()).add(c[0]);
            }
        });
        if (byRegion.isEmpty()) {
            throw new IllegalStateException(file + " holds no school holiday");
        }
        byRegion.values().forEach(l -> l.sort(Comparator.comparing(SchoolHoliday::from)));
        keptById.values().forEach(l -> l.sort(Comparator.comparing(SchoolHoliday::from)));
        version = school + "; " + kept;
    }

    /** Throws {@link IllegalArgumentException} for a country without holiday data. */
    public static HolidayData load(String country) {
        if (!Tables.has(country, "holidays", "{cc}.tsv")) {
            throw new IllegalArgumentException("no holiday data for country " + country);
        }
        return LOADED.computeIfAbsent(country, HolidayData::new);
    }

    /** The school holidays of a region, the earliest first. */
    public List<SchoolHoliday> holidays(String region) {
        List<SchoolHoliday> found = byRegion.get(region);
        if (found == null) {
            throw new IllegalArgumentException("no school holidays for region " + region);
        }
        return List.copyOf(found);
    }

    /** The ids of the holidays a background keeps; none for a background without data. */
    public Set<String> kept(String background) {
        return Set.copyOf(keptBy.getOrDefault(background, Set.of()));
    }

    /** The dates of one kept holiday, the earliest first; none for an unknown id. */
    public List<SchoolHoliday> keptDates(String id) {
        return List.copyOf(keptById.getOrDefault(id, List.of()));
    }

    /** The first day of the school data: no story with school children reaches further back. */
    public LocalDate firstDay() {
        return byRegion.values().stream().flatMap(List::stream).map(SchoolHoliday::from)
                .min(Comparator.naturalOrder()).orElseThrow();
    }

    public LocalDate lastDay() {
        return byRegion.values().stream().flatMap(List::stream).map(SchoolHoliday::to)
                .max(Comparator.naturalOrder()).orElseThrow();
    }

    public String version() {
        return version;
    }
}
