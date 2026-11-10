package com.example.zantifraud.event.mapping;

import com.example.zantifraud.evaluation.api.http.request.JsonEventEnvelope;

import java.util.Set;

/** Converts supported HTTP envelopes to validated internal events; does not query features. */
public interface EventAdapter {
    Set<EventAdapterKey> supportedEvents();

    /** Returns the decoded event and feature events with distinct, stable IDs. */
    AdaptedEvent adapt(JsonEventEnvelope envelope);
}
