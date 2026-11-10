package com.example.zantifraud.event.mapping;

/** An expected unsupported-event or invalid-payload error with a safe public message. */
public class EventAdaptationException extends RuntimeException {
    private final String code;

    public EventAdaptationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
