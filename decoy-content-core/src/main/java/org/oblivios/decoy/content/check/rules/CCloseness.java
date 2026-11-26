// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.oblivios.decoy.content.check.OriginRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.PlanStory;
import org.oblivios.decoy.content.check.PlanStory.Person;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.surface.Contact;
import org.oblivios.decoy.content.surface.Relation;
import org.oblivios.decoy.content.surface.SmsThread;

/**
 * {@code C-CLOSENESS}: calls to acquaintances outnumber calls to the {@value #CLOSEST} closest people; a partner is not
 * among the {@value #TOP} most called numbers; or one of the {@value #CLOSEST} closest has neither a thread nor a call
 * in the last {@value #RECENT_DAYS} days. Calls of every type count.
 */
public final class CCloseness implements OriginRule {
    static final int CLOSEST = 10;
    static final int TOP = 3;
    static final int RECENT_DAYS = 90;

    @Override
    public String id() {
        return "C-CLOSENESS";
    }

    @Override
    public void check(PhoneViews v, PlanStory story, List<Violation> out) {
        Map<String, Integer> calls = new HashMap<>();
        v.people().calls().forEach(c -> calls.merge(c.number(), 1, Integer::sum));
        record Known(Contact contact, Person person) {}
        List<Known> known = new ArrayList<>();
        for (Contact c : v.people().contacts()) {
            story.personByNumber(c.number()).ifPresent(p -> known.add(new Known(c, p)));
        }
        List<Known> closest = known.stream().sorted(Comparator.comparingInt(k -> k.person().closeness())).limit(CLOSEST).toList();
        int toClosest = closest.stream().mapToInt(k -> calls.getOrDefault(k.contact().number(), 0)).sum();
        int toAcquaintances = known.stream().filter(k -> k.person().relation() == Relation.ACQUAINTANCE)
                .mapToInt(k -> calls.getOrDefault(k.contact().number(), 0)).sum();
        if (toAcquaintances > toClosest) {
            out.add(new Violation(id(), toAcquaintances + " calls with acquaintances against " + toClosest + " with the " + CLOSEST + " closest"));
        }
        Set<String> top = calls.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP).map(Map.Entry::getKey).collect(Collectors.toSet());
        for (Known k : known) {
            if (k.person().relation() == Relation.PARTNER && !top.contains(k.contact().number())) {
                out.add(new Violation(id(), "the partner " + k.contact().name() + " is not among the " + TOP + " most called numbers"));
            }
        }
        Instant since = story.now().minus(Duration.ofDays(RECENT_DAYS));
        for (Known k : closest) {
            String number = k.contact().number();
            boolean called = v.people().calls().stream().anyMatch(c -> c.number().equals(number) && !c.at().isBefore(since));
            boolean texted = false;
            for (SmsThread t : v.people().threads()) {
                if ((t.address().equals(number) || t.members().contains(k.contact().id()))
                        && t.messages().stream().anyMatch(m -> !m.at().isBefore(since))) {
                    texted = true;
                    break;
                }
            }
            if (!called && !texted) {
                out.add(new Violation(id(), k.contact().name() + ", among the " + CLOSEST + " closest, has neither a call nor a message in the last "
                        + RECENT_DAYS + " days"));
            }
        }
    }
}
