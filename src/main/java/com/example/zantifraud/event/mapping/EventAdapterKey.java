package com.example.zantifraud.event.mapping;

import com.example.zantifraud.event.model.EventType;

import java.net.URI;
import java.util.Objects;

/** Routing identity only; source registration does not authenticate the caller. */
public record EventAdapterKey(EventType eventType, URI source) {
    public EventAdapterKey {
        Objects.requireNonNull(eventType, "eventType is required");
        Objects.requireNonNull(source, "source is required");
    }
}
