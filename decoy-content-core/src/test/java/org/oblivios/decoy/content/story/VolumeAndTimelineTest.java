// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.story;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;

class VolumeAndTimelineTest {
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final YearMonth LAST = YearMonth.of(2026, 10);

    private static VolumeCounts shaped(int months) {
        List<MonthCount> list = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            list.add(new MonthCount(LAST.minusMonths(months - 1 - i), 40 + i * 15 + (i % 3) * 60, 10 + (i % 4) * 8, 5 + i, 1_000_000L * (i + 1)));
        }
        return new VolumeCounts(list);
    }

    private static Persona persona(String city) {
        return org.oblivios.decoy.content.TestSetup.derive(Seed.of(1, 2),
                PersonaProfile.open(new PersonaInputs("Sam", "Example", city, "NL", ZoneId.of("Europe/Amsterdam"), Locale.forLanguageTag("nl-NL"))), 2026);
    }

    private static CountryPack nl() {
        return Packs.forDevice("NL", ZoneId.of("Europe/Amsterdam"), Locale.ROOT).pack();
    }

    private static int totalCamera(VolumeModel v) {
        int t = 0;
        for (YearMonth m : v.months()) {
            t += v.targetCamera(m);
        }
        return t;
    }

    private static double pearson(double[] x, double[] y) {
        double mx = 0, my = 0;
        for (int i = 0; i < x.length; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= x.length;
        my /= y.length;
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < x.length; i++) {
            sxy += (x[i] - mx) * (y[i] - my);
            sxx += (x[i] - mx) * (x[i] - mx);
            syy += (y[i] - my) * (y[i] - my);
        }
        return sxy / Math.sqrt(sxx * syy);
    }

    private static double km(Place a, Place b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat())) * Math.pow(Math.sin(dLon / 2), 2);
        return 6371 * 2 * Math.asin(Math.sqrt(h));
    }

    @Test
    void degenerateCountsGiveDefaults() {
        List<VolumeCounts> degenerate = List.of(
                new VolumeCounts(List.of()),
                new VolumeCounts(List.of(new MonthCount(LAST, 5, 1, 0, 100))),
                new VolumeCounts(List.of(new MonthCount(LAST.minusMonths(1), 0, 30, 0, 100), new MonthCount(LAST, 0, 25, 0, 100))));
        for (VolumeCounts c : degenerate) {
            VolumeModel v = VolumeModel.fromCounts(c, org.oblivios.decoy.content.Draws.of(Seed.of(3, 4)));
            assertFalse(v.warnings().isEmpty(), "a warning is expected for " + c);
            assertTrue(totalCamera(v) >= 200, "camera total " + totalCamera(v));
        }
    }

    @Test
    void cleanCountsGiveNoWarning() {
        assertTrue(VolumeModel.fromCounts(shaped(18), org.oblivios.decoy.content.Draws.of(Seed.of(3, 4))).warnings().isEmpty());
    }

    @Test
    void targetWithin15Percent() {
        VolumeCounts c = shaped(18);
        VolumeModel v = VolumeModel.fromCounts(c, org.oblivios.decoy.content.Draws.of(Seed.of(5, 6)));
        for (MonthCount m : c.months()) {
            assertTrue(v.targetCamera(m.month()) >= Math.floor(0.85 * m.camera()) && v.targetCamera(m.month()) <= Math.ceil(1.15 * m.camera()),
                    "camera " + m.camera() + " -> " + v.targetCamera(m.month()));
            assertTrue(v.targetScreenshots(m.month()) >= Math.floor(0.85 * m.screenshots()) && v.targetScreenshots(m.month()) <= Math.ceil(1.15 * m.screenshots()));
            assertTrue(v.targetSaved(m.month()) >= Math.floor(0.85 * m.saved()) && v.targetSaved(m.month()) <= Math.ceil(1.15 * m.saved()));
        }
    }

    @Test
    void monthlyShapeFollowsInput() {
        VolumeCounts c = shaped(18);
        VolumeModel v = VolumeModel.fromCounts(c, org.oblivios.decoy.content.Draws.of(Seed.of(7, 8)));
        double[] in = new double[18];
        double[] out = new double[18];
        for (int i = 0; i < 18; i++) {
            in[i] = c.months().get(i).camera();
            out[i] = v.targetCamera(c.months().get(i).month());
        }
        assertTrue(pearson(in, out) >= 0.8, "correlation " + pearson(in, out));
    }

    @Test
    void sameSeedSameTargets() {
        VolumeModel a = VolumeModel.fromCounts(shaped(12), org.oblivios.decoy.content.Draws.of(Seed.of(9, 9)));
        VolumeModel b = VolumeModel.fromCounts(shaped(12), org.oblivios.decoy.content.Draws.of(Seed.of(9, 9)));
        for (YearMonth m : a.months()) {
            assertEquals(a.targetCamera(m), b.targetCamera(m));
        }
    }

    @Test
    void noEventAfterNow() {
        for (long i = 0; i < 200; i++) {
            Timeline t = Timeline.generate(persona("Utrecht"), VolumeModel.fromCounts(shaped(18), org.oblivios.decoy.content.Draws.of(Seed.of(i, 1))), nl(), NOW, org.oblivios.decoy.content.Draws.of(Seed.of(i, 2)));
            assertTrue(t.phoneStart().isBefore(NOW.minus(Duration.ofDays(180))), "phone start " + t.phoneStart());
        }
    }

    @Test
    void phoneStartPrecedesTheFirstVolumeMonth() {
        VolumeCounts c = shaped(18);
        Instant first = c.months().get(0).month().atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        for (long i = 0; i < 50; i++) {
            Timeline t = Timeline.generate(persona("Utrecht"), VolumeModel.fromCounts(c, org.oblivios.decoy.content.Draws.of(Seed.of(i, 1))), nl(), NOW, org.oblivios.decoy.content.Draws.of(Seed.of(i, 2)));
            assertFalse(t.phoneStart().isAfter(first), "phone start " + t.phoneStart() + " after first month " + first);
        }
    }

    @Test
    void rhythmHasNoWakeBetween0200And0500() {
        for (long i = 0; i < 500; i++) {
            Timeline t = Timeline.generate(persona("Utrecht"), VolumeModel.fromCounts(shaped(12), org.oblivios.decoy.content.Draws.of(Seed.of(i, 1))), nl(), NOW, org.oblivios.decoy.content.Draws.of(Seed.of(i, 3)));
            LocalTime w = t.wakeTime();
            assertFalse(!w.isBefore(LocalTime.of(2, 0)) && w.isBefore(LocalTime.of(5, 0)), "wake " + w);
            assertTrue(!w.isBefore(LocalTime.of(5, 30)) && !w.isAfter(LocalTime.of(9, 30)), "wake " + w);
        }
    }

    @Test
    void homeAndWorkSitNearTheCity() {
        Timeline t = Timeline.generate(persona("Utrecht"), VolumeModel.fromCounts(shaped(12), org.oblivios.decoy.content.Draws.of(Seed.of(1, 1))), nl(), NOW, org.oblivios.decoy.content.Draws.of(Seed.of(1, 2)));
        Place home = t.places().stream().filter(p -> p.kind() == PlaceKind.HOME).findFirst().orElseThrow();
        Place work = t.places().stream().filter(p -> p.kind() == PlaceKind.WORK).findFirst().orElseThrow();
        Place city = new Place("Utrecht", 52.09, 5.12, PlaceKind.HOME);
        assertTrue(km(home, city) < 8, "home " + km(home, city) + " km from the centre");
        assertTrue(km(work, city) < 30, "work " + km(work, city) + " km from the centre");
        assertTrue(km(home, work) > 0.2, "work is at home");
        assertTrue(t.places().size() >= 5);
        assertTrue(t.warnings().isEmpty());
    }

    @Test
    void aCityWithoutPlaceDataIsRefusedNotReplaced() {
        // a pack that does not hold the persona's city: the timeline never takes another one
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Timeline.generate(persona("Utrecht"),
                VolumeModel.fromCounts(shaped(12), org.oblivios.decoy.content.Draws.of(Seed.of(1, 1))), Packs.neutral(), NOW,
                org.oblivios.decoy.content.Draws.of(Seed.of(1, 2))));
        assertTrue(e.getMessage().startsWith("city: "), e.getMessage());
    }

    @Test
    void sameSeedSameTimeline() {
        Timeline a = Timeline.generate(persona("Utrecht"), VolumeModel.fromCounts(shaped(12), org.oblivios.decoy.content.Draws.of(Seed.of(2, 2))), nl(), NOW, org.oblivios.decoy.content.Draws.of(Seed.of(4, 4)));
        Timeline b = Timeline.generate(persona("Utrecht"), VolumeModel.fromCounts(shaped(12), org.oblivios.decoy.content.Draws.of(Seed.of(2, 2))), nl(), NOW, org.oblivios.decoy.content.Draws.of(Seed.of(4, 4)));
        assertEquals(a.places(), b.places());
        assertEquals(a.wakeTime(), b.wakeTime());
        assertEquals(a.phoneStart(), b.phoneStart());
    }
}
