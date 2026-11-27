// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.data;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** The shape of real calls, read from {@code spreads/callshape.properties}: today the share of each hour of the day. */
public final class CallShape {
    private static volatile CallShape loaded;

    private final List<Double> hourShares;
    private final String version;

    private CallShape(Properties p) {
        List<Double> shares = new ArrayList<>();
        for (String share : p.getProperty("hour.shares", "").split(",")) {
            shares.add(Double.parseDouble(share));
        }
        if (shares.size() != 24 || p.getProperty("version") == null) {
            throw new IllegalStateException("spreads/callshape: not 24 hour shares and a version");
        }
        hourShares = List.copyOf(shares);
        version = p.getProperty("version");
    }

    public static CallShape load() {
        CallShape found = loaded;
        if (found == null) {
            found = read();
            loaded = found;
        }
        return found;
    }

    private static CallShape read() {
        Properties p = new Properties();
        try (Reader in = Tables.open("spreads/callshape.properties")) {
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new CallShape(p);
    }

    /** The share of each hour from midnight. */
    public List<Double> hourShares() {
        return hourShares;
    }

    public String version() {
        return version;
    }
}
