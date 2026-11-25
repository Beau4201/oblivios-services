// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.lang.LanguagePack;

/** Device name, languages, learned words, ringtones, Do Not Disturb, accessibility, default apps, backup state. */
public final class DeviceSurface {
    private DeviceSurface() {}

    public static DevicePlan generate(SurfaceContext ctx) {
        RandomGenerator r = ctx.seed().stream("device");
        LanguagePack lang = LanguagePack.of(ctx.persona().inputs().locale().toLanguageTag());
        String given = ctx.persona().inputs().ownerName().split("\\s+")[0];
        String name = lang.format("device.name", Map.of("name", given));

        List<String> languages = new ArrayList<>();
        languages.add(ctx.persona().inputs().locale().toLanguageTag());
        if (!languages.contains("en-US")) {
            languages.add("en-US");
        }
        List<String> layouts = lang.list("device.keyboard.layouts");

        Set<String> words = new LinkedHashSet<>();
        for (Contact c : ctx.people().contacts()) {
            if (words.size() < 40) {
                words.add(c.name().split("\\s+")[0].toLowerCase(java.util.Locale.ROOT));
            }
        }
        for (String w : lang.list("device.keyboard.words")) {
            if (r.nextInt(100) < 60) {
                words.add(w);
            }
        }

        Map<String, String> accessibility = new LinkedHashMap<>();
        accessibility.put("font_scale", r.nextInt(100) < 30 ? "1.15" : "1.0");
        accessibility.put("display_size", "default");
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("dialer", "com.android.dialer");
        defaults.put("sms", "com.android.messaging");
        defaults.put("browser", "app.vanadium.browser");
        defaults.put("home", "com.android.launcher3");

        return new DevicePlan(name, languages, layouts, new ArrayList<>(words),
                List.of("Default ringtone", "Default notification", "Default alarm"),
                ctx.timeline().sleepTime(), ctx.timeline().wakeTime(), accessibility, defaults, "off");
    }
}
