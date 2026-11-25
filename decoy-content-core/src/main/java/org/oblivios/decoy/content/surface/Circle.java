// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.Seed;
import org.oblivios.decoy.content.persona.PersonaProfile.CircleSize;
import org.oblivios.decoy.content.persona.PersonaProfile.FriendGroup;
import org.oblivios.decoy.content.persona.PersonaProfile.Gender;
import org.oblivios.decoy.content.persona.PersonaProfile.Housing;
import org.oblivios.decoy.content.persona.PersonaProfile.JobKind;
import org.oblivios.decoy.content.persona.PersonaProfile.Relationship;

/**
 * The people around the persona, before names and numbers: each with a relation, a birth year and a gender, and a
 * friend with the group it comes from. Every relation draws from its own named stream, so a change to one kind never
 * moves another. The acquaintances fill the book up to its size (60 to 400, as before the circle existed).
 */
final class Circle {
    record Member(Relation relation, int birthYear, Gender gender, FriendGroup origin) {}

    private static final int MIN_TOTAL = 60;
    private static final int MAX_TOTAL = 400;

    private Circle() {}

    static List<Member> build(Persona p, Seed s) {
        int year = p.year();
        int age = p.age();
        List<Member> out = new ArrayList<>();
        boolean partner = p.relationship() == Relationship.PARTNER || p.relationship() == Relationship.MARRIED;
        boolean single = p.relationship() == Relationship.SINGLE || p.relationship() == Relationship.DIVORCED;
        boolean childUnder12 = p.childAges().stream().anyMatch(c -> c < 12);
        JobKind job = p.job();

        RandomGenerator r = s.stream("circle.PARTNER");
        int partnerAge = Math.max(16, age + r.nextInt(11) - 5);
        if (partner) {
            out.add(member(Relation.PARTNER, year - partnerAge, partnerGender(p.gender(), r), null));
        }
        r = s.stream("circle.EX_PARTNER");
        if (age >= 20 && (p.relationship() == Relationship.DIVORCED || r.nextInt(10) < 3)) {
            out.add(member(Relation.EX_PARTNER, year - Math.max(18, age + r.nextInt(11) - 5), partnerGender(p.gender(), r), null));
        }
        r = s.stream("circle.DATE");
        if (single && age < 50 && r.nextInt(100) < 40) {
            out.add(member(Relation.DATE, year - Math.max(18, age + r.nextInt(11) - 5), partnerGender(p.gender(), r), null));
        }

        // parents 25 to 35 years older and at most 100; grandparents 20 to 30 years older than the parents and at most 95
        r = s.stream("circle.PARENT");
        int parentsAge = -1;
        if (p.parentsAlive() && age + 25 <= 100) {
            int maxGap = Math.min(35, 100 - age);
            int[] gaps = {25 + r.nextInt(maxGap - 25 + 1), 25 + r.nextInt(maxGap - 25 + 1)};
            out.add(member(Relation.PARENT, year - (age + gaps[0]), Gender.FEMALE, null));
            out.add(member(Relation.PARENT, year - (age + gaps[1]), Gender.MALE, null));
            parentsAge = age + Math.min(gaps[0], gaps[1]);
        }
        r = s.stream("circle.STEP_PARENT");
        if (parentsAge > 0 && !p.parentsTogether() && r.nextInt(100) < 50) {
            out.add(member(Relation.STEP_PARENT, year - Math.min(100, parentsAge + r.nextInt(11) - 5), gender(r), null));
        }
        r = s.stream("circle.GRANDPARENT");
        if (parentsAge > 0 && age < 35) {
            int count = r.nextInt(3);
            for (int i = 0; i < count; i++) {
                int g = parentsAge + 20 + r.nextInt(11);
                if (g <= 95) {
                    out.add(member(Relation.GRANDPARENT, year - g, gender(r), null));
                }
            }
        }
        r = s.stream("circle.SIBLING");
        for (int sib : p.siblingAges()) {
            out.add(member(Relation.SIBLING, year - sib, gender(r), null));
        }
        r = s.stream("circle.CHILD");
        for (int c : p.childAges()) {
            if (c >= 10) { // a child has its own phone from 10
                out.add(member(Relation.CHILD, year - c, gender(r), null));
            }
        }
        r = s.stream("circle.IN_LAW");
        if (partner) {
            int count = 1 + r.nextInt(3);
            for (int i = 0; i < count; i++) {
                int inLaw = r.nextBoolean() ? Math.min(100, partnerAge + 25 + r.nextInt(11)) : Math.max(16, partnerAge + r.nextInt(17) - 8);
                out.add(member(Relation.IN_LAW, year - inLaw, gender(r), null));
            }
        }
        r = s.stream("circle.RELATIVE");
        int relatives = r.nextInt(5);
        for (int i = 0; i < relatives; i++) {
            out.add(member(Relation.RELATIVE, year - Math.max(10, Math.min(95, age + r.nextInt(41) - 10)), gender(r), null));
        }

        friends(p, s, out);

        r = s.stream("circle.HOUSEMATE");
        if (p.housing() == Housing.HOUSE_SHARE) {
            int count = Math.max(1, Math.min(4, p.household() - 1));
            for (int i = 0; i < count; i++) {
                out.add(member(Relation.HOUSEMATE, year - Math.max(16, age + r.nextInt(11) - 5), gender(r), null));
            }
        }
        r = s.stream("circle.NEIGHBOUR");
        int neighbours = r.nextInt(4);
        for (int i = 0; i < neighbours; i++) {
            out.add(member(Relation.NEIGHBOUR, year - (20 + r.nextInt(61)), gender(r), null));
        }

        boolean working = job == JobKind.OFFICE || job == JobKind.SHIFT || job == JobKind.SELF_EMPLOYED;
        r = s.stream("circle.COLLEAGUE");
        if (working) {
            int count = job == JobKind.SELF_EMPLOYED ? 1 + r.nextInt(3) : 3 + r.nextInt(13);
            for (int i = 0; i < count; i++) {
                out.add(member(Relation.COLLEAGUE, year - (20 + r.nextInt(46)), gender(r), null));
            }
        }
        r = s.stream("circle.MANAGER");
        if (job == JobKind.OFFICE || job == JobKind.SHIFT) {
            out.add(member(Relation.MANAGER, year - (28 + r.nextInt(35)), gender(r), null));
        }
        r = s.stream("circle.REPORT");
        if (job == JobKind.OFFICE && age >= 30) {
            int count = r.nextInt(4);
            for (int i = 0; i < count; i++) {
                out.add(member(Relation.REPORT, year - (20 + r.nextInt(41)), gender(r), null));
            }
        }
        r = s.stream("circle.CLIENT");
        if (job == JobKind.SELF_EMPLOYED) {
            int count = 2 + r.nextInt(7);
            for (int i = 0; i < count; i++) {
                out.add(member(Relation.CLIENT, year - (25 + r.nextInt(46)), gender(r), null));
            }
        }
        boolean learning = job == JobKind.PUPIL || job == JobKind.STUDENT;
        r = s.stream("circle.CLASSMATE");
        if (learning) {
            int count = 3 + r.nextInt(8);
            for (int i = 0; i < count; i++) {
                out.add(member(Relation.CLASSMATE, year - Math.max(12, age + r.nextInt(5) - 2), gender(r), null));
            }
        }
        r = s.stream("circle.TEACHER");
        if (learning) {
            int count = 1 + r.nextInt(2);
            for (int i = 0; i < count; i++) {
                out.add(member(Relation.TEACHER, year - (28 + r.nextInt(35)), gender(r), null));
            }
        }
        r = s.stream("circle.TEAMMATE");
        if (p.friendGroups().contains(FriendGroup.SPORT)) {
            int count = 2 + r.nextInt(5);
            for (int i = 0; i < count; i++) {
                out.add(member(Relation.TEAMMATE, year - Math.max(16, age + r.nextInt(17) - 8), gender(r), null));
            }
        }
        r = s.stream("circle.CHILDMINDER");
        if (childUnder12 && r.nextInt(100) < 50) {
            out.add(member(Relation.CHILDMINDER, year - (18 + r.nextInt(48)), Gender.FEMALE, null));
        }
        r = s.stream("circle.PARENT_OF_FRIEND");
        if (childUnder12) {
            int count = 1 + r.nextInt(4);
            for (int i = 0; i < count; i++) {
                out.add(member(Relation.PARENT_OF_FRIEND, year - Math.max(20, age + r.nextInt(13) - 6), gender(r), null));
            }
        }
        r = s.stream("circle.LANDLORD");
        if (p.housing() == Housing.RENT) {
            out.add(member(Relation.LANDLORD, year - (30 + r.nextInt(46)), gender(r), null));
        }
        r = s.stream("circle.TENANT");
        if (p.housing() == Housing.OWN && r.nextInt(10) == 0) {
            out.add(member(Relation.TENANT, year - (20 + r.nextInt(50)), gender(r), null));
        }
        r = s.stream("circle.TRADESPERSON");
        int trades = r.nextInt(3);
        for (int i = 0; i < trades; i++) {
            out.add(member(Relation.TRADESPERSON, year - (22 + r.nextInt(44)), Gender.values()[r.nextInt(2)], null));
        }
        r = s.stream("circle.MARKETPLACE");
        int market = r.nextInt(3);
        for (int i = 0; i < market; i++) {
            out.add(member(Relation.MARKETPLACE, year - (18 + r.nextInt(53)), gender(r), null));
        }

        r = s.stream("circle.ACQUAINTANCE");
        double scale = switch (p.circle()) {
            case SMALL -> 0.7;
            case MEDIUM -> 1.0;
            case LARGE -> 1.4;
        };
        int target = (int) Math.max(MIN_TOTAL, Math.min(MAX_TOTAL, (70 + Math.abs(r.nextGaussian()) * 90) * scale));
        while (out.size() < target) {
            out.add(member(Relation.ACQUAINTANCE, year - Math.max(16, Math.min(90, age + r.nextInt(31) - 15)), gender(r), null));
        }
        return out.size() > MAX_TOTAL ? List.copyOf(out.subList(0, MAX_TOTAL)) : List.copyOf(out);
    }

    /** Friends only from the chosen groups: within 2 years for school and study, within 8 for the others. */
    private static void friends(Persona p, Seed s, List<Member> out) {
        int year = p.year();
        int age = p.age();
        List<FriendGroup> groups = p.friendGroups().stream().sorted().toList();
        if (groups.isEmpty()) {
            return;
        }
        RandomGenerator r = s.stream("circle.FRIEND");
        int[] range = switch (p.circle()) {
            case SMALL -> new int[] {4, 8};
            case MEDIUM -> new int[] {8, 15};
            case LARGE -> new int[] {15, 30};
        };
        int count = range[0] + r.nextInt(range[1] - range[0] + 1);
        for (int i = 0; i < count; i++) {
            FriendGroup g = groups.get(r.nextInt(groups.size()));
            out.add(member(Relation.FRIEND, year - friendAge(age, g, r), gender(r), g));
        }
        RandomGenerator b = s.stream("circle.BEST_FRIEND");
        if (b.nextInt(100) < 80) {
            FriendGroup g = groups.get(b.nextInt(groups.size()));
            out.add(member(Relation.BEST_FRIEND, year - friendAge(age, g, b), gender(b), g));
        }
        RandomGenerator o = s.stream("circle.OLD_FRIEND");
        int old = p.circle() == CircleSize.SMALL ? o.nextInt(2) : o.nextInt(4);
        for (int i = 0; i < old; i++) {
            FriendGroup g = groups.get(o.nextInt(groups.size()));
            out.add(member(Relation.OLD_FRIEND, year - friendAge(age, g, o), gender(o), g));
        }
    }

    private static int friendAge(int age, FriendGroup g, RandomGenerator r) {
        int spread = g == FriendGroup.SCHOOL || g == FriendGroup.STUDY ? 2 : 8;
        return Math.max(12, age + r.nextInt(2 * spread + 1) - spread);
    }

    private static Member member(Relation rel, int birthYear, Gender g, FriendGroup origin) {
        return new Member(rel, birthYear, g, origin);
    }

    private static Gender gender(RandomGenerator r) {
        return r.nextBoolean() ? Gender.FEMALE : Gender.MALE;
    }

    /** Mostly the other gender, sometimes the same. */
    private static Gender partnerGender(Gender own, RandomGenerator r) {
        boolean same = r.nextInt(100) < 10;
        return switch (own) {
            case FEMALE -> same ? Gender.FEMALE : Gender.MALE;
            case MALE -> same ? Gender.MALE : Gender.FEMALE;
            case OTHER -> gender(r);
        };
    }
}
