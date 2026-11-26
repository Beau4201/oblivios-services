// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import java.util.Map;
import java.util.TreeMap;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.country.Packs;

/** The data of one plan: what was read, and in which version. One instance serves one plan. */
public final class DataSet {
    private final CountryPack pack;
    private final Map<String, String> versions = new TreeMap<>();
    private FamilyRules family;

    private DataSet(CountryPack pack) {
        this.pack = pack;
    }

    /** Throws {@link IllegalArgumentException} for a country without a pack. */
    public static DataSet of(String country) {
        return new DataSet(Packs.load(country).orElseThrow(() -> new IllegalArgumentException("no data for country " + country)));
    }

    public NameData names(String background) {
        NameData data = NameData.load(background);
        versions.put("names/" + background, data.version());
        return data;
    }

    public FamilyRules family() {
        if (family == null) {
            family = FamilyRules.of(pack);
        }
        return family;
    }

    /** The version of each data file that was read through this set. */
    public Map<String, String> versions() {
        return Map.copyOf(versions);
    }
}
