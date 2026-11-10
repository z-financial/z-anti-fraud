package com.example.zantifraud.event.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

/**
 * Event types defined by the V1 design, with stable external names.
 * Adapters use {@link #fromWireValue(String)} and {@link #wireValue()} when
 * translating between transport values and the Java contract.
 */
public enum EventType {
    LOGIN_ATTEMPTED("authentication.login.attempted"),
    LOGIN_COMPLETED("authentication.login.completed"),
    CHALLENGE_COMPLETED("authentication.challenge.completed"),

    REGISTRATION_REQUESTED("account.registration.requested"),
    APPLICATION_SUBMITTED("account.application.submitted"),
    ACCOUNT_VERIFICATION_COMPLETED("account.verification.completed"),
    ACCOUNT_RECOVERY_REQUESTED("account.recovery.requested"),

    TRANSFER_REQUESTED("money_movement.transfer.requested"),
    TRANSFER_COMPLETED("money_movement.transfer.completed"),
    WITHDRAWAL_REQUESTED("money_movement.withdrawal.requested"),
    PAYMENT_REQUESTED("money_movement.payment.requested"),

    ORDER_SUBMITTED("market_activity.order.submitted"),
    TRADE_EXECUTED("market_activity.trade.executed"),
    TRANSACTION_OBSERVED("market_activity.transaction.observed"),

    CASE_CONFIRMED("risk_feedback.case.confirmed"),
    LABEL_CORRECTED("risk_feedback.label.corrected");

    private final String wireValue;

    EventType(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /**
     * Resolves an exact external name, rejecting unsupported values.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static EventType fromWireValue(String wireValue) {
        Objects.requireNonNull(wireValue, "wireValue is required");
        for (EventType eventType : values()) {
            if (eventType.wireValue.equals(wireValue)) {
                return eventType;
            }
        }
        throw new IllegalArgumentException("Unsupported event type: " + wireValue);
    }
}
