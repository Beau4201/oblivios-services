// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.world;

import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Draws;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.PhoneNumber;
import org.oblivios.decoy.content.data.CityRef;
import org.oblivios.decoy.content.data.DataSet;
import org.oblivios.decoy.content.data.FamilyRules;
import org.oblivios.decoy.content.data.NameData;
import org.oblivios.decoy.content.data.PhoneNumbers;
import org.oblivios.decoy.content.data.PlaceData;
import org.oblivios.decoy.content.data.Spreads;
import org.oblivios.decoy.content.data.Venue;
import org.oblivios.decoy.content.lang.LanguagePack;
import org.oblivios.decoy.content.origin.Origin;
import org.oblivios.decoy.content.persona.ActivityAnswer;
import org.oblivios.decoy.content.persona.ChildAnswer;
import org.oblivios.decoy.content.persona.ChildAnswer.Custody;
import org.oblivios.decoy.content.persona.ParentAnswer;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.persona.PersonaProfile.Housing;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.persona.PersonaProfile.Relationship;
import org.oblivios.decoy.content.persona.PersonaProfile.Transport;
import org.oblivios.decoy.content.persona.PetAnswer;
import org.oblivios.decoy.content.persona.Question;
import org.oblivios.decoy.content.story.PlaceKind;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.world.Habits.LabelHabit;

/**
 * Builds the world of a persona: the people with their names by family and background, the places of its life, its
 * standing choices and its habits. Every item draws from a stream named after it.
 */
public final class WorldBuilder {
    private static final String SELF = "self";
    /** The id of the place where the persona lives; the first place of every world. */
    public static final String HOME = "home";
    private static final String WORK = "work";
    private static final String FAMILY = "family";
    /** From this age a person has a phone of its own, [assumption]. */
    private static final int PHONE_AGE = 10;
    /** A parent is at least this much older than a child, as in-law ages are read, [assumption]. */
    private static final int GENERATION = 20;
    private static final int NAME_TRIES = 200;
    private static final int NEAREST = 5;
    /** Who is closer, [assumption]; a relation not listed keeps the order of the circle behind these. */
    private static final List<Relation> CLOSEST = List.of(Relation.PARTNER, Relation.CHILD, Relation.PARENT,
            Relation.BEST_FRIEND, Relation.SIBLING, Relation.HOUSEMATE, Relation.STEP_PARENT, Relation.GRANDPARENT,
            Relation.DATE, Relation.FRIEND);
    private static final List<Relation> FARTHEST = List.of(Relation.EX_PARTNER, Relation.MARKETPLACE, Relation.ACQUAINTANCE);

    /** A person while the world is built. */
    private static final class Draft {
        final Circle.Member member;
        final String id;
        final LocalDate birthDate;
        final List<String> parentIds = new ArrayList<>();
        String background;
        String family = "";
        String used = "";
        String given = "";
        String partnerId;
        boolean atHome;

        Draft(Circle.Member member, String id, LocalDate birthDate) {
            this.member = member;
            this.id = id;
            this.birthDate = birthDate;
        }

        Relation relation() {
            return member.relation();
        }

        Gender gender() {
            return member.gender();
        }
    }

    private final Persona p;
    private final CountryPack pack;
    private final DataSet data;
    private final Draws draws;
    private final CityRef city;

    private WorldBuilder(Persona p, CountryPack pack, DataSet data, Draws draws) {
        this.p = p;
        this.pack = pack;
        this.data = data;
        this.draws = draws;
        this.city = PlaceData.require(pack.code(), p.inputs().city());
    }

    /** Throws {@link IllegalArgumentException} for a country without number, name or place data. */
    public static World build(Persona p, CountryPack pack, DataSet data, Draws draws) {
        WorldBuilder builder = new WorldBuilder(p, pack, data, draws); // refuses the city
        return builder.build(data.numbers());
    }

    private World build(PhoneNumbers numbers) {
        List<WorldPlace> places = places();
        List<Draft> drafts = new ArrayList<>();
        List<Circle.Member> circle = Circle.build(p, draws);
        for (Circle.Member m : circle) {
            drafts.add(draft(m, "p" + drafts.size()));
        }
        household(drafts);
        Draft self = new Draft(new Circle.Member(Relation.SELF, p.birthYear(), p.gender(), null, null, "", ""), SELF,
                p.birthDate());
        self.family = p.inputs().familyName().trim();
        self.used = self.family;
        self.given = p.inputs().givenName().trim();
        self.background = p.background();
        self.atHome = true;
        names(self, drafts);

        drafts.sort(Comparator.comparingInt(WorldBuilder::tier));
        LanguagePack lang = LanguagePack.of(p.language());
        Habits habits = habits();
        List<WorldPerson> people = new ArrayList<>();
        Set<String> phones = new HashSet<>();
        for (Draft d : drafts) {
            people.add(person(d, people.size(), self, lang, numbers, phones, places));
        }
        label(people, habits, lang);
        String work = has(places, WORK) ? WORK : null;
        WorldPerson me = new WorldPerson(SELF, Relation.SELF, self.given, self.family, self.used, p.gender(), p.birthDate(),
                p.background(), self.parentIds, self.partnerId, true, HOME, null, "", self.given, "", 0, null, work,
                answer(Question.GIVEN_NAME));
        return new World(me, people, pets(), places, choices(), habits);
    }

    private static Origin answer(Question q) {
        return Origin.answer(q.name());
    }

    private static boolean has(List<WorldPlace> places, String id) {
        return places.stream().anyMatch(x -> x.id().equals(id));
    }

    private static int tier(Draft d) {
        int near = CLOSEST.indexOf(d.relation());
        if (near >= 0) {
            return near;
        }
        int far = FARTHEST.indexOf(d.relation());
        return CLOSEST.size() + (far < 0 ? 0 : 1 + far);
    }

    // ---- people ----------------------------------------------------------------------------------------

    /** A birth date that gives the answered age today, or a day of the birth year that is not after today. */
    private Draft draft(Circle.Member m, String id) {
        RandomGenerator r = draws.stream("world.person." + id + ".birth");
        LocalDate first = m.age() != null ? p.today().minusYears(m.age() + 1L).plusDays(1) : LocalDate.of(m.birthYear(), 1, 1);
        LocalDate last = m.age() != null ? p.today().minusYears(m.age()) : LocalDate.of(m.birthYear(), 12, 31);
        if (last.isAfter(p.today())) {
            last = p.today();
        }
        return new Draft(m, id, first.plusDays(r.nextLong(ChronoUnit.DAYS.between(first, last) + 1)));
    }

    private int age(LocalDate birthDate) {
        return Period.between(birthDate, p.today()).getYears();
    }

    /**
     * Marks the household less the persona as living at home, in the order of who surely shares a home; a household
     * larger than the people known gets more of them, and a housemate without a place in it is none.
     */
    private void household(List<Draft> drafts) {
        List<Draft> children = drafts.stream().filter(d -> d.relation() == Relation.CHILD).toList();
        List<Draft> sharing = new ArrayList<>();
        if (p.partnerAtHome()) {
            drafts.stream().filter(d -> d.relation() == Relation.PARTNER).forEach(sharing::add);
        }
        List<Draft> older = new ArrayList<>();
        for (int i = 0; i < children.size(); i++) {
            ChildAnswer answer = p.children().get(i); // the circle holds the children in the order of the answers
            if (answer.custody() != Custody.ELSEWHERE) {
                (answer.age() < 18 ? sharing : older).add(children.get(i));
            }
        }
        if (p.housing() == Housing.WITH_PARENTS) {
            drafts.stream().filter(d -> d.relation() == Relation.PARENT).forEach(sharing::add);
            drafts.stream().filter(d -> d.relation() == Relation.SIBLING).forEach(sharing::add);
        }
        sharing.addAll(older);
        if (p.housing() == Housing.HOUSE_SHARE) {
            // a shared house holds a housemate, whoever else lives there: one comes right after the partner
            List<Draft> mates = of(drafts, Relation.HOUSEMATE);
            if (!mates.isEmpty()) {
                sharing.add(p.partnerAtHome() && !sharing.isEmpty() ? 1 : 0, mates.get(0));
                sharing.addAll(mates.subList(1, mates.size()));
            }
        }
        int wanted = p.household() - 1;
        for (int i = 0; i < Math.min(wanted, sharing.size()); i++) {
            sharing.get(i).atHome = true;
        }
        drafts.removeIf(d -> d.relation() == Relation.HOUSEMATE && !d.atHome);
        Relation more = p.housing() == Housing.WITH_PARENTS ? Relation.RELATIVE : Relation.HOUSEMATE;
        for (int i = (int) drafts.stream().filter(d -> d.atHome).count(); i < wanted; i++) {
            RandomGenerator r = draws.stream("world.person.home" + i);
            int years = Math.max(16, p.age() + r.nextInt(11) - 5);
            Draft added = draft(new Circle.Member(more, p.today().getYear() - years, r.nextBoolean() ? Gender.FEMALE : Gender.MALE,
                    null, null, "", ""), "home" + i);
            added.atHome = true;
            drafts.add(added);
        }
    }

    /** Family names and backgrounds in the order in which one decides another, then every given name. */
    private void names(Draft self, List<Draft> drafts) {
        FamilyRules rules = data.family();
        Set<String> taken = new HashSet<>();
        taken.add(key(self));
        List<Draft> ordered = new ArrayList<>();

        // the first father of the answers, living or not, gave the persona its name; without one, the first parent
        Gender giver = p.parents().stream().map(ParentAnswer::gender).anyMatch(g -> g == Gender.MALE) ? Gender.MALE
                : p.parents().isEmpty() ? Gender.FEMALE : p.parents().get(0).gender();
        List<Draft> parents = of(drafts, Relation.PARENT);
        Draft named = null;
        for (Draft parent : parents) {
            parent.background = p.background();
            if (named == null && parent.gender() == giver) {
                named = parent;
                parent.family = self.family;
                parent.used = self.family;
            } else {
                parent.family = otherFamily(parent, self.family);
                parent.used = rules.usedFamily(parent.family, self.family, p.parentsTogether(), stream(parent, "used"));
            }
            self.parentIds.add(parent.id);
            given(parent, taken);
            ordered.add(parent);
        }
        grandparents(drafts, parents, taken, ordered);

        Draft partner = of(drafts, Relation.PARTNER).stream().findFirst().orElse(null);
        if (partner != null) {
            partner.background = population(partner);
            partner.family = otherFamily(partner, self.family);
            partner.used = rules.usedFamily(partner.family, self.family, p.relationship() == Relationship.MARRIED,
                    stream(partner, "used"));
            self.partnerId = partner.id;
            partner.partnerId = SELF;
            given(partner, taken);
            ordered.add(partner);
        }
        for (Draft sibling : of(drafts, Relation.SIBLING)) {
            sibling.background = p.background();
            sibling.family = self.family;
            sibling.used = self.family;
            sibling.parentIds.addAll(self.parentIds);
            given(sibling, taken);
            ordered.add(sibling);
        }
        // a partner who shares the home or is married is read as the other parent of the children, [assumption]
        Draft other = partner != null && (p.partnerAtHome() || p.relationship() == Relationship.MARRIED) ? partner : null;
        for (Draft child : of(drafts, Relation.CHILD)) {
            child.background = p.background();
            child.parentIds.add(SELF);
            if (other == null) {
                child.family = self.family;
            } else {
                boolean selfIsFather = self.gender() == Gender.MALE || other.gender() == Gender.FEMALE;
                child.parentIds.add(other.id);
                child.family = rules.childFamily(selfIsFather ? self.family : other.family,
                        selfIsFather ? other.family : self.family, draws.stream("world.family.children"));
            }
            child.used = child.family;
            given(child, taken);
            ordered.add(child);
        }
        inLaws(drafts, partner, taken, ordered);
        for (Draft d : drafts) {
            if (!ordered.contains(d)) {
                d.background = d.relation() == Relation.RELATIVE ? p.background() : population(d);
                d.family = data.names(d.background).family(d.gender(), stream(d, "family"));
                d.used = d.family;
                given(d, taken);
            }
        }
    }

    private void grandparents(List<Draft> drafts, List<Draft> parents, Set<String> taken, List<Draft> ordered) {
        Set<String> filled = new HashSet<>();
        int next = 0;
        for (Draft grand : of(drafts, Relation.GRANDPARENT)) {
            grand.background = p.background();
            Draft child = null;
            for (int i = 0; i < parents.size() && child == null; i++) {
                Draft candidate = parents.get((next + i) % parents.size());
                if (filled.add(candidate.id + grand.gender())) {
                    child = candidate;
                }
            }
            next++;
            if (child == null) {
                grand.family = otherFamily(grand, "");
                grand.used = grand.family;
            } else {
                child.parentIds.add(grand.id);
                inherit(grand, child.family);
            }
            given(grand, taken);
            ordered.add(grand);
        }
    }

    /** A parent of someone: a father bears the family name, a mother her own and uses by the married rules. */
    private void inherit(Draft parent, String family) {
        if (parent.gender() == Gender.MALE) {
            parent.family = family;
            parent.used = family;
        } else {
            parent.family = otherFamily(parent, family);
            parent.used = data.family().usedFamily(parent.family, family, true, stream(parent, "used"));
        }
    }

    /** The partner's people: one a generation older is a parent, one of the partner's own age a brother or sister. */
    private void inLaws(List<Draft> drafts, Draft partner, Set<String> taken, List<Draft> ordered) {
        Set<Gender> parentsOfPartner = new HashSet<>();
        for (Draft d : of(drafts, Relation.IN_LAW)) {
            d.background = partner == null ? population(d) : partner.background;
            int older = partner == null ? 0 : age(d.birthDate) - age(partner.birthDate);
            if (partner != null && older >= GENERATION && parentsOfPartner.add(d.gender())) {
                partner.parentIds.add(d.id);
                inherit(d, partner.family);
            } else {
                d.family = partner != null && Math.abs(older) < GENERATION ? partner.family : otherFamily(d, "");
                d.used = d.family;
            }
            given(d, taken);
            ordered.add(d);
        }
    }

    private static List<Draft> of(List<Draft> drafts, Relation relation) {
        return drafts.stream().filter(d -> d.relation() == relation).toList();
    }

    private RandomGenerator stream(Draft d, String what) {
        return draws.stream("world.person." + d.id + "." + what);
    }

    private String population(Draft d) {
        return data.population().background(city.municipality(), age(d.birthDate), d.gender(), NameData.backgrounds(),
                stream(d, "background"));
    }

    /** A family name of the person's background other than {@code not}. */
    private String otherFamily(Draft d, String not) {
        NameData names = data.names(d.background);
        RandomGenerator r = stream(d, "family");
        for (int i = 0; i < NAME_TRIES; i++) {
            String family = names.family(d.gender(), r);
            if (!family.equalsIgnoreCase(not)) {
                return family;
            }
        }
        throw new IllegalStateException("names " + d.background + ": no family name beside one");
    }

    private static String key(Draft d) {
        return (d.given + " " + d.used).toLowerCase(Locale.ROOT);
    }

    /** The answered name, or one of the birth year that gives no second person of one full name. */
    private void given(Draft d, Set<String> taken) {
        if (!d.member.given().isBlank()) {
            d.given = d.member.given().trim();
            taken.add(key(d));
            return;
        }
        NameData names = data.names(d.background);
        RandomGenerator r = stream(d, "given");
        for (int i = 0; i < NAME_TRIES; i++) {
            d.given = names.given(d.gender(), d.birthDate.getYear(), r);
            if (taken.add(key(d))) {
                return;
            }
        }
        throw new IllegalStateException("names " + d.background + ": no free given name for " + d.gender() + " born "
                + d.birthDate.getYear());
    }

    private WorldPerson person(Draft d, int closeness, Draft self, LanguagePack lang, PhoneNumbers numbers, Set<String> phones,
            List<WorldPlace> places) {
        PhoneNumber phone = null;
        if (age(d.birthDate) >= PHONE_AGE) {
            RandomGenerator r = stream(d, "phone");
            do {
                phone = numbers.mobile(r);
            } while (!phones.add(phone.e164()));
        }
        String at = d.atHome ? HOME : d.relation() == Relation.PARENT && has(places, FAMILY) ? FAMILY : null;
        boolean colleague = d.relation() == Relation.COLLEAGUE || d.relation() == Relation.MANAGER
                || d.relation() == Relation.REPORT;
        WorldPerson bare = new WorldPerson(d.id, d.relation(), d.given, d.family, d.used, d.gender(), d.birthDate,
                d.background, d.parentIds, d.partnerId, d.atHome, at, phone, "", "", self.given, closeness, d.member.origin(),
                colleague && has(places, WORK) ? WORK : null, answer(question(d.relation())));
        return named(bare, "", Labels.addressedAs(bare, d.member.calledAs(), lang, stream(d, "addressed")));
    }

    private static WorldPerson named(WorldPerson w, String label, String addressedAs) {
        return new WorldPerson(w.id(), w.relation(), w.given(), w.family(), w.usedFamily(), w.gender(), w.birthDate(),
                w.background(), w.parentIds(), w.partnerId(), w.atHome(), w.placeId(), w.phone(), label, addressedAs,
                w.addressesPersonaAs(), w.closeness(), w.group(), w.workplaceId(), w.origin());
    }

    /** The question whose answer, given or drawn, puts a person of this relation in the world. */
    private static Question question(Relation r) {
        return switch (r) {
            case PARTNER, EX_PARTNER, DATE, IN_LAW -> Question.RELATIONSHIP;
            case PARENT, STEP_PARENT, GRANDPARENT -> Question.PARENTS;
            case SIBLING -> Question.SIBLINGS;
            case CHILD, CHILDMINDER, PARENT_OF_FRIEND -> Question.CHILDREN;
            case BEST_FRIEND, FRIEND, OLD_FRIEND, TEAMMATE -> Question.FRIEND_GROUPS;
            case HOUSEMATE, LANDLORD, TENANT -> Question.HOUSING;
            case COLLEAGUE, MANAGER, REPORT, CLIENT, CLASSMATE, TEACHER -> Question.JOB;
            case RELATIVE, NEIGHBOUR, TRADESPERSON, MARKETPLACE, ACQUAINTANCE -> Question.CIRCLE;
            case SELF -> Question.GIVEN_NAME;
        };
    }

    /** Saves everyone with a phone, the closest first; a name or word that two bear never stands alone. */
    private void label(List<WorldPerson> people, Habits habits, LanguagePack lang) {
        List<String> alone = new ArrayList<>();
        for (WorldPerson w : people) {
            if (w.phone() != null) {
                boolean word = Labels.elder(w.relation()) && !w.addressedAs().equals(w.given());
                alone.add((word ? w.addressedAs() : w.given()).split("\\s+")[0]);
            }
        }
        Set<String> taken = new HashSet<>();
        Labels.reserve(alone, taken);
        for (int i = 0; i < people.size(); i++) {
            WorldPerson w = people.get(i);
            if (w.phone() != null) {
                String label = Labels.label(w, p, habits.labels(), lang, taken,
                        draws.stream("world.person." + w.id() + ".label"));
                people.set(i, named(w, label, w.addressedAs()));
            }
        }
    }

    // ---- places, choices, pets, habits -------------------------------------------------------------------

    private List<WorldPlace> places() {
        PlaceData places = data.places();
        List<WorldPlace> out = new ArrayList<>();
        double[] home = places.point(city.municipality(), draws.stream("world.place.home"));
        out.add(point(HOME, PlaceKind.HOME, home, Question.CITY));
        boolean works = p.job() == JobKind.OFFICE || p.job() == JobKind.SHIFT || p.job() == JobKind.SELF_EMPLOYED;
        if (works) {
            // a work place is a point where people live too: no data gives where they work, [assumption]
            out.add(point(WORK, PlaceKind.WORK, places.point(city.municipality(), draws.stream("world.place.work")), Question.JOB));
        }
        // parents are read to live in the persona's municipality, [assumption]
        if (p.parentsAlive() && p.housing() != Housing.WITH_PARENTS) {
            out.add(point(FAMILY, PlaceKind.FAMILY_HOME,
                    places.point(city.municipality(), draws.stream("world.place.family")), Question.PARENTS));
        }
        boolean learns = p.job() == JobKind.PUPIL || p.job() == JobKind.STUDENT;
        if (learns || p.childAges().stream().anyMatch(a -> a >= 4 && a < 18)) {
            venues(out, home, Venue.VenueKind.SCHOOL, PlaceKind.SCHOOL, 1, learns ? Question.JOB : Question.CHILDREN);
        }
        if (p.childAges().stream().anyMatch(a -> a < 4)) {
            venues(out, home, Venue.VenueKind.CHILDCARE, PlaceKind.CARE, 1, Question.CHILDREN);
        }
        activities(out, home);
        venues(out, home, Venue.VenueKind.DOCTOR, PlaceKind.DOCTOR, 1, Question.CITY);
        venues(out, home, Venue.VenueKind.DENTIST, PlaceKind.DENTIST, 1, Question.CITY);
        add(out, near(home, Venue.VenueKind.SUPERMARKET, v -> hasChain(v.name(), p.supermarket()), 1), PlaceKind.SUPERMARKET,
                Question.SUPERMARKET);
        venues(out, home, Venue.VenueKind.CAFE, PlaceKind.CAFE, 2, Question.CITY);
        List<String> online = pack.onlineShops();
        add(out, near(home, Venue.VenueKind.SHOP, v -> online.stream().noneMatch(o -> hasChain(v.name(), o)), 3),
                PlaceKind.SHOP, Question.CITY);
        if (p.transport() == Transport.PUBLIC) {
            venues(out, home, Venue.VenueKind.STATION, PlaceKind.STATION, 1, Question.TRANSPORT);
        }
        return out;
    }

    private void activities(List<WorldPlace> out, double[] home) {
        Set<Venue.VenueKind> done = new HashSet<>();
        for (ActivityAnswer a : p.activities()) {
            Optional<Venue.VenueKind> kind = switch (a.kind()) {
                case TEAM_SPORT, GYM, SWIMMING -> Optional.of(Venue.VenueKind.SPORT);
                case MUSIC, CHOIR -> Optional.of(Venue.VenueKind.MUSIC);
                case FAITH -> Optional.of(Venue.VenueKind.FAITH);
                case RUNNING, CLASS, VOLUNTEERING, GAMING, CLUB -> Optional.empty();
            };
            if (kind.isPresent() && done.add(kind.get())) {
                venues(out, home, kind.get(), kind.get() == Venue.VenueKind.SPORT ? PlaceKind.SPORT : PlaceKind.VENUE, 1,
                        Question.ACTIVITIES);
            }
        }
    }

    private static boolean hasChain(String name, String chain) {
        return !chain.isEmpty() && name.toLowerCase(Locale.ROOT).matches(
                "(?s)(.*[^\\p{L}])?" + java.util.regex.Pattern.quote(chain.toLowerCase(Locale.ROOT)) + "([^\\p{L}].*)?");
    }

    private WorldPlace point(String id, PlaceKind kind, double[] at, Question q) {
        return new WorldPlace(id, kind, "", at[0], at[1], city.municipality(), answer(q));
    }

    private void venues(List<WorldPlace> out, double[] home, Venue.VenueKind kind, PlaceKind as, int count, Question q) {
        add(out, near(home, kind, v -> true, count), as, q);
    }

    private void add(List<WorldPlace> out, List<Venue> venues, PlaceKind as, Question q) {
        for (Venue v : venues) {
            if (out.stream().noneMatch(x -> x.id().equals(v.id()))) {
                out.add(new WorldPlace(v.id(), as, v.name(), v.lat(), v.lon(), city.municipality(), answer(q)));
            }
        }
    }

    /** Up to {@code count} of the fitting venues of the city, each one of the few nearest to home that are left. */
    private List<Venue> near(double[] home, Venue.VenueKind kind, java.util.function.Predicate<Venue> fits, int count) {
        List<Venue> left = new ArrayList<>(data.places().venues(city.municipality(), kind).stream().filter(fits)
                .sorted(Comparator.comparingDouble((Venue v) -> distance(home, v)).thenComparing(Venue::id)).toList());
        RandomGenerator r = draws.stream("world.place." + kind);
        List<Venue> out = new ArrayList<>();
        while (out.size() < count && !left.isEmpty()) {
            Venue v = left.remove(r.nextInt(Math.min(NEAREST, left.size())));
            // a second branch under one name is no second place to the persona
            if (out.stream().noneMatch(x -> x.name().equalsIgnoreCase(v.name()))) {
                out.add(v);
            }
        }
        return out;
    }

    private static double distance(double[] from, Venue v) {
        return Math.hypot(from[0] - v.lat(), (from[1] - v.lon()) * Math.cos(Math.toRadians(from[0])));
    }

    private StandingChoices choices() {
        RandomGenerator r = draws.stream("world.choices.homeSsid");
        List<String> patterns = pack.homeWifiPatterns();
        StringBuilder ssid = new StringBuilder();
        if (!patterns.isEmpty()) {
            for (char c : patterns.get(r.nextInt(patterns.size())).toCharArray()) {
                ssid.append(c == '#' ? Character.forDigit(r.nextInt(10), 10) : c);
            }
        }
        return new StandingChoices(p.bank(), p.operator(), p.supermarket(), "", "", ssid.toString(), "",
                answer(Question.BANK));
    }

    private List<WorldPet> pets() {
        List<WorldPet> out = new ArrayList<>();
        for (PetAnswer pet : p.pets()) {
            out.add(new WorldPet("pet" + out.size(), pet.kind(), pet.name(), answer(Question.PETS)));
        }
        return out;
    }

    private Habits habits() {
        Spreads s = data.spreads();
        LabelHabit labels = chance(s, "habit.labels.FULL") ? LabelHabit.FULL : LabelHabit.GIVEN;
        int roll = habit("habit.fontScale").nextInt(100);
        double small = s.value("habit.fontScale.small.percent");
        double large = small + s.value("habit.fontScale.large.percent");
        double largest = large + s.value("habit.fontScale.largest.percent");
        double fontScale = roll < small ? 0.85 : roll < large ? 1.15 : roll < largest ? 1.3 : 1.0;
        int fewest = (int) s.value("habit.favourites.low");
        int favourites = fewest + habit("habit.favourites").nextInt((int) s.value("habit.favourites.high") - fewest + 1);
        Activity activity = new Activity(drawn(s, "activity.calls"), drawn(s, "activity.texts"),
                drawn(s, "activity.photoBurst"), drawn(s, "activity.notes"), drawn(s, "activity.browsing"),
                drawn(s, "activity.callLength"));
        Tidiness tidiness = new Tidiness(drawn(s, "tidiness.unusedContacts"), drawn(s, "tidiness.unexplainedMessages"),
                drawn(s, "tidiness.fullTraceEvents"), drawn(s, "tidiness.offPhoneCauses"));
        return new Habits(labels, chance(s, "habit.savesInternational"), drawn(s, "habit.calendarShare"),
                drawn(s, "habit.photoShare"), chance(s, "habit.screenshots"), chance(s, "habit.paymentTexts"),
                drawn(s, "habit.callBackShare"), drawn(s, "habit.unreadShare"), chance(s, "habit.dnd"), fontScale,
                chance(s, "habit.renamesDevice"), false, favourites, chance(s, "habit.notes"), rhythm(s),
                drawn(s, "habit.rankExponent"), activity, tidiness, answer(Question.BIRTH_YEAR));
    }

    private RandomGenerator habit(String key) {
        return draws.stream("world." + key);
    }

    private boolean chance(Spreads s, String key) {
        return habit(key).nextInt(100) < s.value(key + ".percent");
    }

    private double drawn(Spreads s, String key) {
        return s.draw(key, habit(key));
    }

    /** The hours of real calls, moved by the persona's own hours and bent hour by hour. */
    private Rhythm rhythm(Spreads s) {
        List<Double> base = data.callShape().hourShares();
        double shift = drawn(s, "habit.rhythm.shiftHours");
        double spread = s.value("habit.rhythm.hourSpread");
        RandomGenerator r = habit("habit.rhythm.hours");
        double[] weights = new double[24];
        double sum = 0;
        for (int h = 0; h < 24; h++) {
            double from = h - shift;
            int low = (int) Math.floor(from);
            double part = from - low;
            double share = base.get(Math.floorMod(low, 24)) * (1 - part) + base.get(Math.floorMod(low + 1, 24)) * part;
            weights[h] = share * Math.exp(spread * r.nextGaussian());
            sum += weights[h];
        }
        List<Double> out = new ArrayList<>(24);
        for (double w : weights) {
            out.add(w / sum);
        }
        return new Rhythm(out);
    }
}
