// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.NoVerifiedRange;
import org.oblivios.decoy.content.country.Sender;
import org.oblivios.decoy.content.country.SenderKind;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.story.Place;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.story.Timeline;

/** Contacts, call log, SMS threads and voicemail, all of one life story. */
public final class PeopleSurface {
    /** Indices into the language's {@code contact.groups} (family, friends, work, other), weighted by repetition. */
    private static final int[] GROUP_WEIGHTS = {0, 1, 1, 2, 2, 3, 3, 3};
    private PeopleSurface() {}

    /** Throws {@link IllegalArgumentException} when the pack has no verified number range: no number is invented. */
    public static People generate(Persona p, Timeline t, CountryPack c, Seed s) {
        try {
            return build(p, t, c, s);
        } catch (NoVerifiedRange e) {
            throw new IllegalArgumentException("no verified number range for country pack " + c.code() + ": contacts cannot be created", e);
        }
    }

    private static People build(Persona p, Timeline t, CountryPack c, Seed s) throws NoVerifiedRange {
        RandomGenerator r = s.stream("people");
        ZoneId zone = p.inputs().zone();
        LanguagePack lang = LanguagePack.of(p.language());
        LanguagePack service = LanguagePack.forService(c, lang.tag());
        List<String> groupNames = lang.list("contact.groups");
        Set<String> used = new HashSet<>();
        String own = freshNumber(c, r, used);

        int count = Math.min(400, 70 + (int) (Math.abs(r.nextGaussian()) * 90));
        String[] ids = new String[count];
        String[] names = new String[count];
        String[] numbers = new String[count];
        boolean[] favourite = new boolean[count];
        String[] groups = new String[count];
        LocalDate[] birthdays = new LocalDate[count];
        PhotoKind[] photos = new PhotoKind[count];
        int favourites = r.nextInt(7);
        for (int i = 0; i < count; i++) {
            ids[i] = "c" + i;
            names[i] = r.nextInt(100) < 25 ? c.givenName(r) : c.givenName(r) + " " + c.familyName(r);
            numbers[i] = freshNumber(c, r, used);
            favourite[i] = i < favourites;
            groups[i] = groupNames.get(GROUP_WEIGHTS[r.nextInt(GROUP_WEIGHTS.length)]);
            birthdays[i] = r.nextInt(100) < 40 ? LocalDate.of(1955 + r.nextInt(55), 1 + r.nextInt(12), 1 + r.nextInt(28)) : null;
            photos[i] = r.nextInt(100) < 15 ? PhotoKind.INITIALS : PhotoKind.NONE;
        }

        // the slots of a message: first names of the contacts and the cafes and shops of the story
        List<String> firstNames = java.util.Arrays.stream(names).map(n -> n.split(" ")[0]).distinct().toList();
        List<String> placeNames = t.places().stream().filter(place -> place.kind() == PlaceKind.CAFE || place.kind() == PlaceKind.SHOP)
                .map(Place::name).distinct().toList();

        LocalTime earliest = t.wakeTime();
        LocalTime latest = LocalTime.of(23, 30);
        long days = Math.max(1, Duration.between(t.phoneStart(), t.now()).toDays());
        int callCount = (int) Math.min(1500, Math.max(60, days / 2));
        List<Call> calls = new ArrayList<>();
        for (int i = 0; i < callCount; i++) {
            int who = pickContact(r, favourite);
            int roll = r.nextInt(100);
            CallType type = roll < 40 ? CallType.INCOMING : roll < 80 ? CallType.OUTGOING : CallType.MISSED;
            int duration = type == CallType.MISSED ? 0 : (int) Math.min(3600, 5 + Math.abs(r.nextGaussian()) * 240);
            calls.add(new Call(numbers[who], Events.randomInstant(r, t.phoneStart(), t.now(), zone, earliest, latest), duration, type));
        }
        calls.sort(Comparator.comparing(Call::at));

        List<SmsThread> threads = new ArrayList<>();
        Map<String, Instant> lastSms = new HashMap<>();
        for (int i = 0; i < count; i++) {
            if (r.nextInt(100) < 30 && threads.size() < 60) {
                List<SmsMessage> msgs = new ArrayList<>();
                int n = 2 + r.nextInt(29);
                for (int k = 0; k < n; k++) {
                    msgs.add(new SmsMessage(Events.randomInstant(r, t.phoneStart(), t.now(), zone, earliest, latest),
                            r.nextBoolean(), SmsText.message(lang, r, firstNames, placeNames), true));
                }
                msgs.sort(Comparator.comparing(SmsMessage::at));
                threads.add(new SmsThread(numbers[i], names[i], null, msgs));
                lastSms.put(numbers[i], msgs.get(msgs.size() - 1).at());
            }
        }
        for (SenderKind kind : List.of(SenderKind.BANK, SenderKind.OPERATOR, SenderKind.PARCEL, SenderKind.SHOP)) {
            List<Sender> senders = c.senders(kind);
            if (!senders.isEmpty()) {
                Sender sender = senders.get(r.nextInt(senders.size()));
                threads.add(serviceThread(sender, kind, service, r, t, zone, earliest, latest, true));
            }
        }
        if (c.senders(SenderKind.BANK).isEmpty() && c.senders(SenderKind.OPERATOR).isEmpty() && !c.senders(SenderKind.GLOBAL).isEmpty()) {
            Sender g = c.senders(SenderKind.GLOBAL).get(r.nextInt(c.senders(SenderKind.GLOBAL).size()));
            threads.add(serviceThread(g, SenderKind.GLOBAL, service, r, t, zone, earliest, latest, true));
        }

        List<Voicemail> voicemails = new ArrayList<>();
        int vm = r.nextInt(5);
        for (int i = 0; i < vm; i++) {
            String from = r.nextInt(100) < 70 ? numbers[r.nextInt(count)] : freshNumber(c, r, used);
            voicemails.add(new Voicemail(from, Events.randomInstant(r, t.phoneStart(), t.now(), zone, earliest, latest), 5 + r.nextInt(116)));
        }
        voicemails.sort(Comparator.comparing(Voicemail::at));

        Map<String, Instant> lastCall = new HashMap<>();
        for (Call call : calls) {
            lastCall.merge(call.number(), call.at(), (a, b) -> a.isAfter(b) ? a : b);
        }
        List<Contact> contacts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Instant a = lastCall.get(numbers[i]);
            Instant b = lastSms.get(numbers[i]);
            Instant last = a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
            contacts.add(new Contact(ids[i], names[i], numbers[i], favourite[i], groups[i], birthdays[i], last, photos[i]));
        }
        return new People(own, contacts, calls, threads, voicemails);
    }

    private static SmsThread serviceThread(Sender sender, SenderKind kind, LanguagePack lang, RandomGenerator r, Timeline t,
            ZoneId zone, LocalTime earliest, LocalTime latest, boolean withCode) {
        List<SmsMessage> msgs = new ArrayList<>();
        int n = 3 + r.nextInt(10);
        boolean wantsCode = kind == SenderKind.BANK || kind == SenderKind.OPERATOR || kind == SenderKind.GLOBAL;
        int customer = 10_000_000 + r.nextInt(90_000_000); // one customer number per operator, as in life
        for (int k = 0; k < n; k++) {
            boolean code = wantsCode && withCode && (k == 0 || r.nextInt(100) < 50);
            msgs.add(new SmsMessage(Events.randomInstant(r, t.phoneStart(), t.now(), zone, earliest, latest), true,
                    serviceText(kind, lang, code, customer, r), true));
        }
        msgs.sort(Comparator.comparing(SmsMessage::at));
        return new SmsThread(sender.name(), sender.name(), kind, msgs);
    }

    private static String serviceText(SenderKind kind, LanguagePack lang, boolean code, int customer, RandomGenerator r) {
        int six = 100000 + r.nextInt(900000);
        if (code) {
            return lang.format("sms.service.code", Map.of("code", String.valueOf(six)));
        }
        // a transaction text carries its own reference: a thread of one sentence repeated is the same on every phone and on no real one
        int ref = 10_000_000 + r.nextInt(90_000_000);
        return lang.format("sms.service." + kind.name(), Map.of("ref", String.valueOf(ref), "customer", String.valueOf(customer)));
    }

    /** Favourites are called about six times as often as the others. */
    private static int pickContact(RandomGenerator r, boolean[] favourite) {
        for (int attempt = 0; attempt < 8; attempt++) {
            int i = r.nextInt(favourite.length);
            if (favourite[i] || r.nextInt(6) == 0) {
                return i;
            }
        }
        return r.nextInt(favourite.length);
    }

    private static String freshNumber(CountryPack c, RandomGenerator r, Set<String> used) throws NoVerifiedRange {
        for (;;) {
            String n = c.mobileNumber(r).e164();
            if (used.add(n)) {
                return n;
            }
        }
    }
}
