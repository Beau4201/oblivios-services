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

    /** The places of the set's country; throws {@link IllegalArgumentException} for a country without them. */
    public PlaceData places() {
        PlaceData data = PlaceData.load(pack.code());
        versions.put("places/" + pack.code(), data.version());
        return data;
    }

    public PhoneNumbers numbers() {
        PhoneNumbers data = PhoneNumbers.of(pack.code());
        versions.put("numbers/" + pack.code(), data.version());
        return data;
    }

    public PopulationData population() {
        PopulationData data = PopulationData.load(pack.code());
        versions.put("population/" + pack.code(), data.version());
        return data;
    }

    public HolidayData holidays() {
        HolidayData data = HolidayData.load(pack.code());
        versions.put("holidays/" + pack.code(), data.version());
        return data;
    }

    /** The version of each data file that was read through this set. */
    public Map<String, String> versions() {
        return Map.copyOf(versions);
    }
}
