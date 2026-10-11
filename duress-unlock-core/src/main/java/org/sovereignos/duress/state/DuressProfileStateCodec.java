// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.duress.state;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class DuressProfileStateCodec {

    private DuressProfileStateCodec() {
    }

    static String encode(DuressProfileState state) {
        if (state instanceof DuressProfileState.Off) {
            return "OFF";
        }
        DuressProfileState.Registered registered = (DuressProfileState.Registered) state;
        return "REGISTERED,"
                + Base64.getEncoder().encodeToString(registered.profileId().getBytes(StandardCharsets.UTF_8))
                + ","
                + Base64.getEncoder().encodeToString(registered.credentialHash())
                + ","
                + Base64.getEncoder().encodeToString(registered.salt());
    }

    static DuressProfileState decode(String line) {
        if (line.equals("OFF")) {
            return new DuressProfileState.Off();
        }
        String[] parts = line.split(",", 4);
        if (parts.length != 4 || !parts[0].equals("REGISTERED")) {
            throw new IllegalArgumentException("Malformed duress profile state line");
        }
        String profileId = new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        byte[] hash = Base64.getDecoder().decode(parts[2]);
        byte[] salt = Base64.getDecoder().decode(parts[3]);
        return new DuressProfileState.Registered(profileId, hash, salt);
    }
}
