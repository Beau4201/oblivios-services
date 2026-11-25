// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.pool;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Sender;
import org.oblivios.decoy.content.country.SenderKind;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.persona.PersonaProfile.Fact;
import org.oblivios.decoy.content.persona.PersonaProfile.FriendGroup;
import org.oblivios.decoy.content.persona.PersonaProfile.Texting;
import org.oblivios.decoy.content.story.Place;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.story.Timeline;
import org.oblivios.decoy.content.style.StyleBook;
import org.oblivios.decoy.content.style.StyleVector;
import org.oblivios.decoy.content.surface.Contact;
import org.oblivios.decoy.content.surface.People;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.surface.SmsMessage;

/**
 * Picks the personal and group conversations of a plan from the pool and fills them. A conversation fits a contact
 * when its language, relation, ages, genders and registers (within one step of the style book) fit, when the facts it
 * presumes are the persona's, when every slot resolves from the plan, and when it has not been used on this device. It
 * is placed in the story with its gaps kept and its times moved into the waking hours of the timeline.
 */
public final class ConversationPicker {
    /** The first candidate and at most three replacements per conversation the judge rejects. */
    private static final int TRIES = 4;
    private static final int RESERVE = 8;
    private static final Pattern SLOT = Pattern.compile("\\{([A-Z_]+)}");
    private static final Set<FriendGroup> HOBBY = EnumSet.of(FriendGroup.MUSIC, FriendGroup.SPORT, FriendGroup.GAMING);
    private static final Set<Relation> FRIENDS = EnumSet.of(Relation.FRIEND, Relation.BEST_FRIEND, Relation.OLD_FRIEND);

    private ConversationPicker() {}

    /** Picks without rewriting or judging: every conversation is marked unrewritten, so no device takes the plan. */
    public static Picked pick(Persona p, People people, StyleBook styles, Timeline t, CountryPack c, List<PoolConversation> pool, Seed s) {
        return pick(p, people, styles, t, c, pool, s, null, null);
    }

    public static Picked pick(Persona p, People people, StyleBook styles, Timeline t, CountryPack c, List<PoolConversation> pool, Seed s,
            Rewriter rewriter, Judge judge) {
        return new Run(p, people, styles, t, c, s, rewriter, judge).pick(pool);
    }

    /** How many conversations a contact of this relation gets, before the texting habit and chance. */
    private static int weight(Relation r) {
        return switch (r) {
            case PARTNER -> 8;
            case BEST_FRIEND -> 5;
            case DATE -> 3;
            case FRIEND, PARENT, SIBLING, CHILD, HOUSEMATE -> 2;
            case ACQUAINTANCE -> 0;
            default -> 1;
        };
    }

    private static final class Run {
        final Persona p;
        final People people;
        final StyleBook styles;
        final Timeline t;
        final CountryPack pack;
        final Seed seed;
        final Rewriter rewriter;
        final Judge judge;
        final LanguagePack lang;
        final Set<Fact> facts;
        final Set<String> interests = new HashSet<>();
        final Set<String> used = new HashSet<>();
        final List<String> warnings = new ArrayList<>();
        final List<Place> cafes;
        final List<Place> shops;
        final List<Sender> supermarkets;

        Run(Persona p, People people, StyleBook styles, Timeline t, CountryPack pack, Seed seed, Rewriter rewriter, Judge judge) {
            this.p = p;
            this.people = people;
            this.styles = styles;
            this.t = t;
            this.pack = pack;
            this.seed = seed;
            this.rewriter = rewriter;
            this.judge = judge;
            this.lang = LanguagePack.of(p.language());
            this.facts = p.facts();
            p.interests().forEach(i -> interests.add(i.toLowerCase(Locale.ROOT)));
            this.cafes = t.places().stream().filter(x -> x.kind() == PlaceKind.CAFE).toList();
            this.shops = t.places().stream().filter(x -> x.kind() == PlaceKind.SHOP).toList();
            this.supermarkets = pack.senders(SenderKind.SHOP);
        }

        Picked pick(List<PoolConversation> pool) {
            Map<Relation, List<PoolConversation>> oneToOne = new EnumMap<>(Relation.class);
            List<PoolConversation> groupPool = new ArrayList<>();
            for (PoolConversation c : pool) {
                if (!usable(c)) {
                    continue;
                }
                if (c.isGroup()) {
                    groupPool.add(c);
                } else {
                    oneToOne.computeIfAbsent(c.sides().get(1).relation(), k -> new ArrayList<>()).add(c);
                }
            }

            Map<String, List<FilledConversation>> byContact = new LinkedHashMap<>();
            for (Contact contact : people.contacts()) {
                RandomGenerator r = seed.stream("pick." + contact.id());
                int wanted = wanted(contact.relation(), r);
                if (wanted == 0) {
                    continue;
                }
                List<PoolConversation> candidates = ordered(fitting(oneToOne, contact), r);
                List<FilledConversation> got = new ArrayList<>();
                int next = 0;
                while (got.size() < wanted && next < candidates.size()) {
                    for (int tries = 0; tries < TRIES && next < candidates.size(); ) {
                        PoolConversation cand = candidates.get(next++);
                        if (used.contains(cand.id())) {
                            continue;
                        }
                        tries++;
                        used.add(cand.id());
                        FilledConversation f = accept(cand, List.of(contact), styles.personaTowards().get(contact.relation()));
                        if (f != null) {
                            got.add(f);
                            break;
                        }
                    }
                }
                if (got.size() < wanted) {
                    warnings.add("contact " + contact.id() + " (" + contact.relation() + "): " + got.size() + " of " + wanted
                            + " conversations, the pool holds no more that fit");
                }
                if (!got.isEmpty()) {
                    byContact.put(contact.id(), got);
                }
            }

            List<FilledConversation> groups = groups(groupPool);

            List<FilledConversation> reserve = new ArrayList<>();
            for (Contact contact : people.contacts()) {
                if (reserve.size() >= RESERVE) {
                    break;
                }
                if (weight(contact.relation()) < 2) {
                    continue;
                }
                for (PoolConversation cand : ordered(fitting(oneToOne, contact), seed.stream("reserve." + contact.id()))) {
                    if (used.contains(cand.id())) {
                        continue;
                    }
                    used.add(cand.id());
                    FilledConversation f = accept(cand, List.of(contact), styles.personaTowards().get(contact.relation()));
                    if (f != null) {
                        reserve.add(f);
                    }
                    break;
                }
            }
            return new Picked(byContact, groups, reserve, warnings);
        }

        int wanted(Relation rel, RandomGenerator r) {
            double mult = p.texting() == Texting.LOW ? 0.5 : p.texting() == Texting.HIGH ? 1.6 : 1.0;
            int w = weight(rel);
            if (w == 0) {
                return r.nextInt(100) < 3 ? 1 : 0;
            }
            return (int) Math.round(w * mult * (0.5 + r.nextDouble()));
        }

        /** Labels that do not depend on the contact: language, facts, voice, and slots the plan can fill. */
        boolean usable(PoolConversation c) {
            if (!c.language().equals(p.language()) || !(c.mix().isEmpty() || c.mix().equals(p.mixLanguage()))) {
                return false;
            }
            if (!facts.containsAll(c.requires())) {
                return false;
            }
            boolean noVoice = styles.persona().voiceRate() == 0;
            for (PoolMessage m : c.messages()) {
                if (noVoice && m.side() == 'A' && m.text().contains("[voice")) {
                    return false;
                }
                Matcher x = SLOT.matcher(m.text());
                while (x.find()) {
                    if (!resolvable(x.group(1), c.sides().size())) {
                        return false;
                    }
                }
            }
            return placeable(c);
        }

        boolean resolvable(String slot, int sides) {
            Slot s;
            try {
                s = Slot.valueOf(slot);
            } catch (IllegalArgumentException e) {
                return false;
            }
            return switch (s) {
                case NAME_A -> true;
                case NAME_B, NAME_C, NAME_D, NAME_E, NAME_F, NAME_G, NAME_H -> s.ordinal() - Slot.NAME_A.ordinal() < sides;
                case NAME_OTHER -> people.contacts().size() > sides;
                case PLACE_CAFE -> !cafes.isEmpty();
                case PLACE_SHOP -> !shops.isEmpty();
                case SUPERMARKET -> !supermarkets.isEmpty();
                case CITY -> p.inputs().city() != null && !p.inputs().city().isBlank();
                // shortcut: no club data exists yet, so a conversation that names a club is never picked; add a source to the pack to use them
                case CLUB -> false;
                case DAY, TIME, AMOUNT -> true;
            };
        }

        boolean placeable(PoolConversation c) {
            return !lastStart(c).isBefore(firstStart());
        }

        LocalDate firstStart() {
            return LocalDate.ofInstant(t.phoneStart(), t.zone()).plusDays(1);
        }

        LocalDate lastStart(PoolConversation c) {
            int lastDay = c.messages().stream().mapToInt(PoolMessage::day).max().orElse(0);
            return LocalDate.ofInstant(t.now(), t.zone()).minusDays(1L + lastDay);
        }

        List<PoolConversation> fitting(Map<Relation, List<PoolConversation>> index, Contact contact) {
            List<PoolConversation> out = new ArrayList<>();
            int age = p.year() - contact.birthYear();
            StyleVector towards = styles.personaTowards().get(contact.relation());
            StyleVector theirs = styles.contacts().get(contact.id());
            for (PoolConversation c : index.getOrDefault(contact.relation(), List.of())) {
                PoolSide a = c.sides().get(0);
                PoolSide b = c.sides().get(1);
                if (fitsPersona(a, towards) && fitsContact(b, contact, age, theirs)) {
                    out.add(c);
                }
            }
            return out;
        }

        boolean fitsPersona(PoolSide a, StyleVector style) {
            return a.age().contains(p.age()) && a.gender() == p.gender() && near(a.register(), style.register());
        }

        boolean fitsContact(PoolSide side, Contact c, int age, StyleVector style) {
            return side.relation() == c.relation() && side.age().contains(age) && side.gender() == c.gender()
                    && near(side.register(), style.register());
        }

        static boolean near(StyleVector.Register a, StyleVector.Register b) {
            return Math.abs(a.ordinal() - b.ordinal()) <= 1;
        }

        /** The candidates in a weighted random order: a topic among the persona's interests weighs three times. */
        List<PoolConversation> ordered(List<PoolConversation> candidates, RandomGenerator r) {
            Map<PoolConversation, Double> key = new HashMap<>();
            List<PoolConversation> out = new ArrayList<>(candidates);
            for (PoolConversation c : candidates) {
                boolean liked = c.topics().stream().anyMatch(x -> interests.contains(x.toLowerCase(Locale.ROOT)));
                key.put(c, Math.pow(r.nextDouble(), 1.0 / (liked ? 3.0 : 1.0)));
            }
            out.sort(Comparator.comparing((PoolConversation c) -> key.get(c)).reversed().thenComparing(PoolConversation::id));
            return out;
        }

        /** Fills, rewrites and judges a conversation; null when the judge rejects it. */
        FilledConversation accept(PoolConversation cand, List<Contact> members, StyleVector owner) {
            FilledConversation f = fill(cand, members);
            if (rewriter != null) {
                List<StyleVector> others = members.stream().map(m -> styles.contacts().get(m.id())).toList();
                f = rewriter.rewrite(f, owner, others, p.language());
            }
            if (judge != null && !judge.judge(f, p, members).ok()) {
                return null;
            }
            return f;
        }

        FilledConversation fill(PoolConversation c, List<Contact> members) {
            RandomGenerator r = seed.stream("fill." + c.id());
            ZoneId zone = t.zone();
            LocalDate first = firstStart();
            long span = ChronoUnit.DAYS.between(first, lastStart(c));
            LocalDate start = first.plusDays(r.nextLong(span + 1));
            int wake = t.wakeTime().toSecondOfDay() / 60;
            int sleep = t.sleepTime().toSecondOfDay() / 60;
            if (sleep <= wake) {
                sleep = 1439; // a bedtime after midnight: keep the conversation inside the day
            }
            int minM = c.messages().stream().mapToInt(PoolMessage::minute).min().orElse(0);
            int maxM = c.messages().stream().mapToInt(PoolMessage::minute).max().orElse(0);
            int lo = wake - minM;
            int hi = sleep - maxM;
            int shift = lo <= hi ? lo + r.nextInt(hi - lo + 1) : lo;

            String own = p.inputs().ownerName().trim().split("\\s+")[0];
            Set<String> memberIds = new HashSet<>();
            members.forEach(m -> memberIds.add(m.id()));
            List<Contact> outside = people.contacts().stream().filter(x -> !memberIds.contains(x.id())).toList();
            String other = outside.isEmpty() ? "" : firstName(outside.get(r.nextInt(outside.size())));
            String cafe = cafes.isEmpty() ? "" : cafes.get(r.nextInt(cafes.size())).name();
            String shop = shops.isEmpty() ? "" : shops.get(r.nextInt(shops.size())).name();
            String market = supermarkets.isEmpty() ? "" : supermarkets.get(r.nextInt(supermarkets.size())).name();

            List<SmsMessage> out = new ArrayList<>();
            for (PoolMessage m : c.messages()) {
                int minute = Math.min(1439, m.minute() + shift);
                LocalDate day = start.plusDays(m.day());
                var at = day.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant();
                StringBuilder sb = new StringBuilder();
                Matcher x = SLOT.matcher(m.text());
                while (x.find()) {
                    String value = switch (Slot.valueOf(x.group(1))) {
                        case NAME_A -> own;
                        case NAME_B, NAME_C, NAME_D, NAME_E, NAME_F, NAME_G, NAME_H ->
                                firstName(members.get(Slot.valueOf(x.group(1)).ordinal() - Slot.NAME_B.ordinal()));
                        case NAME_OTHER -> other;
                        case PLACE_CAFE -> cafe;
                        case PLACE_SHOP -> shop;
                        case SUPERMARKET -> market;
                        case CITY -> p.inputs().city().trim();
                        case DAY -> lang.weekday(day.plusDays(1 + r.nextInt(6)).getDayOfWeek());
                        case TIME -> String.format(Locale.ROOT, "%02d:%02d", 7 + r.nextInt(16), 5 * r.nextInt(12));
                        case AMOUNT -> pack.amount(5 + r.nextInt(196));
                        case CLUB -> throw new IllegalStateException("a club slot is never picked");
                    };
                    x.appendReplacement(sb, Matcher.quoteReplacement(value));
                }
                x.appendTail(sb);
                boolean incoming = m.side() != 'A';
                String from = c.isGroup() && incoming ? members.get(m.side() - 'B').id() : null;
                out.add(new SmsMessage(at, incoming, sb.toString(), true, from));
            }
            List<String> ids = members.stream().map(Contact::id).toList();
            return new FilledConversation(c.id(), c.situation(), c.group(), ids, c.requires(), out, false);
        }

        static String firstName(Contact c) {
            return c.name().trim().split("\\s+")[0];
        }

        List<FilledConversation> groups(List<PoolConversation> pool) {
            RandomGenerator r = seed.stream("groups");
            int[] range = pack.mmsGroups();
            int count = range[0] + r.nextInt(range[1] - range[0] + 1);
            List<FilledConversation> out = new ArrayList<>();
            int tries = 0;
            for (PoolConversation cand : ordered(pool, r)) {
                if (out.size() >= count) {
                    break;
                }
                if (used.contains(cand.id()) || !fitsPersona(cand.sides().get(0), styles.persona())) {
                    continue;
                }
                List<Contact> members = assign(cand, r);
                if (members == null) {
                    continue;
                }
                used.add(cand.id());
                FilledConversation f = accept(cand, members, styles.persona());
                if (f != null) {
                    out.add(f);
                    tries = 0;
                } else if (++tries >= TRIES) {
                    count--; // the slot is lost after the first candidate and three replacements
                    tries = 0;
                }
            }
            return out;
        }

        /** A member of the circle for every side, of a kind the group allows; null when a side has none. */
        List<Contact> assign(PoolConversation g, RandomGenerator r) {
            List<Contact> allowed = new ArrayList<>();
            for (Contact c : people.contacts()) {
                if (allowed(g.group(), c)) {
                    allowed.add(c);
                }
            }
            boolean oneOrigin = g.group() == GroupKind.FRIENDS || g.group() == GroupKind.OLD_FRIENDS;
            if (!oneOrigin) {
                return assign(g, allowed);
            }
            for (FriendGroup origin : new TreeSet<>(p.friendGroups())) {
                List<Contact> same = allowed.stream().filter(c -> c.origin() == origin).toList();
                List<Contact> got = assign(g, same);
                if (got != null) {
                    return got;
                }
            }
            return null;
        }

        List<Contact> assign(PoolConversation g, List<Contact> allowed) {
            List<Contact> members = new ArrayList<>();
            Set<String> taken = new HashSet<>();
            for (int i = 1; i < g.sides().size(); i++) {
                PoolSide side = g.sides().get(i);
                Contact found = null;
                for (Contact c : allowed) {
                    if (!taken.contains(c.id()) && fitsContact(side, c, p.year() - c.birthYear(), styles.contacts().get(c.id()))) {
                        found = c;
                        break;
                    }
                }
                if (found == null) {
                    return null;
                }
                taken.add(found.id());
                members.add(found);
            }
            return members;
        }

        boolean allowed(GroupKind kind, Contact c) {
            Relation r = c.relation();
            return switch (kind) {
                case HOUSEHOLD -> r == Relation.PARTNER || r == Relation.CHILD || r == Relation.HOUSEMATE;
                case FAMILY -> r.groupIndex() == 0;
                case SIBLINGS -> r == Relation.SIBLING;
                case FRIENDS -> r == Relation.FRIEND || r == Relation.BEST_FRIEND;
                case OLD_FRIENDS -> FRIENDS.contains(r);
                case HOUSE_SHARE -> r == Relation.HOUSEMATE;
                case WORK_TEAM -> r == Relation.COLLEAGUE || r == Relation.MANAGER;
                case SHIFT_ROSTER -> r == Relation.COLLEAGUE;
                case STUDY_PROJECT, CLASS -> r == Relation.CLASSMATE;
                case SCHOOL_PARENTS -> r == Relation.PARENT_OF_FRIEND;
                case SPORT_TEAM -> r == Relation.TEAMMATE;
                case HOBBY_CLUB, GAMING -> FRIENDS.contains(r) && HOBBY.contains(c.origin());
                case NEIGHBOURHOOD -> r == Relation.NEIGHBOUR;
                case TRIP, EVENT, MOVING, FUNERAL -> FRIENDS.contains(r) || r.groupIndex() == 0;
            };
        }
    }
}
