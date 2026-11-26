// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.check.rules;

import java.time.Instant;
import java.util.List;
import org.oblivios.decoy.content.Persona;
import org.oblivios.decoy.content.check.ExaminerRule;
import org.oblivios.decoy.content.check.PhoneViews;
import org.oblivios.decoy.content.check.Violation;
import org.oblivios.decoy.content.country.CountryPack;
import org.oblivios.decoy.content.surface.BluetoothPair;

/**
 * {@code H-HARDWARE}: a paired accessory whose Bluetooth address has the locally administered bit (bit 1 of the first
 * octet) set, or is no address of six hex octets: a classic accessory carries the address its maker was assigned
 * {@code [assumption: about real accessories, decoy-generator-audit.md]}.
 */
public final class HHardware implements ExaminerRule {
    @Override
    public String id() {
        return "H-HARDWARE";
    }

    @Override
    public void check(PhoneViews v, Persona persona, CountryPack pack, Instant now, List<Violation> out) {
        for (BluetoothPair b : v.network().bluetooth()) {
            String a = b.address();
            if (a == null || !a.matches("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")) {
                out.add(new Violation(id(), "accessory " + b.name() + " has no Bluetooth address: " + a));
            } else if ((Integer.parseInt(a.substring(0, 2), 16) & 0x02) != 0) {
                out.add(new Violation(id(), "accessory " + b.name() + " has a locally administered address " + a));
            }
        }
    }
}
