// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.story.MonthCount;
import org.oblivios.decoy.content.story.Timeline;
import org.oblivios.decoy.content.story.VolumeCounts;
import org.oblivios.decoy.content.story.VolumeModel;

class MediaSurfaceTest {
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

    /** A test fixture, not a measured profile: the real one comes from the owner's sample photos (Task 5). */
    private static final DeviceCameraProfile CAM = new DeviceCameraProfile(4080, 3072, List.of(table(8), table(9)),
            "PXL_\\d{8}_\\d{9}\\.jpg", "'PXL_'yyyyMMdd_HHmmssSSS'.jpg'", true);

    private static int[] table(int base) {
        int[] t = new int[64];
        for (int i = 0; i < 64; i++) {
            t[i] = base + i;
        }
        return t;
    }

    private static CountryPack nl() {
        return Packs.forDevice("NL", ZONE, Locale.ROOT).pack();
    }

    private record Setup(VolumeModel volume, Timeline timeline, TravelPlan travel, List<ChosenPhoto> chosen, MediaPlan plan) {}

    private static List<ChosenPhoto> chosen(int count) {
        List<ChosenPhoto> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new ChosenPhoto("/sdcard/DCIM/Camera/own_" + i + ".jpg",
                    Instant.parse("2025-03-01T08:00:00Z").plusSeconds(i * 86_400L * 3 + i * 977L), i % 2 == 0 ? Set.of("owner-name") : Set.of()));
        }
        return out;
    }

    private static Setup setup(long n, YearMonth last, int chosenCount) {
        Persona p = org.oblivios.decoy.content.TestSetup.derive(Seed.of(n, 1),
                PersonaProfile.open(new PersonaInputs("Sam", "Example", "Utrecht", "NL", ZONE, Locale.forLanguageTag("nl-NL"))), 2026);
        List<MonthCount> months = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            months.add(new MonthCount(last.minusMonths(29 - i), 60 + i * 5, 10, 8, 1_000_000L));
        }
        VolumeModel v = VolumeModel.fromCounts(new VolumeCounts(months), org.oblivios.decoy.content.Draws.of(Seed.of(n, 2)));
        Timeline t = Timeline.generate(p, v, nl(), NOW, org.oblivios.decoy.content.Draws.of(Seed.of(n, 3)));
        TravelPlan travel = TravelSurface.generate(t, nl(), LanguagePack.of("nl"), org.oblivios.decoy.content.Draws.of(Seed.of(n, 6)));
        List<ChosenPhoto> chosen = chosen(chosenCount);
        return new Setup(v, t, travel, chosen, MediaSurface.generate(v, t, travel, nl(), CAM, chosen, org.oblivios.decoy.content.Draws.of(Seed.of(n, 7))));
    }

    private static Setup setup(long n) {
        return setup(n, YearMonth.of(2026, 10), 25);
    }

    private static YearMonth monthOf(MediaItem i) {
        return YearMonth.from(i.taken().atZone(ZONE));
    }

    private static boolean isCamera(MediaItem i) {
        return i.kind() == MediaKind.CAMERA_CHOSEN || i.kind() == MediaKind.CAMERA_FILL;
    }

    @Test
    void copyShareBetween5And15Percent() {
        for (long n = 0; n < 20; n++) {
            List<MediaItem> items = setup(n).plan().items();
            long copies = items.stream().filter(i -> i.copyOf() != null).count();
            long photos = items.stream().filter(i -> i.kind() != MediaKind.DOCUMENT).count();
            double share = (double) copies / photos;
            assertTrue(share >= 0.05 && share <= 0.15, "copy share " + share + " for seed " + n);
        }
    }

    @Test
    void noPhotoHasMoreThanOneCopy() {
        for (long n = 0; n < 20; n++) {
            List<MediaItem> items = setup(n).plan().items();
            Set<String> originals = new HashSet<>();
            Set<String> names = items.stream().filter(i -> i.kind() != MediaKind.COPY).map(MediaItem::name).collect(Collectors.toSet());
            for (MediaItem i : items) {
                if (i.copyOf() == null) {
                    continue;
                }
                assertEquals(MediaKind.COPY, i.kind());
                assertTrue(names.contains(i.copyOf()), "copy of an unknown item " + i.copyOf());
                assertTrue(originals.add(i.copyOf()), "second copy of " + i.copyOf());
            }
            assertFalse(originals.isEmpty());
        }
    }

    @Test
    void categoriesFollowVolumeModel() {
        for (long n = 0; n < 10; n++) {
            Setup s = setup(n);
            for (YearMonth m : s.volume().months()) {
                assertCamera(s, m);
                if (beforeThePhone(s, m)) {
                    continue;
                }
                long saved = s.plan().items().stream().filter(i -> i.kind() == MediaKind.SAVED && monthOf(i).equals(m)).count();
                long shots = s.plan().items().stream().filter(i -> i.kind() == MediaKind.SCREENSHOT && monthOf(i).equals(m)).count();
                assertWithin(s.volume().targetSaved(m), saved, "saved " + m);
                assertWithin(s.volume().targetScreenshots(m), shots, "screenshots " + m);
            }
        }
    }

    /** A month the phone did not exist in, as one before the school holiday data for school children, has no media. */
    private static boolean beforeThePhone(Setup s, YearMonth m) {
        return !m.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant().isAfter(s.timeline().phoneStart());
    }

    private static void assertCamera(Setup s, YearMonth m) {
        long camera = s.plan().items().stream().filter(i -> isCamera(i) && monthOf(i).equals(m)).count();
        if (beforeThePhone(s, m)) {
            assertEquals(0, camera, "camera before the phone, " + m);
            assertTrue(s.plan().warnings().stream().anyMatch(w -> w.contains(m.toString())), "no warning for " + m);
        } else {
            assertWithin(s.volume().targetCamera(m), camera, "camera " + m);
        }
    }

    private static void assertWithin(int target, long count, String what) {
        assertTrue(Math.abs(count - target) <= Math.ceil(0.15 * target), what + ": target " + target + ", plan " + count);
    }

    @Test
    void savedImagesCarryNoExifSpec() {
        List<MediaItem> saved = setup(3).plan().items().stream().filter(i -> i.kind() == MediaKind.SAVED).toList();
        assertFalse(saved.isEmpty());
        for (MediaItem i : saved) {
            assertFalse(i.exif(), i.name());
            assertNull(i.lat());
            assertNull(i.lon());
            assertNull(i.profileId());
            assertFalse(Pattern.matches(CAM.fileNameRegex(), i.name()), "saved image named like a camera photo: " + i.name());
        }
    }

    @Test
    void cameraFillMatchesDeviceProfile() {
        Pattern name = Pattern.compile(CAM.fileNameRegex());
        for (long n = 0; n < 10; n++) {
            List<MediaItem> fill = setup(n).plan().items().stream().filter(i -> i.kind() == MediaKind.CAMERA_FILL).toList();
            assertFalse(fill.isEmpty());
            for (MediaItem i : fill) {
                assertEquals(CAM.width(), i.width());
                assertEquals(CAM.height(), i.height());
                assertEquals(CAM.id(), i.profileId());
                assertTrue(name.matcher(i.name()).matches(), "file name " + i.name());
                assertTrue(i.exif());
                assertNotNull(i.lat(), "the profile writes GPS");
                assertNotNull(i.lon());
            }
        }
    }

    @Test
    void cameraFillWithoutGpsProfileCarriesNoCoordinates() {
        DeviceCameraProfile noGps = new DeviceCameraProfile(4080, 3072, List.of(table(8)),
                "PXL_\\d{8}_\\d{9}\\.jpg", "'PXL_'yyyyMMdd_HHmmssSSS'.jpg'", false);
        Setup s = setup(2);
        MediaPlan plan = MediaSurface.generate(s.volume(), s.timeline(), s.travel(), nl(), noGps, List.of(), org.oblivios.decoy.content.Draws.of(Seed.of(2, 7)));
        for (MediaItem i : plan.items()) {
            assertNull(i.lat(), i.name());
            assertNull(i.lon(), i.name());
        }
    }

    @Test
    void addedUniformOnRestoreDay() {
        LocalDate restoreDay = LocalDate.ofInstant(NOW, ZONE);
        for (long n = 0; n < 10; n++) {
            Setup s = setup(n);
            for (MediaItem i : s.plan().items()) {
                assertEquals(restoreDay, LocalDate.ofInstant(i.added(), ZONE), i.name());
                assertFalse(i.added().isBefore(i.taken()), "added before taken: " + i.name());
            }
        }
    }

    @Test
    void neverAfterNowNorBeforeThePhoneStart() {
        for (long n = 0; n < 20; n++) {
            Setup s = setup(n, YearMonth.of(2026, 11), 25);
            assertFalse(s.plan().items().isEmpty());
            for (MediaItem i : s.plan().items()) {
                assertFalse(i.taken().isAfter(NOW), "taken after now: " + i);
                assertFalse(i.added().isAfter(NOW), "added after now: " + i);
                assertFalse(i.added().isBefore(i.taken()), "added before taken: " + i);
                assertFalse(i.taken().isBefore(s.timeline().phoneStart()), "taken before the phone start: " + i);
            }
        }
    }

    @Test
    void fillSelectionDiffersPerSeed() {
        Set<String> a = fillIds(setup(1));
        Set<String> b = fillIds(setup(2));
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        Set<String> common = new HashSet<>(a);
        common.retainAll(b);
        assertFalse(a.isEmpty());
        assertTrue((double) common.size() / union.size() < 0.2, "Jaccard " + common.size() + "/" + union.size());
    }

    private static Set<String> fillIds(Setup s) {
        return s.plan().items().stream().filter(i -> i.kind() == MediaKind.CAMERA_FILL).map(MediaItem::sourceId).collect(Collectors.toSet());
    }

    @Test
    void noBundledImagePool() throws IOException {
        Path resources = Path.of("src/main/resources");
        if (!Files.exists(resources)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(resources)) {
            List<Path> images = walk.filter(p -> p.toString().toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|webp)$")).toList();
            assertTrue(images.isEmpty(), "bundled images: " + images);
        }
    }

    @Test
    void chosenPhotosAreMovedRenamedAndKeepTheirTimeOfDay() {
        Setup s = setup(4);
        List<MediaItem> items = s.plan().items().stream().filter(i -> i.kind() == MediaKind.CAMERA_CHOSEN).toList();
        assertEquals(s.chosen().size(), items.size());
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(CAM.fileNameTemplate()).withZone(ZONE);
        for (ChosenPhoto c : s.chosen()) {
            MediaItem i = items.stream().filter(m -> m.sourceId().equals(c.path())).findFirst().orElseThrow();
            assertNotEquals("own_0.jpg", i.name());
            assertEquals(fmt.format(i.taken()), i.name(), "the name follows the new taken time");
            assertNull(i.lat(), "the real file keeps its own metadata, stripped by the writer");
            assertTrue(i.exif());
            LocalTime was = c.taken().atZone(ZONE).toLocalTime().withNano(0);
            assertEquals(was, i.taken().atZone(ZONE).toLocalTime().withNano(0), "time of day of " + c.path());
        }
    }

    @Test
    void chosenPhotosCarryTheOwnersStripMarksAndAlwaysTheGps() {
        // the real GPS tag is the owner's real place: it never stays, whatever the owner marked
        boolean sawChosenCopy = false;
        for (long n = 0; n < 10; n++) {
            Setup s = setup(n);
            for (MediaItem i : s.plan().items()) {
                if (i.kind() == MediaKind.CAMERA_FILL || i.kind() == MediaKind.SAVED || i.kind() == MediaKind.SCREENSHOT
                        || i.kind() == MediaKind.DOCUMENT) {
                    assertTrue(i.stripFields().isEmpty(), i.name());
                    continue;
                }
                String source = i.sourceId();
                ChosenPhoto c = s.chosen().stream().filter(x -> x.path().equals(source)).findFirst().orElse(null);
                if (c == null) {
                    continue; // a copy of a fill or saved item
                }
                Set<String> expected = new HashSet<>(c.stripFields());
                expected.add("gps");
                assertEquals(expected, i.stripFields(), i.kind() + " " + i.name());
            }
            sawChosenCopy |= s.plan().items().stream().anyMatch(i -> i.kind() == MediaKind.COPY
                    && s.chosen().stream().anyMatch(x -> x.path().equals(i.sourceId())));
        }
        assertTrue(sawChosenCopy, "no copy of a chosen photo in 10 plans: the copy case is unchecked");
    }

    @Test
    void surplusChosenPhotosAreDroppedWithAWarning() {
        Setup s = setup(5, YearMonth.of(2026, 10), 5000);
        List<MediaItem> chosen = s.plan().items().stream().filter(i -> i.kind() == MediaKind.CAMERA_CHOSEN).toList();
        long total = s.volume().months().stream().mapToLong(s.volume()::targetCamera).sum();
        assertTrue(chosen.size() <= total, "chosen " + chosen.size() + " of capacity " + total);
        assertFalse(s.plan().warnings().isEmpty());
        for (YearMonth m : s.volume().months()) {
            assertCamera(s, m);
        }
    }

    @Test
    void whichChosenPhotosAreDroppedIsRandomNotTheLastOnes() {
        Setup s = setup(5, YearMonth.of(2026, 10), 5000);
        long capacity = s.volume().months().stream().mapToLong(s.volume()::targetCamera).sum();
        int highest = s.plan().items().stream().filter(i -> i.kind() == MediaKind.CAMERA_CHOSEN)
                .mapToInt(i -> Integer.parseInt(i.sourceId().replaceAll("\\D+", ""))).max().orElse(0);
        assertTrue(highest > capacity + 500, "kept photos stop at " + highest + " of capacity " + capacity + ": the first ones are kept, not a random subset");
    }

    @Test
    void copiesAreSpreadOverTheWholeGallery() {
        for (long n = 0; n < 5; n++) {
            List<MediaItem> items = setup(n).plan().items();
            List<MediaItem> pool = items.stream().filter(i -> i.kind() == MediaKind.CAMERA_FILL || i.kind() == MediaKind.CAMERA_CHOSEN
                    || i.kind() == MediaKind.SAVED).sorted(java.util.Comparator.comparing(MediaItem::taken)).toList();
            java.util.Set<String> originals = items.stream().filter(i -> i.copyOf() != null).map(MediaItem::copyOf).collect(Collectors.toSet());
            List<Double> ranks = new ArrayList<>();
            for (int k = 0; k < pool.size(); k++) {
                if (originals.contains(pool.get(k).name())) {
                    ranks.add((double) k / pool.size());
                }
            }
            double mean = ranks.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            assertTrue(ranks.size() > 20 && mean > 0.35 && mean < 0.65, "mean position of the copied photos " + mean + " over " + ranks.size());
        }
    }

    @Test
    void everyBoardingPassTripHasItsScreenshot() {
        for (long n = 0; n < 20; n++) {
            Setup s = setup(n);
            for (String id : s.travel().boardingPassTrips()) {
                Trip trip = s.travel().trips().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
                MediaItem shot = s.plan().items().stream().filter(i -> ("boardingpass:" + id).equals(i.sourceId())).findFirst().orElse(null);
                if (shot == null) {
                    // only a month outside the volume model has no screenshot
                    YearMonth m = YearMonth.from(trip.from().atZone(ZONE).toLocalDate().minusDays(1));
                    assertFalse(s.volume().months().contains(m) && s.volume().targetScreenshots(m) > 0, "no screenshot for " + id);
                    continue;
                }
                assertEquals(MediaKind.SCREENSHOT, shot.kind());
                assertTrue(shot.taken().isBefore(trip.from()) && shot.taken().isAfter(trip.from().minusSeconds(3 * 86_400L)));
            }
        }
    }

    @Test
    void generatedItemsStayInTheDayAndNamesAreUnique() {
        for (long n = 0; n < 10; n++) {
            Setup s = setup(n);
            // the day of the views: from waking to the bedtime, or to midnight where that lies after it
            LocalTime wake = s.timeline().wakeTime();
            LocalTime bed = s.timeline().sleepTime();
            LocalTime end = bed.isAfter(wake) ? bed.plusMinutes(1) : LocalTime.MAX;
            Set<String> names = new HashSet<>();
            for (MediaItem i : s.plan().items()) {
                assertTrue(names.add(i.name()), "duplicate name " + i.name());
                if (i.kind() == MediaKind.CAMERA_FILL || i.kind() == MediaKind.SAVED || i.kind() == MediaKind.SCREENSHOT) {
                    if (i.sourceId().startsWith("boardingpass:")) {
                        continue; // taken the evening before the flight, whatever the bedtime
                    }
                    LocalTime at = i.taken().atZone(ZONE).toLocalTime();
                    assertTrue(!at.isBefore(wake) && at.isBefore(end), i.name() + " taken at " + at + ", day " + wake + " to " + end);
                }
            }
        }
    }

    @Test
    void clashingNamesMoveByASecondAndStayInStepWithTheTime() {
        // a camera that names files to the second, and a month with thousands of photos, force clashes
        DeviceCameraProfile seconds = new DeviceCameraProfile(4080, 3072, List.of(table(8)), "IMG_\\d{8}_\\d{6}\\.jpg",
                "'IMG_'yyyyMMdd_HHmmss'.jpg'", false);
        Persona p = org.oblivios.decoy.content.TestSetup.derive(Seed.of(1, 1), PersonaProfile.open(new PersonaInputs("Sam", "Example", "Utrecht", "NL", ZONE, Locale.forLanguageTag("nl-NL"))), 2026);
        List<MonthCount> months = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            months.add(new MonthCount(YearMonth.of(2026, 10).minusMonths(5 - i), 6000, 10, 8, 1_000_000L));
        }
        VolumeModel v = VolumeModel.fromCounts(new VolumeCounts(months), org.oblivios.decoy.content.Draws.of(Seed.of(1, 2)));
        Timeline t = Timeline.generate(p, v, nl(), NOW, org.oblivios.decoy.content.Draws.of(Seed.of(1, 3)));
        MediaPlan plan = MediaSurface.generate(v, t, TravelSurface.generate(t, nl(), LanguagePack.of("nl"), org.oblivios.decoy.content.Draws.of(Seed.of(1, 6))), nl(), seconds, List.of(), org.oblivios.decoy.content.Draws.of(Seed.of(1, 7)));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(seconds.fileNameTemplate()).withZone(ZONE);
        Set<String> names = new HashSet<>();
        for (MediaItem i : plan.items()) {
            assertTrue(names.add(i.name()), "duplicate name " + i.name());
            if (i.kind() == MediaKind.CAMERA_FILL) {
                assertEquals(fmt.format(i.taken()), i.name());
            }
        }
    }

    @Test
    void sameSeedSamePlan() {
        assertEquals(setup(8).plan(), setup(8).plan());
        assertNotEquals(setup(8).plan(), setup(9).plan());
    }

    @Test
    void profileRejectsATemplateThatDoesNotMatchItsRegex() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceCameraProfile(4080, 3072, List.of(table(8)),
                "PXL_\\d{8}_\\d{9}\\.jpg", "'IMG_'yyyyMMdd'.jpg'", true));
        assertThrows(IllegalArgumentException.class, () -> new DeviceCameraProfile(4080, 3072, List.of(new int[63]),
                "PXL_\\d{8}_\\d{9}\\.jpg", "'PXL_'yyyyMMdd_HHmmssSSS'.jpg'", true));
    }
}
