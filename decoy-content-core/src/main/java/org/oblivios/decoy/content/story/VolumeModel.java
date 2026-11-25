// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.story;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Seed;

/**
 * How many photos per month and category the decoy shows: the owner's counted volume times a factor of 0.85 to
 * 1.15, or the defaults of a typical used phone where the counts are too thin to follow (a warning says so).
 */
public final class VolumeModel {
    private static final int MIN_MONTHS = 6;
    private static final int MIN_CAMERA_TOTAL = 200;
    private static final int DEFAULT_MONTHS = 24;

    private final Map<YearMonth, int[]> targets;
    private final List<String> warnings;

    private VolumeModel(Map<YearMonth, int[]> targets, List<String> warnings) {
        this.targets = targets;
        this.warnings = List.copyOf(warnings);
    }

    /** Deterministic convenience form with a fixed seed; the planner passes its own seed to the two-argument form. */
    public static VolumeModel fromCounts(VolumeCounts c) {
        return fromCounts(c, Seed.of(0, 0));
    }

    public static VolumeModel fromCounts(VolumeCounts c, Seed seed) {
        List<String> warnings = new ArrayList<>();
        List<MonthCount> counted = new ArrayList<>(c.months());
        counted.sort(Comparator.comparing(MonthCount::month));
        // shortcut: an empty count ends at the current month, which makes that case clock dependent; the planner
        // passes counts read on the device, so it only happens when the read found no media at all.
        YearMonth end = counted.isEmpty() ? YearMonth.now() : counted.get(counted.size() - 1).month();
        RandomGenerator r = seed.stream("volume");

        boolean thin = counted.size() < MIN_MONTHS;
        if (counted.isEmpty()) {
            warnings.add("no media counts: the defaults of a typical used phone are used");
        } else if (thin) {
            warnings.add("fewer than " + MIN_MONTHS + " months of counts: the defaults of a typical used phone are used");
        }

        List<YearMonth> axis = new ArrayList<>();
        if (thin) {
            for (int i = DEFAULT_MONTHS - 1; i >= 0; i--) {
                axis.add(end.minusMonths(i));
            }
        } else {
            counted.forEach(m -> axis.add(m.month()));
        }

        long cameraTotal = counted.stream().mapToLong(MonthCount::camera).sum();
        long shotsTotal = counted.stream().mapToLong(MonthCount::screenshots).sum();
        long savedTotal = counted.stream().mapToLong(MonthCount::saved).sum();
        boolean defCamera = thin || cameraTotal < MIN_CAMERA_TOTAL;
        boolean defShots = thin || shotsTotal == 0;
        boolean defSaved = thin || savedTotal == 0;
        if (!thin && defCamera) {
            warnings.add("camera counts total " + cameraTotal + ", below " + MIN_CAMERA_TOTAL + ": the camera default is used");
        }
        if (!thin && defShots) {
            warnings.add("no screenshots counted: the screenshot default is used");
        }
        if (!thin && defSaved) {
            warnings.add("no saved images counted: the saved-image default is used");
        }

        RandomGenerator d = seed.stream("volume-default");
        double phase = d.nextDouble() * 2 * Math.PI;
        Map<YearMonth, MonthCount> byMonth = new TreeMap<>();
        counted.forEach(m -> byMonth.put(m.month(), m));

        Map<YearMonth, int[]> out = new TreeMap<>();
        for (int i = 0; i < axis.size(); i++) {
            YearMonth m = axis.get(i);
            MonthCount mc = byMonth.get(m);
            double season = 1 + 0.4 * Math.sin(2 * Math.PI * i / 12.0 + phase);
            int camera = defCamera ? (int) Math.round(70 * season) : target(mc.camera(), r);
            int shots = defShots ? (int) Math.round(14 * season) : target(mc.screenshots(), r);
            int saved = defSaved ? (int) Math.round(8 * season) : target(mc.saved(), r);
            out.put(m, new int[] {camera, shots, saved});
        }
        return new VolumeModel(out, warnings);
    }

    private static int target(int counted, RandomGenerator r) {
        double factor = 0.85 + 0.30 * r.nextDouble();
        return (int) Math.round(counted * factor);
    }

    public List<YearMonth> months() {
        return List.copyOf(targets.keySet());
    }

    public int targetCamera(YearMonth m) {
        int[] t = targets.get(m);
        return t == null ? 0 : t[0];
    }

    public int targetScreenshots(YearMonth m) {
        int[] t = targets.get(m);
        return t == null ? 0 : t[1];
    }

    public int targetSaved(YearMonth m) {
        int[] t = targets.get(m);
        return t == null ? 0 : t[2];
    }

    public List<String> warnings() {
        return warnings;
    }
}
