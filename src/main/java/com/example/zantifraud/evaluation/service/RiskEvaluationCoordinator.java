package com.example.zantifraud.evaluation.service;

import com.example.zantifraud.evaluation.api.http.request.RiskEvaluationRequest;
import com.example.zantifraud.evaluation.api.http.response.RiskEvaluationResponse;
import com.example.zantifraud.evaluation.model.DecisionReason;
import com.example.zantifraud.evaluation.model.EvaluationStatus;
import com.example.zantifraud.evaluation.rule.RuleEngine;
import com.example.zantifraud.event.mapping.AdaptedEvent;
import com.example.zantifraud.event.mapping.EventAdapterRegistry;
import com.example.zantifraud.event.model.EventModel;
import com.example.zantifraud.feature.model.FeatureSnapshot;
import com.example.zantifraud.feature.service.FeatureService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MVP orchestration: adapt an event, request persistence, query features and evaluate rules.
 * Authentication/source authorization and durable idempotency must precede
 * ingestion in the eventual implementation. This MVP performs no ingestion or
 * persistence and issues no decision. decisionPoint remains unused in V1.
 */
@Service
public class RiskEvaluationCoordinator {
    private final EventAdapterRegistry adapters;
    private final FeatureService featureService;
    private final RuleEngine ruleEngine;

    public RiskEvaluationCoordinator(EventAdapterRegistry adapters, FeatureService featureService,
                                     RuleEngine ruleEngine) {
        this.adapters = adapters;
        this.featureService = featureService;
        this.ruleEngine = ruleEngine;
    }

    public RiskEvaluationResponse evaluate(RiskEvaluationRequest request) {
        AdaptedEvent adapted = adapters.resolve(request.event().eventType(), request.event().source())
                .adapt(request.event());
        EventModel event = adapted.event();

        featureService.persistEvents(adapted.featureEvents());
        FeatureSnapshot features = featureService.queryFeatures(adapted.featureEvents());
        ruleEngine.evaluate(event, features);

        // TODO: pass the rule evaluation to the decision engine.
        return new RiskEvaluationResponse(
                request.requestId(), null, event.eventId(), event.operationId(),
                EvaluationStatus.UNAVAILABLE, null,
                List.of(new DecisionReason("EVALUATION_NOT_CONFIGURED",
                        "The decision engine is not configured.", null)),
                null, null, null);
    }
}
