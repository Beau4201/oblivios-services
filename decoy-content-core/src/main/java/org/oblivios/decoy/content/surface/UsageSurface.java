// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/** The usage trail Android keeps: sessions of the last 28 days, a few after midnight, none in the small hours. */
public final class UsageSurface {
    private static final int WINDOW_DAYS = 28;

    private UsageSurface() {}

    public static UsagePlan generate(SurfaceContext ctx) {
        RandomGenerator r = ctx.seed().stream("usage");
        List<String> packages = SystemApps.usable(ctx);
        Instant windowStart = ctx.now().minus(Duration.ofDays(WINDOW_DAYS));
        if (windowStart.isBefore(ctx.timeline().phoneStart())) {
            windowStart = ctx.timeline().phoneStart();
        }
        long days = Math.max(1, Duration.between(windowStart, ctx.now()).toDays());
        int count = (int) Math.max(40, days * (25 + r.nextInt(30)));
        List<UsageSession> sessions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            boolean lateNight = r.nextInt(100) < 3; // after midnight, never between 02:00 and 05:00
            LocalTime earliest = lateNight ? LocalTime.of(0, 0) : ctx.timeline().wakeTime();
            LocalTime latest = lateNight ? LocalTime.of(1, 30) : LocalTime.of(23, 59);
            Instant start = Events.randomInstant(r, windowStart, ctx.now(), ctx.timeline().zone(), earliest, latest);
            String pkg = packages.get((int) Math.min(packages.size() - 1, Math.abs(r.nextGaussian()) * packages.size() / 2.5));
            sessions.add(new UsageSession(start, 1 + (int) Math.min(40, Math.abs(r.nextGaussian()) * 8), pkg));
        }
        sessions.sort(Comparator.comparing(UsageSession::start));

        Map<String, Instant> lastUsed = new LinkedHashMap<>();
        for (UsageSession s : sessions) {
            lastUsed.merge(s.pkg(), s.start(), (a, b) -> a.isAfter(b) ? a : b);
        }
        for (String pkg : packages) {
            lastUsed.computeIfAbsent(pkg, p -> windowStart(ctx, r)); // not used in the window: used before it
        }
        return new UsagePlan(sessions, lastUsed);
    }

    private static Instant windowStart(SurfaceContext ctx, RandomGenerator r) {
        Instant before = ctx.now().minus(Duration.ofDays(WINDOW_DAYS + 1L + r.nextInt(60)));
        return before.isBefore(ctx.timeline().phoneStart()) ? ctx.timeline().phoneStart() : before;
    }
}
