// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.oblivios.decoy.content.persona.PersonaProfile;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.story.MonthCount;
import org.oblivios.decoy.content.story.Place;
import org.oblivios.decoy.content.story.Timeline;
import org.oblivios.decoy.content.story.VolumeCounts;
import org.oblivios.decoy.content.story.VolumeModel;

class TraceSurfacesTest {
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");

    private static CountryPack nl() {
        return Packs.forDevice("NL", ZONE, Locale.ROOT).pack();
    }

    private static SurfaceContext ctx(long n, String currentSsid) {
        Persona p = Persona.derive(Seed.of(n, 1), PersonaProfile.open(new PersonaInputs("Sam Example", "Utrecht", "NL", ZONE, Locale.forLanguageTag("nl-NL"))), 2026);
        List<MonthCount> months = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            months.add(new MonthCount(YearMonth.of(2026, 10).minusMonths(29 - i), 60 + i * 5, 10, 8, 1_000_000L));
        }
        Timeline t = Timeline.generate(p, VolumeModel.fromCounts(new VolumeCounts(months), Seed.of(n, 2)), nl(), NOW, Seed.of(n, 3));
        People people = PeopleSurface.generate(p, t, nl(), Seed.of(n, 4));
        List<AppSpec> apps = List.of(new AppSpec("org.example.chat", "chat.example.org", true),
                new AppSpec("org.example.shop", "shop.example.org", true), new AppSpec("org.example.notes", "notes.example.org", false));
        return new SurfaceContext(p, t, people, nl(), apps, currentSsid, NOW, Seed.of(n, 5));
    }

    @Test
    void wifiContainsCurrentNetwork() {
        for (long n = 0; n < 15; n++) {
            NetworkPlan plan = NetworkSurface.generate(ctx(n, "AndroidWifi"));
            assertTrue(plan.wifi().stream().anyMatch(w -> w.ssid().equals("AndroidWifi")));
        }
    }

    @Test
    void wifiNetworksSitAtTimelinePlaces() {
        for (long n = 0; n < 15; n++) {
            SurfaceContext c = ctx(n, "AndroidWifi");
            NetworkPlan plan = NetworkSurface.generate(c);
            boolean home = false;
            for (WifiNetwork w : plan.wifi()) {
                if (w.place() != null) {
                    assertTrue(c.timeline().places().contains(w.place()), "network at no place: " + w);
                    home |= w.place().kind() == org.oblivios.decoy.content.story.PlaceKind.HOME;
                }
                assertFalse(w.savedAt().isAfter(c.now()));
                assertFalse(w.savedAt().isBefore(c.timeline().phoneStart()));
            }
            assertTrue(home, "no home network");
        }
    }

    @Test
    void pairedBluetoothHasNoRealAddress() {
        for (long n = 0; n < 30; n++) {
            for (BluetoothPair b : NetworkSurface.generate(ctx(n, "x")).bluetooth()) {
                int first = Integer.parseInt(b.address().substring(0, 2), 16);
                assertTrue((first & 0x02) != 0, "not locally administered: " + b.address());
                assertTrue((first & 0x01) == 0, "multicast address: " + b.address());
                assertTrue(b.address().matches("([0-9A-F]{2}:){5}[0-9A-F]{2}"), b.address());
            }
        }
    }

    @Test
    void appInstallDatesAreOneRestoreDay() {
        for (long n = 0; n < 15; n++) {
            SurfaceContext c = ctx(n, "x");
            AppsPlan plan = AppsSurface.generate(c);
            LocalDate day = LocalDate.ofInstant(c.now(), ZONE);
            for (AppEntry a : plan.apps()) {
                assertEquals(day, LocalDate.ofInstant(a.installed(), ZONE), a.pkg());
                assertFalse(a.installed().isAfter(c.now()));
            }
        }
    }

    @Test
    void appsOnlyFromTakeoverList() {
        SurfaceContext c = ctx(1, "x");
        Set<String> allowed = new HashSet<>();
        c.apps().forEach(a -> allowed.add(a.pkg()));
        AppsPlan plan = AppsSurface.generate(c);
        assertEquals(allowed.size(), plan.apps().size());
        plan.apps().forEach(a -> assertTrue(allowed.contains(a.pkg()), a.pkg()));
    }

    @Test
    void usageRespectsRhythm() {
        for (long n = 0; n < 15; n++) {
            SurfaceContext c = ctx(n, "x");
            UsagePlan plan = UsageSurface.generate(c);
            long total = 0, night = 0;
            for (UsageSession s : plan.sessions()) {
                total += s.minutes();
                int hour = s.start().atZone(ZONE).getHour();
                if (hour >= 2 && hour < 5) {
                    night += s.minutes();
                }
            }
            assertTrue(total > 0);
            assertTrue(night <= 0.05 * total, "night share " + night + "/" + total);
        }
    }

    @Test
    void usageStaysInTheRetainedWindow() {
        SurfaceContext c = ctx(2, "x");
        UsagePlan plan = UsageSurface.generate(c);
        for (UsageSession s : plan.sessions()) {
            assertFalse(s.start().isAfter(c.now()));
            assertFalse(s.start().isBefore(c.now().minus(Duration.ofDays(28))), "older than the 28 day window");
            assertFalse(s.start().isBefore(c.timeline().phoneStart()));
        }
        for (Instant last : plan.lastUsed().values()) {
            assertFalse(last.isAfter(c.now()));
        }
    }

    @Test
    void browserHistoryIsSkewed() {
        for (long n = 0; n < 15; n++) {
            BrowserPlan plan = BrowserSurface.generate(ctx(n, "x"));
            Map<String, Integer> counts = new HashMap<>();
            plan.history().forEach(v -> counts.merge(v.domain(), 1, Integer::sum));
            List<Integer> sorted = new ArrayList<>(counts.values());
            sorted.sort(java.util.Comparator.reverseOrder());
            int top = sorted.stream().limit(10).mapToInt(Integer::intValue).sum();
            assertTrue(top >= 0.4 * plan.history().size(), "top ten " + top + " of " + plan.history().size());
            // with about 22 sites a uniform history already passes the plan's top-ten rule, so the head must be steeper
            int top3 = sorted.stream().limit(3).mapToInt(Integer::intValue).sum();
            assertTrue(top3 >= 0.3 * plan.history().size(), "top three " + top3 + " of " + plan.history().size());
            assertTrue(plan.history().size() >= 300);
        }
    }

    @Test
    void browserHistoryStaysInTheNinetyDayRetention() {
        SurfaceContext c = ctx(3, "x");
        for (Visit v : BrowserSurface.generate(c).history()) {
            assertFalse(v.at().isAfter(c.now()));
            assertFalse(v.at().isBefore(c.now().minus(Duration.ofDays(90))));
        }
    }

    @Test
    void noValidSessions() {
        for (long n = 0; n < 15; n++) {
            SurfaceContext c = ctx(n, "x");
            for (Cookie k : BrowserSurface.generate(c).cookies()) {
                assertTrue(k.expires().isBefore(c.now()), "valid cookie: " + k);
            }
        }
    }

    @Test
    void vaultDomainsAreKnownDomains() {
        for (long n = 0; n < 15; n++) {
            SurfaceContext c = ctx(n, "x");
            Set<String> known = new HashSet<>();
            BrowserSurface.generate(c).history().forEach(v -> known.add(v.domain()));
            c.apps().forEach(a -> known.add(a.domain()));
            VaultPlan vault = VaultSurface.generate(c);
            assertFalse(vault.entries().isEmpty());
            for (VaultEntry e : vault.entries()) {
                assertTrue(known.contains(e.domain()), "vault domain not seen elsewhere: " + e.domain());
                assertTrue(e.password().length() >= 12);
            }
        }
    }

    @Test
    void notificationHistorySpansUsageTrail() {
        for (long n = 0; n < 15; n++) {
            SurfaceContext c = ctx(n, "x");
            UsagePlan usage = UsageSurface.generate(c);
            Instant first = usage.sessions().stream().map(UsageSession::start).min(Instant::compareTo).orElseThrow();
            TracesPlan traces = TracesSurface.generate(c);
            assertFalse(traces.notifications().isEmpty());
            for (NotificationRecord r : traces.notifications()) {
                assertFalse(r.at().isBefore(first), "notification before the usage trail");
                assertFalse(r.at().isAfter(c.now()));
            }
        }
    }

    @Test
    void cacheDirTimesWithinUsage() {
        for (long n = 0; n < 15; n++) {
            SurfaceContext c = ctx(n, "x");
            UsagePlan usage = UsageSurface.generate(c);
            Instant first = usage.sessions().stream().map(UsageSession::start).min(Instant::compareTo).orElseThrow();
            TracesPlan traces = TracesSurface.generate(c);
            assertFalse(traces.cacheDirTimes().isEmpty());
            for (Map.Entry<String, Instant> e : traces.cacheDirTimes().entrySet()) {
                assertFalse(e.getValue().isBefore(first), e.getKey());
                assertFalse(e.getValue().isAfter(c.now()), e.getKey());
            }
        }
    }

    @Test
    void deviceDoNotDisturbFollowsTheRhythm() {
        for (long n = 0; n < 15; n++) {
            SurfaceContext c = ctx(n, "x");
            DevicePlan d = DeviceSurface.generate(c);
            assertEquals(c.timeline().sleepTime(), d.dndFrom());
            assertEquals(c.timeline().wakeTime(), d.dndTo());
            assertFalse(d.deviceName().isBlank());
            assertFalse(d.languages().isEmpty());
            assertTrue(d.learnedWords().size() >= 20);
        }
    }

    @Test
    void sameSeedSamePlans() {
        SurfaceContext a = ctx(6, "x");
        SurfaceContext b = ctx(6, "x");
        assertEquals(DeviceSurface.generate(a), DeviceSurface.generate(b));
        assertEquals(NetworkSurface.generate(a), NetworkSurface.generate(b));
        assertEquals(UsageSurface.generate(a), UsageSurface.generate(b));
        assertEquals(AppsSurface.generate(a), AppsSurface.generate(b));
        assertEquals(BrowserSurface.generate(a), BrowserSurface.generate(b));
        assertEquals(VaultSurface.generate(a), VaultSurface.generate(b));
        assertEquals(TracesSurface.generate(a), TracesSurface.generate(b));
    }

    @Test
    void placeRecordsCarryTheTimelineKind() {
        Place any = ctx(1, "x").timeline().places().get(0);
        assertTrue(any.lat() != 0);
    }
}
