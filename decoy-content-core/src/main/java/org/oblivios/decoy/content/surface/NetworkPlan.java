// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content.surface;

import java.util.List;

public record NetworkPlan(List<WifiNetwork> wifi, List<BluetoothPair> bluetooth) {}
