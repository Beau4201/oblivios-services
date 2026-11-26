// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The picked apps. Each carries the install day of the setup, never an older one: {@code pm install-existing}
 * stamps the time of the call (row DUR-131), which is the restore day of the story.
 */
public final class AppsSurface {
    private AppsSurface() {}

    public static AppsPlan generate(SurfaceContext ctx) {
        RandomGenerator r = ctx.draws().stream("apps");
        ZoneId zone = ctx.timeline().zone();
        LocalDate day = LocalDate.ofInstant(ctx.now(), zone);
        Instant dayStart = day.atStartOfDay(zone).toInstant();
        long minutesSince = Math.max(0, java.time.Duration.between(dayStart, ctx.now()).toMinutes());
        List<AppEntry> apps = new ArrayList<>();
        for (AppSpec spec : ctx.apps()) {
            Instant installed = dayStart.plusSeconds(60L * (minutesSince == 0 ? 0 : r.nextLong(minutesSince + 1)));
            String version = (1 + r.nextInt(9)) + "." + r.nextInt(30) + "." + r.nextInt(10);
            long data = (5L + r.nextInt(400)) * 1_000_000L;
            long cache = (1L + r.nextInt(80)) * 1_000_000L;
            apps.add(new AppEntry(spec.pkg(), installed, version, data, cache));
        }
        return new AppsPlan(apps);
    }
}
