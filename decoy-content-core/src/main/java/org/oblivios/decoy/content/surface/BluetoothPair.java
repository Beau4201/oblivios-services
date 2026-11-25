// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.time.Instant;

/** A paired accessory; the address is locally administered, so it is never a real device's address. */
public record BluetoothPair(String name, String address, Instant pairedAt) {}
