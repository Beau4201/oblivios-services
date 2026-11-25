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

/**
 * Notification history (Android keeps about the last days) and cache directory times, both inside the usage
 * trail of {@link UsageSurface}: a cache is touched when its app is used, so no time lies before the first session.
 */
public final class TracesSurface {
    private static final List<String> TITLES = List.of("New message", "Missed call", "Reminder", "Update available", "New activity");

    private TracesSurface() {}

    public static TracesPlan generate(SurfaceContext ctx) {
        RandomGenerator r = ctx.seed().stream("traces");
        UsagePlan usage = UsageSurface.generate(ctx);
        Instant first = usage.sessions().get(0).start();
        Instant from = ctx.now().minus(Duration.ofDays(3));
        if (from.isBefore(first)) {
            from = first;
        }
        List<String> packages = SystemApps.usable(ctx);
        List<NotificationRecord> notifications = new ArrayList<>();
        int count = 10 + r.nextInt(31);
        for (int i = 0; i < count; i++) {
            String pkg = packages.get(r.nextInt(packages.size()));
            notifications.add(new NotificationRecord(pkg, TITLES.get(r.nextInt(TITLES.size())),
                    Events.randomInstant(r, from, ctx.now(), ctx.timeline().zone(), ctx.timeline().wakeTime(), LocalTime.of(23, 30))));
        }
        notifications.sort(Comparator.comparing(NotificationRecord::at));

        Map<String, Instant> cache = new LinkedHashMap<>();
        for (String pkg : packages) {
            Instant lastUse = usage.lastUsed().get(pkg);
            Instant touched = lastUse.minus(Duration.ofMinutes(r.nextInt(120)));
            cache.put(pkg, touched.isBefore(first) ? first : touched);
        }
        return new TracesPlan(notifications, cache);
    }
}
