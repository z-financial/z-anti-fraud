package com.example.zantifraud.event.model;

/** Analytical event types derived by adapters; not accepted by the external API. */
public enum InternalEventType {
    TRANSFER_OUT("money_movement.transfer.out"),
    TRANSFER_IN("money_movement.transfer.in");

    private final String wireValue;

    InternalEventType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
