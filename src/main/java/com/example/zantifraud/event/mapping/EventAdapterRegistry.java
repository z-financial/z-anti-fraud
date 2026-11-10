package com.example.zantifraud.event.mapping;

import com.example.zantifraud.event.model.EventType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class EventAdapterRegistry {
    private final Map<EventAdapterKey, EventAdapter> adapters;

    public EventAdapterRegistry(List<EventAdapter> candidates) {
        Map<EventAdapterKey, EventAdapter> registrations = new HashMap<>();
        for (EventAdapter adapter : candidates) {
            for (EventAdapterKey key : adapter.supportedEvents()) {
                if (registrations.putIfAbsent(key, adapter) != null) {
                    throw new IllegalStateException("Duplicate event adapter registration: " + key);
                }
            }
        }
        adapters = Map.copyOf(registrations);
    }

    public EventAdapter resolve(EventType eventType, URI source) {
        EventAdapter adapter = adapters.get(new EventAdapterKey(eventType, source));
        if (adapter == null) {
            throw new EventAdaptationException("UNSUPPORTED_EVENT",
                    "No adapter supports this event type and source combination.");
        }
        return adapter;
    }
}
