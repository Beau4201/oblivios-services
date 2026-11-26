// SPDX-License-Identifier: Apache-2.0
package org.oblivios.decoy.content;

import java.util.List;
import java.util.Map;

/**
 * What the setup reads on the device, so that no view invents it: the model, the app versions by package, the ringtones,
 * the notification strings by package and the screen size.
 */
public record DeviceFacts(String model, String defaultDeviceName, Map<String, String> appVersions, List<String> ringtones,
        String defaultRingtone, Map<String, List<String>> notificationStrings, int screenWidth, int screenHeight) {
    public DeviceFacts {
        appVersions = Map.copyOf(appVersions);
        ringtones = List.copyOf(ringtones);
        notificationStrings = Map.copyOf(notificationStrings);
    }
}
