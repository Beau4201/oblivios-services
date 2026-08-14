package org.aegisos.duress.state;

public sealed interface DuressProfileState {

    record Off() implements DuressProfileState {
    }

    record Registered(String profileId, byte[] credentialHash, byte[] salt)
            implements DuressProfileState {
    }
}
