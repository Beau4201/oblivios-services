// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.PersonaInputs;
import org.oblivios.decoy.content.check.rules.CCalls;
import org.oblivios.decoy.content.check.rules.CCloseness;
import org.oblivios.decoy.content.check.rules.EStale;
import org.oblivios.decoy.content.check.rules.FPresumes;
import org.oblivios.decoy.content.check.rules.GClock;
import org.oblivios.decoy.content.check.rules.GOrigin;
import org.oblivios.decoy.content.check.rules.GSignature;
import org.oblivios.decoy.content.check.rules.GSpread;
import org.oblivios.decoy.content.check.rules.HHardware;
import org.oblivios.decoy.content.check.rules.MRaw;
import org.oblivios.decoy.content.check.rules.NDistinct;
import org.oblivios.decoy.content.check.rules.OPresumes;
import org.oblivios.decoy.content.check.rules.RBirthday;
import org.oblivios.decoy.content.check.rules.RCallContact;
import org.oblivios.decoy.content.check.rules.RFuture;
import org.oblivios.decoy.content.check.rules.RGroupMember;
import org.oblivios.decoy.content.check.rules.RMailAge;
import org.oblivios.decoy.content.check.rules.RPhotoPlace;
import org.oblivios.decoy.content.check.rules.RRegister;
import org.oblivios.decoy.content.check.rules.RRequires;
import org.oblivios.decoy.content.check.rules.RSlot;
import org.oblivios.decoy.content.check.rules.RSmsSender;
import org.oblivios.decoy.content.check.rules.RUnrewritten;
import org.oblivios.decoy.content.check.rules.RWifiPlace;
import org.oblivios.decoy.content.check.rules.SOne;
import org.oblivios.decoy.content.check.rules.SPhysical;
import org.oblivios.decoy.content.check.rules.TAway;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.plan.ContentPlan;

/**
 * The two checkers of the life story design and the population rules: every rule is listed here once. The examiner
 * reads only the views and the setup answers; the origin checker reads the story as well; the population rules read
 * many plans. A rule whose id is in {@code off} is not run, so a test can switch each rule off (acceptance 4).
 */
public final class Checks {
    private static final List<ExaminerRule> EXAMINER = List.of(new NDistinct(), new FPresumes(), new SOne(), new MRaw(), new GClock(),
            new CCalls(), new EStale(), new HHardware(), new RSmsSender(), new RBirthday(), new RGroupMember());
    private static final List<OriginRule> ORIGIN = List.of(new GOrigin(), new CCloseness(), new TAway(), new SPhysical(), new OPresumes(),
            new RPhotoPlace(), new RCallContact(), new RWifiPlace(), new RMailAge(), new RRequires(), new RUnrewritten(), new RFuture(),
            new RSlot(), new RRegister());
    private static final List<PopulationRule> POPULATION = List.of(new GSignature(), new GSpread());
    private static final Set<String> IDS = ids(EXAMINER.stream().map(ExaminerRule::id), ORIGIN.stream().map(OriginRule::id),
            POPULATION.stream().map(PopulationRule::id));

    private Checks() {}

    @SafeVarargs
    private static Set<String> ids(java.util.stream.Stream<String>... parts) {
        Set<String> out = new LinkedHashSet<>();
        for (java.util.stream.Stream<String> s : parts) {
            s.forEach(id -> {
                if (!out.add(id)) {
                    throw new IllegalStateException("rule id " + id + " is listed twice");
                }
            });
        }
        return Set.copyOf(out);
    }

    public static Set<String> ids() {
        return IDS;
    }

    public static List<Violation> examine(PhoneViews v, Persona persona, CountryPack pack, Instant now, Set<String> off) {
        List<Violation> out = new ArrayList<>();
        for (ExaminerRule r : EXAMINER) {
            if (!off.contains(r.id())) {
                r.check(v, persona, pack, now, out);
            }
        }
        return out;
    }

    public static List<Violation> origins(PhoneViews v, PlanStory story, Set<String> off) {
        List<Violation> out = new ArrayList<>();
        for (OriginRule r : ORIGIN) {
            if (!off.contains(r.id())) {
                r.check(v, story, out);
            }
        }
        return out;
    }

    public static List<Violation> population(List<PhoneViews> plans, Set<String> off) {
        List<Violation> out = new ArrayList<>();
        for (PopulationRule r : POPULATION) {
            if (!off.contains(r.id())) {
                r.check(plans, out);
            }
        }
        return out;
    }

    /** Both checkers over one plan, nothing off. */
    public static List<Violation> all(ContentPlan p) {
        PhoneViews v = PhoneViews.of(p);
        List<Violation> out = examine(v, p.persona(), packOf(p.persona()), p.timeline().now(), Set.of());
        out.addAll(origins(v, PlanStory.of(p), Set.of()));
        return out;
    }

    /** The country pack the device of the persona's inputs uses. */
    public static CountryPack packOf(Persona persona) {
        PersonaInputs in = persona.inputs();
        return Packs.forDevice(in.countryCode(), in.zone(), in.locale()).pack();
    }

    /** Whether {@code text} holds {@code phrase} as whole words, ignoring case. */
    public static boolean hasPhrase(String text, String phrase) {
        if (text == null || phrase.isBlank()) {
            return false;
        }
        Pattern p = Pattern.compile("(?<!\\p{L})" + Pattern.quote(phrase.toLowerCase(Locale.ROOT)) + "(?!\\p{L})");
        return p.matcher(text.toLowerCase(Locale.ROOT)).find();
    }

    /** The distance in metres between two points on the earth. */
    public static double metres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** The text of a value for a violation detail, cut at 160 characters. */
    public static String shown(Object value) {
        String s = String.valueOf(value);
        return s.length() <= 160 ? s : s.substring(0, 157) + "...";
    }
}
