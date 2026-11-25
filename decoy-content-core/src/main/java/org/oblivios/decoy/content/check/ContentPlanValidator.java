// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;
import org.oblivios.decoy.content.country.SenderKind;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.plan.ContentPlan;
import org.oblivios.decoy.content.story.Place;
import org.oblivios.decoy.content.style.StyleVector;
import org.oblivios.decoy.content.surface.CalEvent;
import org.oblivios.decoy.content.surface.CalendarSurface;
import org.oblivios.decoy.content.surface.Contact;
import org.oblivios.decoy.content.surface.MediaItem;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.surface.SmsMessage;
import org.oblivios.decoy.content.surface.SmsThread;
import org.oblivios.decoy.content.surface.Trip;
import org.oblivios.decoy.content.surface.WifiNetwork;

/**
 * The contradiction checker: reads a plan the way an examiner reads the decoy and reports every place where two
 * surfaces disagree. The rules are {@code R-PHOTO-PLACE}, {@code R-CALL-CONTACT}, {@code R-SMS-SENDER},
 * {@code R-BIRTHDAY}, {@code R-WIFI-PLACE}, {@code R-FUTURE}, {@code R-MAIL-AGE} and, for the pool conversations,
 * {@code R-SLOT}, {@code R-REQUIRES}, {@code R-REGISTER}, {@code R-UNREWRITTEN} and {@code R-GROUP-MEMBER}. An empty
 * list is a clean plan.
 */
public final class ContentPlanValidator {
    /** A photo counts as taken at a place when its tag lies within this distance of it; the generator jitters by about 22 m. */
    private static final double PLACE_RADIUS_METRES = 250;
    private static final Pattern NOT_A_LETTER = Pattern.compile("[^\\p{L}]+");

    private ContentPlanValidator() {}

    public static List<Violation> check(ContentPlan p) {
        List<Violation> v = new ArrayList<>();
        photoPlace(p, v);
        callContact(p, v);
        smsSender(p, v);
        birthday(p, v);
        wifiPlace(p, v);
        future(p, v);
        mailAge(p, v);
        slot(p, v);
        conversations(p, v);
        groupMember(p, v);
        return v;
    }

    private static void slot(ContentPlan p, List<Violation> v) {
        Stream.concat(p.people().threads().stream().flatMap(t -> t.messages().stream()),
                p.reserve().stream().flatMap(c -> c.messages().stream())).forEach(m -> {
            if (m.body().contains("{")) {
                v.add(new Violation("R-SLOT", "message at " + m.at() + " holds a slot that was not filled: " + m.body()));
            }
        });
    }

    /** The reserve counts as well: the roll-forward writes it later with no further check. */
    private static void conversations(ContentPlan p, List<Violation> v) {
        Set<String> street = Set.copyOf(LanguagePack.of(p.persona().language()).list("register.STREET.words"));
        Map<String, Relation> relations = new HashMap<>();
        p.people().contacts().forEach(c -> relations.put(c.id(), c.relation()));
        Stream.concat(p.conversations().stream(), p.reserve().stream()).forEach(c -> {
            if (!c.rewritten()) {
                v.add(new Violation("R-UNREWRITTEN", "conversation " + c.poolId() + " was not rewritten"));
            }
            if (!p.persona().facts().containsAll(c.requires())) {
                v.add(new Violation("R-REQUIRES", "conversation " + c.poolId() + " presumes " + c.requires()
                        + ", and the persona does not have all of it"));
            }
            // a group is written in the persona's own style, as the picker matched it
            Relation relation = c.group() == null ? relations.get(c.contactIds().get(0)) : null;
            StyleVector own = relation == null ? p.styles().persona()
                    : p.styles().personaTowards().getOrDefault(relation, p.styles().persona());
            if (own.register() != StyleVector.Register.FORMAL && own.register() != StyleVector.Register.NEUTRAL) {
                return;
            }
            c.messages().stream().filter(m -> !m.incoming()).forEach(m -> {
                for (String word : NOT_A_LETTER.split(m.body().toLowerCase(Locale.ROOT))) {
                    if (street.contains(word)) {
                        v.add(new Violation("R-REGISTER", "conversation " + c.poolId() + ": the persona writes " + own.register()
                                + " there, and says '" + word + "'"));
                    }
                }
            });
        });
    }

    private static void groupMember(ContentPlan p, List<Violation> v) {
        Set<String> ids = new HashSet<>();
        p.people().contacts().forEach(c -> ids.add(c.id()));
        for (SmsThread t : p.people().threads()) {
            for (String member : t.members()) {
                if (!ids.contains(member)) {
                    v.add(new Violation("R-GROUP-MEMBER", "group " + t.title() + " holds " + member + ", which is no contact"));
                }
            }
            if (t.members().isEmpty()) {
                continue;
            }
            for (SmsMessage m : t.messages()) {
                // an immutable list refuses a null in contains, so the missing sender is tested first
                if (m.incoming() && (m.from() == null || !t.members().contains(m.from()))) {
                    v.add(new Violation("R-GROUP-MEMBER", "group " + t.title() + " has a message at " + m.at() + " from "
                            + m.from() + ", which is no member"));
                }
            }
        }
    }

    private static void photoPlace(ContentPlan p, List<Violation> v) {
        ZoneId zone = p.timeline().zone();
        for (MediaItem i : p.media().items()) {
            if (i.lat() == null || i.lon() == null) {
                continue;
            }
            LocalDate day = LocalDate.ofInstant(i.taken(), zone);
            List<Place> valid = p.timeline().places();
            for (Trip trip : p.travel().trips()) {
                if (!day.isBefore(LocalDate.ofInstant(trip.from(), zone)) && !day.isAfter(LocalDate.ofInstant(trip.to(), zone))) {
                    valid = trip.places();
                    break;
                }
            }
            double nearest = Double.MAX_VALUE;
            for (Place place : valid) {
                nearest = Math.min(nearest, metres(i.lat(), i.lon(), place.lat(), place.lon()));
            }
            if (nearest > PLACE_RADIUS_METRES) {
                v.add(new Violation("R-PHOTO-PLACE", i.name() + " on " + day + " lies " + Math.round(nearest) + " m from every place of that day"));
            }
        }
    }

    private static void callContact(ContentPlan p, List<Violation> v) {
        Set<String> numbers = contactNumbers(p);
        p.people().calls().forEach(c -> {
            if (!numbers.contains(c.number())) {
                v.add(new Violation("R-CALL-CONTACT", "call at " + c.at() + " to " + c.number() + ", which is no contact"));
            }
        });
    }

    private static void smsSender(ContentPlan p, List<Violation> v) {
        Set<String> known = contactNumbers(p);
        CountryPack pack = packOf(p);
        for (SenderKind kind : SenderKind.values()) {
            pack.senders(kind).forEach(s -> known.add(s.name()));
        }
        p.people().threads().forEach(t -> {
            if (t.members().isEmpty() && !known.contains(t.address())) { // a group thread is checked by its members
                v.add(new Violation("R-SMS-SENDER", "thread with " + t.address() + ", which is no contact and no sender of the country pack"));
            }
        });
    }

    /** Both ways: a contact's birthday has its yearly event, and a yearly event belongs to a contact with that birthday. */
    private static void birthday(ContentPlan p, List<Violation> v) {
        for (Contact c : p.people().contacts()) {
            if (c.birthday() != null && p.calendar().events().stream().noneMatch(e -> birthdayOf(e, c))) {
                v.add(new Violation("R-BIRTHDAY", "contact " + c.name() + " has a birthday and no yearly event"));
            }
        }
        for (CalEvent e : p.calendar().events()) {
            if (e.yearly() && p.people().contacts().stream().noneMatch(c -> c.birthday() != null && birthdayOf(e, c))) {
                v.add(new Violation("R-BIRTHDAY", "yearly event '" + e.title() + "' belongs to no contact with that birthday"));
            }
        }
    }

    private static boolean birthdayOf(CalEvent e, Contact c) {
        return e.yearly() && c.birthday() != null && e.title().contains(c.name())
                && e.start().atZone(ZoneOffset.UTC).getMonth() == c.birthday().getMonth()
                && e.start().atZone(ZoneOffset.UTC).getDayOfMonth() == c.birthday().getDayOfMonth();
    }

    /** A saved network sits at a place of the story; the one network of no place is the one the phone is on. */
    private static void wifiPlace(ContentPlan p, List<Violation> v) {
        int withoutPlace = 0;
        for (WifiNetwork w : p.network().wifi()) {
            if (w.place() == null) {
                withoutPlace++;
            } else if (!p.timeline().places().contains(w.place())) {
                v.add(new Violation("R-WIFI-PLACE", "network " + w.ssid() + " sits at " + w.place().name() + ", which is no place of the story"));
            }
        }
        if (withoutPlace > 1) {
            v.add(new Violation("R-WIFI-PLACE", withoutPlace + " saved networks have no place; only the current network may"));
        }
    }

    /** No instant of any surface lies after the moment the story ends; calendar entries may reach the horizon past it. */
    private static void future(ContentPlan p, List<Violation> v) {
        Instant now = p.timeline().now();
        BiConsumer<String, Instant> late = (what, at) -> {
            if (at != null && at.isAfter(now)) {
                v.add(new Violation("R-FUTURE", what + " at " + at + " lies after " + now));
            }
        };
        p.people().calls().forEach(c -> late.accept("call", c.at()));
        p.people().threads().forEach(t -> t.messages().forEach(m -> late.accept("SMS", m.at())));
        p.people().voicemails().forEach(m -> late.accept("voicemail", m.at()));
        p.people().contacts().forEach(c -> late.accept("last contact of " + c.name(), c.lastContacted()));
        // a calendar entry may lie ahead, up to the horizon the generator uses
        Instant horizon = now.plus(Duration.ofDays(CalendarSurface.HORIZON_DAYS));
        p.calendar().events().forEach(e -> {
            if (e.start().isAfter(horizon)) {
                v.add(new Violation("R-FUTURE", "calendar entry '" + e.title() + "' at " + e.start() + " lies after the horizon " + horizon));
            }
        });
        p.calendar().notes().forEach(n -> late.accept("note", n.created()));
        p.travel().trips().forEach(t -> late.accept("trip " + t.id(), t.to()));
        p.media().items().forEach(i -> late.accept("taken " + i.name(), i.taken()));
        p.media().items().forEach(i -> late.accept("added " + i.name(), i.added()));
        p.mail().messages().forEach(m -> late.accept("mail '" + m.subject() + "'", m.date()));
        p.network().wifi().forEach(w -> late.accept("network " + w.ssid(), w.savedAt()));
        p.network().bluetooth().forEach(b -> late.accept("pairing " + b.name(), b.pairedAt()));
        p.usage().sessions().forEach(s -> late.accept("usage session of " + s.pkg(), s.start()));
        p.usage().lastUsed().forEach((pkg, at) -> late.accept("last use of " + pkg, at));
        p.apps().apps().forEach(a -> late.accept("install of " + a.pkg(), a.installed()));
        p.browser().history().forEach(h -> late.accept("visit of " + h.domain(), h.at()));
        p.traces().notifications().forEach(n -> late.accept("notification of " + n.pkg(), n.at()));
    }

    private static void mailAge(ContentPlan p, List<Violation> v) {
        Instant start = p.timeline().phoneStart();
        p.mail().messages().forEach(m -> {
            if (m.date().isBefore(start)) {
                v.add(new Violation("R-MAIL-AGE", "mail '" + m.subject() + "' of " + m.date() + " is older than the phone (" + start + ")"));
            }
        });
    }

    private static Set<String> contactNumbers(ContentPlan p) {
        Set<String> numbers = new HashSet<>();
        p.people().contacts().forEach(c -> numbers.add(c.number()));
        return numbers;
    }

    private static CountryPack packOf(ContentPlan p) {
        return Packs.forDevice(p.persona().inputs().countryCode(), p.timeline().zone(), p.persona().inputs().locale()).pack();
    }

    private static double metres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
