// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PopulationRule;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.surface.BluetoothPair;
import org.oblivios.decoy.content.surface.Contact;
import org.oblivios.decoy.content.surface.Note;
import org.oblivios.decoy.content.surface.NotificationRecord;
import org.oblivios.decoy.content.surface.Visit;

/**
 * {@code G-SIGNATURE}: a value that more than half of the plans share, which marks a decoy as made by this generator:
 * the set of visited sites, a note text, the work network name with its digits masked, the set of the
 * {@value #PREFIX}-character prefixes of the contact numbers, a notification title or a Bluetooth name. A value of
 * {@code check/signature-allow.properties} is excepted. Judged only over {@value #MIN_PLANS} plans or more.
 */
public final class GSignature implements PopulationRule {
    static final int MIN_PLANS = 3;
    /** The country code and the start of the national number, as {@code +316257}. */
    static final int PREFIX = 7;
    private static final String ALLOW = "/org/oblivios/decoy/content/data/check/signature-allow.properties";

    private final Map<String, Function<PhoneViews, Set<String>>> kinds = new LinkedHashMap<>();
    private final Properties allow = read();

    public GSignature() {
        kinds.put("sites", v -> one(new TreeSet<>(v.browser().history().stream().map(Visit::domain).toList())));
        kinds.put("notes", v -> new HashSet<>(v.calendar().notes().stream().map(Note::text).toList()));
        kinds.put("wifi.work", v -> new HashSet<>(v.network().wifi().stream().filter(w -> w.place() != null && w.place().kind() == PlaceKind.WORK)
                .map(w -> w.ssid().replaceAll("[0-9]", "#")).toList()));
        kinds.put("prefixes", v -> one(new TreeSet<>(v.people().contacts().stream().map(Contact::number)
                .filter(n -> n != null && n.length() >= PREFIX).map(n -> n.substring(0, PREFIX)).toList())));
        kinds.put("notifications", v -> new HashSet<>(v.traces().notifications().stream().map(NotificationRecord::title).toList()));
        kinds.put("bluetooth", v -> new HashSet<>(v.network().bluetooth().stream().map(BluetoothPair::name).toList()));
    }

    @Override
    public String id() {
        return "G-SIGNATURE";
    }

    @Override
    public void check(List<PhoneViews> plans, List<Violation> out) {
        if (plans.size() < MIN_PLANS) {
            return;
        }
        kinds.forEach((kind, values) -> {
            Map<String, Integer> plansWith = new LinkedHashMap<>();
            for (PhoneViews v : plans) {
                values.apply(v).forEach(value -> plansWith.merge(value, 1, Integer::sum));
            }
            Set<String> allowed = new HashSet<>(List.of(allow.getProperty(kind, "").split("\\|")));
            plansWith.forEach((value, n) -> {
                if (2 * n > plans.size() && !allowed.contains(value)) {
                    out.add(new Violation(id(), kind + " '" + value + "' in " + n + " of " + plans.size() + " plans"));
                }
            });
        });
    }

    /** A whole set as one value, its members joined by a comma and a space; an empty set is no value. */
    private static Set<String> one(TreeSet<String> set) {
        return set.isEmpty() ? Set.of() : Set.of(String.join(", ", set));
    }

    private static Properties read() {
        try (InputStream in = GSignature.class.getResourceAsStream(ALLOW)) {
            if (in == null) {
                throw new IllegalStateException("the allow list of G-SIGNATURE is missing: " + ALLOW);
            }
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
