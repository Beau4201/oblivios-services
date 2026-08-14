package org.aegisos.duress.state;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DuressProfileStateCodecTest {

    @Test
    void encodesAndDecodesOffState() {
        String line = DuressProfileStateCodec.encode(new DuressProfileState.Off());
        assertEquals("OFF", line);
        assertInstanceOf(DuressProfileState.Off.class, DuressProfileStateCodec.decode(line));
    }

    @Test
    void encodesAndDecodesRegisteredStateRoundTrip() {
        byte[] hash = {1, 2, 3, 4, 5};
        byte[] salt = {9, 8, 7};
        DuressProfileState.Registered original =
                new DuressProfileState.Registered("profile-1", hash, salt);

        String line = DuressProfileStateCodec.encode(original);
        DuressProfileState decoded = DuressProfileStateCodec.decode(line);

        DuressProfileState.Registered registered =
                assertInstanceOf(DuressProfileState.Registered.class, decoded);
        assertEquals("profile-1", registered.profileId());
        assertArrayEquals(hash, registered.credentialHash());
        assertArrayEquals(salt, registered.salt());
    }

    @Test
    void decodingMalformedLineThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> DuressProfileStateCodec.decode("not-a-valid-line"));
    }
}
