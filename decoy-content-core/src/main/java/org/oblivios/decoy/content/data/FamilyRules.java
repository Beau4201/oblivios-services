// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import java.util.HashMap;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.NameForm;

/**
 * Which family name a child gets and which one a married person uses, with the weights of the country pack. One
 * instance serves one plan: it remembers each couple's choice, so that siblings share a name.
 */
public final class FamilyRules {
    private final Map<NameForm.Child, Integer> child;
    private final Map<NameForm.Married, Integer> married;
    private final Map<String, String> chosen = new HashMap<>();

    private FamilyRules(Map<NameForm.Child, Integer> child, Map<NameForm.Married, Integer> married) {
        this.child = child;
        this.married = married;
    }

    /** Throws {@link IllegalArgumentException} for a country whose pack holds no family rules. */
    public static FamilyRules of(CountryPack pack) {
        if (pack.childNameForms().isEmpty() || pack.marriedNameForms().isEmpty()) {
            throw new IllegalArgumentException("no family name rules for country " + pack.code());
        }
        return new FamilyRules(pack.childNameForms(), pack.marriedNameForms());
    }

    /** The family name of a child of two parents; an empty parent name leaves the other's. */
    public String childFamily(String father, String mother, RandomGenerator r) {
        if (father.isBlank() || mother.isBlank() || father.equals(mother)) {
            return father.isBlank() ? mother : father;
        }
        return chosen.computeIfAbsent(father + '\n' + mother, couple -> switch (draw(child, r)) {
            case FATHER -> father;
            case MOTHER -> mother;
            case BOTH -> r.nextBoolean() ? father + " " + mother : mother + " " + father;
        });
    }

    /** The family name a person uses: the own one unless married, and then by the weights. */
    public String usedFamily(String own, String partner, boolean isMarried, RandomGenerator r) {
        if (!isMarried || partner.isBlank() || partner.equals(own)) {
            return own;
        }
        return switch (draw(married, r)) {
            case OWN -> own;
            case PARTNER -> partner;
            case PARTNER_OWN -> partner + "-" + own;
            case OWN_PARTNER -> own + "-" + partner;
        };
    }

    private static <E extends Enum<E>> E draw(Map<E, Integer> weights, RandomGenerator r) {
        int pick = r.nextInt(weights.values().stream().mapToInt(Integer::intValue).sum());
        for (Map.Entry<E, Integer> e : weights.entrySet()) {
            pick -= e.getValue();
            if (pick < 0) {
                return e.getKey();
            }
        }
        throw new IllegalStateException("unreachable: the pick is below the total");
    }
}
