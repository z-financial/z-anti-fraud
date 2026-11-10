package com.example.zantifraud.evaluation.api.http.request;

import java.util.Objects;

/**
 * One transport attempt to ingest an event and evaluate its risk.
 *
 * <p>A retry may change {@code requestId}, while preserving the idempotency key
 * and event. The coordinator must scope the key to the authenticated caller,
 * operation, and source/event identity.
 * The caller identity comes from the authenticated transport context.
 *
 * <p>V1 makes one decision per transfer. The optional {@code decisionPoint} is
 * reserved for future use and is ignored by V1 evaluation and idempotency.
 * Ingestion must stamp the platform receipt time when accepting the event.
 *
 * <p>The coordinator must compute, store, and compare a digest of material input
 * within that scope. Exclude request IDs, platform receipt timestamps, and the
 * unused decision point from that digest. Reject reuse with changed material
 * input. This request contract carries the retry key. Persistence and retry
 * resolution belong to the coordinator.
 */
public record RiskEvaluationRequest(
        String requestId,
        String idempotencyKey,
        String decisionPoint,
        JsonEventEnvelope event
) {
    public RiskEvaluationRequest {
        Objects.requireNonNull(requestId, "requestId is required");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey is required");
        Objects.requireNonNull(event, "event is required");

        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}
