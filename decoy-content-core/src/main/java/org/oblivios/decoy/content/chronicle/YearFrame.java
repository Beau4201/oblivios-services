// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.chronicle;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.data.HolidayData;
import org.oblivios.decoy.content.data.SchoolHoliday;

/** The first layer of a life: its public holidays, the school holidays of its region and the days it keeps itself. */
public final class YearFrame {

    public enum Season { WINTER, SPRING, SUMMER, AUTUMN }

    private final Set<LocalDate> publicHolidays = new HashSet<>();
    private final List<SchoolHoliday> school;
    private final Map<LocalDate, String> kept = new HashMap<>();

    private YearFrame(List<SchoolHoliday> school) {
        this.school = school;
    }

    /** Public and kept holidays are known from {@code from} to {@code to}, school holidays as far as their data goes. */
    public static YearFrame of(CountryPack pack, HolidayData h, String region, LocalDate from, LocalDate to,
            Set<String> keptHolidays) {
        YearFrame f = new YearFrame(h.holidays(region));
        for (int year = from.getYear(); year <= to.getYear(); year++) {
            f.publicHolidays.addAll(pack.publicHolidays(year));
        }
        for (String id : new TreeSet<>(keptHolidays)) {
            for (SchoolHoliday dates : h.keptDates(id)) {
                for (LocalDate d = dates.from(); !d.isAfter(dates.to()); d = d.plusDays(1)) {
                    if (!d.isBefore(from) && !d.isAfter(to)) {
                        f.kept.putIfAbsent(d, id);
                    }
                }
            }
        }
        return f;
    }

    public DayKind kind(LocalDate d, boolean schoolChild, Set<DayOfWeek> workDays) {
        if (publicHoliday(d) || schoolChild && schoolHoliday(d)) {
            return DayKind.HOLIDAY;
        }
        if (workDays.contains(d.getDayOfWeek())) {
            return schoolChild ? DayKind.SCHOOL_DAY : DayKind.WORKDAY;
        }
        return d.getDayOfWeek().getValue() >= DayOfWeek.SATURDAY.getValue() ? DayKind.WEEKEND : DayKind.ANY;
    }

    public boolean publicHoliday(LocalDate d) {
        return publicHolidays.contains(d);
    }

    public boolean schoolHoliday(LocalDate d) {
        return school.stream().anyMatch(h -> !d.isBefore(h.from()) && !d.isAfter(h.to()));
    }

    public Optional<String> keptHoliday(LocalDate d) {
        return Optional.ofNullable(kept.get(d));
    }

    /** By month, as the northern half of the earth has them; turned for a latitude south of the equator. */
    public Season season(LocalDate d, double latitude) {
        int north = d.getMonthValue() % 12 / 3;
        return Season.values()[latitude < 0 ? (north + 2) % 4 : north];
    }
}
