// SPDX-License-Identifier: Apache-2.0
package org.sovereignos.airpods.platform;

public sealed interface ControlCommand permits SetListeningMode, PlaySound {
}
