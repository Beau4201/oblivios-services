package org.aegisos.duress.state;

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
                + registered.profileId()
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
            throw new IllegalArgumentException("Malformed duress profile state line: " + line);
        }
        byte[] hash = Base64.getDecoder().decode(parts[2]);
        byte[] salt = Base64.getDecoder().decode(parts[3]);
        return new DuressProfileState.Registered(parts[1], hash, salt);
    }
}
