package com.example.zantifraud.evaluation.service;

import com.example.zantifraud.evaluation.api.http.request.RiskEvaluationRequest;
import com.example.zantifraud.evaluation.api.http.response.RiskEvaluationResponse;
import com.example.zantifraud.evaluation.model.DecisionReason;
import com.example.zantifraud.evaluation.model.EvaluationStatus;
import com.example.zantifraud.event.mapping.AdaptedEvent;
import com.example.zantifraud.event.mapping.EventAdapterRegistry;
import com.example.zantifraud.event.model.EventModel;
import com.example.zantifraud.feature.service.FeatureService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MVP orchestration: adapt a supported event, request persistence, then query features.
 * Authentication/source authorization and durable idempotency must precede
 * ingestion in the eventual implementation. This MVP performs no ingestion or
 * persistence and issues no decision. decisionPoint remains unused in V1.
 */
@Service
public class RiskEvaluationCoordinator {
    private final EventAdapterRegistry adapters;
    private final FeatureService featureService;

    public RiskEvaluationCoordinator(EventAdapterRegistry adapters, FeatureService featureService) {
        this.adapters = adapters;
        this.featureService = featureService;
    }

    public RiskEvaluationResponse evaluate(RiskEvaluationRequest request) {
        AdaptedEvent adapted = adapters.resolve(request.event().eventType(), request.event().source())
                .adapt(request.event());
        EventModel event = adapted.event();

        featureService.persistEvents(adapted.featureEvents());
        featureService.queryFeatures(adapted.featureEvents());

        // TODO: evaluate the decoded event with the returned feature snapshot and apply decision policy.
        return new RiskEvaluationResponse(
                request.requestId(), null, event.eventId(), event.operationId(),
                EvaluationStatus.UNAVAILABLE, null,
                List.of(new DecisionReason("EVALUATION_NOT_CONFIGURED",
                        "The risk evaluation engine is not configured.", null)),
                null, null, null);
    }
}
