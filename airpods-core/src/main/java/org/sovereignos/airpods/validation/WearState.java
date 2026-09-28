package org.sovereignos.airpods.validation;

public record WearState(boolean leftWorn, boolean rightWorn) implements AirPodsMessage {
}
