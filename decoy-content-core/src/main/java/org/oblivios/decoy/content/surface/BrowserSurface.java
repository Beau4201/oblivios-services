// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/** Browser history with a few favourite sites on top, as a real history is skewed. Site names are plain text. */
public final class BrowserSurface {
    private static final int RETENTION_DAYS = 90;
    private static final List<String> GLOBAL = List.of("wikipedia.org", "youtube.com", "reddit.com", "github.com",
            "stackoverflow.com", "openstreetmap.org", "duckduckgo.com", "imdb.com", "archive.org", "weather.com");
    private static final List<String> NL = List.of("nu.nl", "marktplaats.nl", "bol.com", "buienradar.nl", "ns.nl", "9292.nl",
            "funda.nl", "tweakers.net", "coolblue.nl", "ah.nl", "rijksoverheid.nl", "thuisbezorgd.nl");
    private static final List<String> PATHS = List.of("", "search?q=", "nieuws", "account", "producten", "kaart", "weer", "info", "contact");

    private BrowserSurface() {}

    public static BrowserPlan generate(SurfaceContext ctx) {
        RandomGenerator r = ctx.seed().stream("browser");
        List<String> pool = new ArrayList<>(GLOBAL);
        if ("NL".equals(ctx.pack().code())) {
            pool.addAll(NL);
        }
        Collections.shuffle(pool, new java.util.Random(r.nextLong()));
        List<String> domains = new ArrayList<>(pool.subList(0, Math.min(pool.size(), 24)));
        double[] cumulative = new double[domains.size()];
        double sum = 0;
        for (int i = 0; i < cumulative.length; i++) {
            sum += 1.0 / Math.pow(i + 1, 1.1); // Zipf: the first few sites take most visits
            cumulative[i] = sum;
        }

        Instant from = ctx.now().minus(Duration.ofDays(RETENTION_DAYS));
        if (from.isBefore(ctx.timeline().phoneStart())) {
            from = ctx.timeline().phoneStart();
        }
        int count = 600 + r.nextInt(1400);
        List<Visit> history = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double pick = r.nextDouble() * sum;
            int k = 0;
            while (cumulative[k] < pick) {
                k++;
            }
            String domain = domains.get(k);
            String url = "https://" + domain + "/" + PATHS.get(r.nextInt(PATHS.size()));
            history.add(new Visit(domain, url, Events.randomInstant(r, from, ctx.now(), ctx.timeline().zone(),
                    ctx.timeline().wakeTime(), LocalTime.of(23, 30))));
        }
        history.sort(java.util.Comparator.comparing(Visit::at));

        List<Cookie> cookies = new ArrayList<>();
        int cookieCount = 4 + r.nextInt(8);
        for (int i = 0; i < cookieCount; i++) {
            String domain = domains.get(r.nextInt(Math.min(8, domains.size())));
            cookies.add(new Cookie(domain, r.nextBoolean() ? "consent" : "lang", ctx.now().minus(Duration.ofDays(1 + r.nextInt(400)))));
        }
        return new BrowserPlan(history, cookies, new ArrayList<>(domains.subList(0, Math.min(5, domains.size()))));
    }
}
