// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Sender;
import org.oblivios.decoy.content.country.SenderKind;
import org.oblivios.decoy.content.story.Place;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.story.Timeline;
import org.oblivios.decoy.content.story.VolumeModel;

/**
 * The gallery of the decoy: per month the counts of the volume model, split over the owner's chosen photos (moved
 * to times of the story, renamed to the camera's pattern) and fill in the shape of this device's camera, saved web
 * images, screenshots (one per boarding-pass trip), a few documents, and edited copies of about one photo in ten.
 * Every item is taken inside the story and added on the one restore day.
 *
 * <p>No image data is chosen here: a fill item holds a selection key that the downloader resolves against a large
 * public source (no bundled pool). A fill photo sits at a place of its day (home, work, a trip place) when the
 * camera writes GPS, which is what the contradiction checker tests.
 */
public final class MediaSurface {
    private static final double GPS_JITTER_DEGREES = 0.0002; // about 22 m
    private static final List<String> SAVED_BASES = List.of("download", "images", "image", "photo", "wallpaper", "unnamed", "IMG");
    private static final List<String> SAVED_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp");
    // shortcut: web-image sizes written by hand; upgrade when the sources record measures real ones.
    private static final List<int[]> SAVED_SIZES = List.of(new int[] {1080, 1350}, new int[] {1200, 800}, new int[] {800, 600},
            new int[] {1280, 720}, new int[] {1080, 1080}, new int[] {2048, 1365});
    // shortcut: the system's screenshot name is assumed to be the AOSP pattern; Task 17 reads it from the device.
    private static final DateTimeFormatter SCREENSHOT = DateTimeFormatter.ofPattern("'Screenshot_'yyyyMMdd-HHmmss'.png'", Locale.ROOT);

    private MediaSurface() {}

    public static MediaPlan generate(VolumeModel v, Timeline t, TravelPlan travel, CountryPack c, DeviceCameraProfile cam,
            List<ChosenPhoto> chosen, Draws s) {
        return new Builder(v, t, travel, c, cam, s).build(chosen);
    }

    private static final class Builder {
        private final VolumeModel volume;
        private final Timeline timeline;
        private final TravelPlan travel;
        private final CountryPack pack;
        private final DeviceCameraProfile cam;
        private final Draws seed;
        private final ZoneId zone;
        private final Instant now;
        private final List<MediaItem> items = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final Set<String> names = new HashSet<>();
        private final Map<YearMonth, Instant[]> windows = new TreeMap<>();
        private final Instant restoreStart;
        private final long minutesSinceRestoreStart;
        private final RandomGenerator addedStream;
        private final Function<Instant, String> cameraName;
        private final Function<Instant, String> screenshotName;

        Builder(VolumeModel v, Timeline t, TravelPlan travel, CountryPack c, DeviceCameraProfile cam, Draws s) {
            this.volume = v;
            this.timeline = t;
            this.travel = travel;
            this.pack = c;
            this.cam = cam;
            this.seed = s;
            this.zone = t.zone();
            this.now = t.now();
            this.addedStream = s.stream("media-added");
            DateTimeFormatter shotFormat = SCREENSHOT.withZone(zone);
            this.cameraName = at -> cam.fileName(at, zone);
            this.screenshotName = shotFormat::format;
            this.restoreStart = LocalDate.ofInstant(now, zone).atStartOfDay(zone).toInstant();
            this.minutesSinceRestoreStart = Math.max(0, ChronoUnit.MINUTES.between(restoreStart, now));
        }

        MediaPlan build(List<ChosenPhoto> chosen) {
            for (YearMonth m : volume.months()) {
                Instant start = Instant.ofEpochMilli(Math.max(m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                        timeline.phoneStart().toEpochMilli()));
                Instant end = Instant.ofEpochMilli(Math.min(m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                        now.toEpochMilli()));
                if (start.isBefore(end)) {
                    windows.put(m, new Instant[] {start, end});
                } else {
                    warnings.add("month " + m + " lies outside the story: no media for it");
                }
            }
            Map<YearMonth, Integer> cameraCap = new TreeMap<>();
            Map<YearMonth, Integer> shotCap = new TreeMap<>();
            for (YearMonth m : windows.keySet()) {
                cameraCap.put(m, volume.targetCamera(m));
                shotCap.put(m, volume.targetScreenshots(m));
            }

            placeChosen(chosen, cameraCap);
            fill(cameraCap);
            saved();
            screenshots(shotCap);
            documents();
            copies();

            items.sort(Comparator.comparing(MediaItem::taken).thenComparing(MediaItem::name));
            return new MediaPlan(items, warnings);
        }

        // ---- camera --------------------------------------------------------------------------------------

        private void placeChosen(List<ChosenPhoto> chosen, Map<YearMonth, Integer> cap) {
            RandomGenerator r = seed.stream("media-chosen");
            List<ChosenPhoto> order = new ArrayList<>(chosen);
            Shuffle.inPlace(order, r);
            int dropped = 0;
            for (ChosenPhoto photo : order) {
                int total = cap.values().stream().mapToInt(Integer::intValue).sum();
                if (total == 0) {
                    dropped++;
                    continue;
                }
                int pick = r.nextInt(total);
                YearMonth month = null;
                for (Map.Entry<YearMonth, Integer> e : cap.entrySet()) {
                    pick -= e.getValue();
                    if (pick < 0) {
                        month = e.getKey();
                        break;
                    }
                }
                cap.merge(month, -1, Integer::sum);
                Set<String> strip = new HashSet<>(photo.stripFields());
                strip.add("gps"); // the real tag is the owner's real place, whatever the owner marked
                LocalTime timeOfDay = photo.taken().atZone(zone).toLocalTime().withNano(0);
                Named named = timed(withMillis(draw(windows.get(month), r, timeOfDay), r, windows.get(month)[1]), cameraName);
                items.add(new MediaItem(MediaKind.CAMERA_CHOSEN, named.name(), named.at(), addedFor(named.at()), photo.path(), null,
                        0, 0, true, null, null, null, strip));
            }
            if (dropped > 0) {
                warnings.add(dropped + " chosen photos exceed the camera volume of the story and are not used");
            }
        }

        private void fill(Map<YearMonth, Integer> cap) {
            RandomGenerator r = seed.stream("media-fill");
            RandomGenerator ids = seed.stream("media-fill-id");
            for (Map.Entry<YearMonth, Integer> e : cap.entrySet()) {
                Instant[] w = windows.get(e.getKey());
                for (int k = 0; k < e.getValue(); k++) {
                    Named named = timed(withMillis(draw(w, r, null), r, w[1]), cameraName);
                    Double lat = null;
                    Double lon = null;
                    if (cam.gps()) {
                        Place place = placeOfDay(named.at(), r);
                        lat = place.lat() + (r.nextDouble() * 2 - 1) * GPS_JITTER_DEGREES;
                        lon = place.lon() + (r.nextDouble() * 2 - 1) * GPS_JITTER_DEGREES;
                    }
                    items.add(new MediaItem(MediaKind.CAMERA_FILL, named.name(), named.at(), addedFor(named.at()),
                            "fill:" + String.format("%016x", ids.nextLong()), null, cam.width(), cam.height(), true, cam.id(), lat, lon, Set.of()));
                }
            }
        }

        /** The place a photo of this moment sits at: a trip place on a trip day, else home, work, a cafe or a shop by the hour. */
        private Place placeOfDay(Instant at, RandomGenerator r) {
            LocalDate day = LocalDate.ofInstant(at, zone);
            for (Trip trip : travel.trips()) {
                if (!day.isBefore(LocalDate.ofInstant(trip.from(), zone)) && !day.isAfter(LocalDate.ofInstant(trip.to(), zone))) {
                    return trip.places().get(r.nextInt(trip.places().size()));
                }
            }
            int hour = at.atZone(zone).getHour();
            boolean workHours = day.getDayOfWeek().getValue() <= DayOfWeek.FRIDAY.getValue() && hour >= 9 && hour < 17;
            double x = r.nextDouble();
            PlaceKind kind;
            if (workHours) {
                kind = x < 0.55 ? PlaceKind.WORK : x < 0.70 ? PlaceKind.CAFE : x < 0.80 ? PlaceKind.SHOP : PlaceKind.HOME;
            } else {
                kind = x < 0.50 ? PlaceKind.HOME : x < 0.75 ? PlaceKind.CAFE : PlaceKind.SHOP;
            }
            List<Place> of = timeline.places().stream().filter(p -> p.kind() == kind).toList();
            return of.get(r.nextInt(of.size()));
        }

        // ---- web images, screenshots, documents ------------------------------------------------------------

        private void saved() {
            RandomGenerator r = seed.stream("media-saved");
            for (YearMonth m : windows.keySet()) {
                Instant[] w = windows.get(m);
                for (int k = 0; k < volume.targetSaved(m); k++) {
                    Instant at = draw(w, r, null);
                    int[] size = SAVED_SIZES.get(r.nextInt(SAVED_SIZES.size()));
                    String name = unique(SAVED_BASES.get(r.nextInt(SAVED_BASES.size())), SAVED_EXTENSIONS.get(r.nextInt(SAVED_EXTENSIONS.size())));
                    items.add(new MediaItem(MediaKind.SAVED, name, at, addedFor(at), "saved:" + String.format("%016x", r.nextLong()), null,
                            size[0], size[1], false, null, null, null, Set.of()));
                }
            }
        }

        private void screenshots(Map<YearMonth, Integer> cap) {
            RandomGenerator r = seed.stream("media-shots");
            for (String id : travel.boardingPassTrips()) {
                Trip trip = travel.trips().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
                LocalDate day = LocalDate.ofInstant(trip.from(), zone).minusDays(1);
                Instant at = day.atTime(19 + r.nextInt(4), r.nextInt(60), r.nextInt(60)).atZone(zone).toInstant();
                YearMonth month = YearMonth.from(day);
                if (!windows.containsKey(month) || cap.get(month) <= 0 || at.isBefore(timeline.phoneStart()) || at.isAfter(now)) {
                    continue;
                }
                cap.merge(month, -1, Integer::sum);
                Named named = timed(at, screenshotName);
                items.add(shot(named, "boardingpass:" + id));
            }
            for (Map.Entry<YearMonth, Integer> e : cap.entrySet()) {
                Instant[] w = windows.get(e.getKey());
                for (int k = 0; k < e.getValue(); k++) {
                    Named named = timed(draw(w, r, null), screenshotName);
                    items.add(shot(named, "appstate:" + String.format("%016x", r.nextLong())));
                }
            }
        }

        private MediaItem shot(Named named, String sourceId) {
            return new MediaItem(MediaKind.SCREENSHOT, named.name(), named.at(), addedFor(named.at()), sourceId, null, 0, 0, false,
                    null, null, null, Set.of());
        }

        private void documents() {
            RandomGenerator r = seed.stream("media-docs");
            List<Sender> shops = pack.senders(SenderKind.SHOP);
            DateTimeFormatter date = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT).withZone(zone);
            for (YearMonth m : windows.keySet()) {
                if (r.nextInt(3) != 0) {
                    continue;
                }
                Instant at = draw(windows.get(m), r, null);
                String day = date.format(at);
                int type = r.nextInt(3);
                String base;
                String source;
                if (type == 0) {
                    String shop = shops.isEmpty() ? "" : "_" + shops.get(r.nextInt(shops.size())).name().replace(' ', '-');
                    base = "Receipt" + shop + "_" + day;
                    source = "doc:receipt";
                } else if (type == 1) {
                    base = "Ticket_" + day;
                    source = "doc:ticket";
                } else {
                    base = "Scan_" + day;
                    source = "doc:scan";
                }
                items.add(new MediaItem(MediaKind.DOCUMENT, unique(base, ".pdf"), at, addedFor(at), source, null, 0, 0, false, null, null,
                        null, Set.of()));
            }
        }

        // ---- copies --------------------------------------------------------------------------------------

        /** An edited copy for about one photo in ten, at most one per photo. */
        private void copies() {
            RandomGenerator r = seed.stream("media-copies");
            List<MediaItem> pool = items.stream().filter(i -> i.kind() != MediaKind.DOCUMENT && i.kind() != MediaKind.SCREENSHOT).toList();
            long total = items.stream().filter(i -> i.kind() != MediaKind.DOCUMENT).count();
            int n = (int) Math.min(pool.size(), Math.round(total * (0.08 + 0.04 * r.nextDouble())));
            List<MediaItem> order = new ArrayList<>(pool);
            Shuffle.inPlace(order, r);
            for (MediaItem o : order.subList(0, n)) {
                int dot = o.name().lastIndexOf('.');
                items.add(new MediaItem(MediaKind.COPY, unique(o.name().substring(0, dot) + "-edited", o.name().substring(dot)), o.taken(),
                        addedFor(o.taken()), o.sourceId(), o.name(), o.width(), o.height(), o.exif(), o.profileId(), o.lat(), o.lon(), o.stripFields()));
            }
        }

        // ---- time and names ------------------------------------------------------------------------------

        private record Named(Instant at, String name) {}

        /** A day of the window and a time of that day inside the owner's waking hours (or the given one), never at or after the window's end. */
        private Instant draw(Instant[] w, RandomGenerator r, LocalTime fixedTimeOfDay) {
            LocalDate first = LocalDate.ofInstant(w[0], zone);
            LocalDate last = LocalDate.ofInstant(w[1].minusMillis(1), zone);
            long days = ChronoUnit.DAYS.between(first, last) + 1;
            for (int tries = 0; tries < 40; tries++) {
                LocalDate day = first.plusDays(r.nextLong(days));
                LocalTime tod = fixedTimeOfDay != null ? fixedTimeOfDay : wakingTime(r);
                Instant at = day.atTime(tod).atZone(zone).toInstant();
                if (!at.isBefore(w[0]) && at.isBefore(w[1])) {
                    return at;
                }
            }
            Instant late = w[1].minusSeconds(1 + r.nextInt(3600));
            return late.isBefore(w[0]) ? w[0] : late;
        }

        private LocalTime wakingTime(RandomGenerator r) {
            int wake = timeline.wakeTime().toSecondOfDay() / 60;
            // a sleep time after midnight (or equal to the wake time) ends the day at 23:59
            int end = timeline.sleepTime().isAfter(timeline.wakeTime()) ? timeline.sleepTime().toSecondOfDay() / 60 : 23 * 60 + 59;
            int minute = wake + r.nextInt(end - wake + 1);
            return LocalTime.of(minute / 60, minute % 60, r.nextInt(60));
        }

        private Instant withMillis(Instant at, RandomGenerator r, Instant windowEnd) {
            Instant moved = at.plusMillis(r.nextInt(1000));
            return moved.isBefore(windowEnd) ? moved : at;
        }

        /** Names a timed file; on a clash the time moves one second so name and time stay in step. */
        private Named timed(Instant at, Function<Instant, String> pattern) {
            int step = at.plusSeconds(1).isAfter(now) ? -1 : 1;
            String name;
            while (!names.add(name = pattern.apply(at))) {
                at = at.plusSeconds(step);
            }
            return new Named(at, name);
        }

        private Instant addedFor(Instant taken) {
            Instant drawn = restoreStart.plusSeconds(60L * (minutesSinceRestoreStart == 0 ? 0 : addedStream.nextLong(minutesSinceRestoreStart + 1)));
            return drawn.isBefore(taken) ? taken : drawn;
        }

        /** {@code base.ext}, then {@code base (1).ext}, {@code base (2).ext} as a browser names repeated downloads. */
        private String unique(String base, String extension) {
            String name = base + extension;
            for (int n = 1; !names.add(name); n++) {
                name = base + " (" + n + ")" + extension;
            }
            return name;
        }
    }
}
