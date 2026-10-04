package org.sovereignos.airpods.validation;

/** A frame from the AirPods, decoded and range-checked by {@link AapCodec}. */
public sealed interface AirPodsMessage
        permits BatteryStatus,
                ListeningModeState,
                EarDetectionSetting,
                ConversationAwarenessSetting,
                ConversationAwarenessLevel,
                AdaptiveLevelSetting,
                EarState,
                DeviceInformation,
                ControlReport,
                CallManagementReport,
                UnrecognisedMessage {
}
