package com.example.zantifraud.event.mapping;

import com.example.zantifraud.evaluation.api.http.request.JsonEventEnvelope;
import com.example.zantifraud.event.model.EventModel;

import java.util.Set;

/** Converts supported HTTP envelopes to validated internal events; does not query features. */
public interface EventAdapter {
    Set<EventAdapterKey> supportedEvents();

    EventModel adapt(JsonEventEnvelope envelope);
}
