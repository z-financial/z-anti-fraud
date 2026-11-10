package com.example.zantifraud.event.model;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * An occurrence timestamp with its source precision and explicit UTC offset.
 *
 * <p>Upstream producers must supply an explicit offset, including Z for UTC.
 * The API must not infer an offset for a local timestamp. Precision describes
 * the source observation, independently of the number of decimal places used
 * to represent the timestamp. Retain any dataset timezone assumptions and
 * synthetic replay-clock mapping in adapter provenance outside this contract.
 */
public record EventTime(
        OffsetDateTime value,
        Precision precision
) {
    public EventTime {
        Objects.requireNonNull(value, "value is required");
        Objects.requireNonNull(precision, "precision is required");
    }

    public enum Precision {
        NANOSECOND,
        MICROSECOND,
        MILLISECOND,
        SECOND,
        MINUTE,
        HOUR,
        DAY,
        MONTH,
        YEAR,
        UNKNOWN
    }
}
