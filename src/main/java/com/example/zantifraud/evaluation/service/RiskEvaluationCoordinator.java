package com.example.zantifraud.evaluation.service;

import com.example.zantifraud.evaluation.api.http.request.RiskEvaluationRequest;
import com.example.zantifraud.evaluation.api.http.response.RiskEvaluationResponse;
import com.example.zantifraud.evaluation.decision.DecisionEngine;
import com.example.zantifraud.evaluation.model.Decision;
import com.example.zantifraud.evaluation.model.RiskEvaluationStatus;
import com.example.zantifraud.evaluation.rule.RuleEngine;
import com.example.zantifraud.evaluation.rule.RuleEvaluation;
import com.example.zantifraud.event.mapping.AdaptedEvent;
import com.example.zantifraud.event.mapping.EventAdapterRegistry;
import com.example.zantifraud.event.model.EventModel;
import com.example.zantifraud.feature.model.FeatureSnapshot;
import com.example.zantifraud.feature.service.FeatureService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Adapts an event, loads its features and applies the risk decision policy.
 * Authentication/source authorization and durable idempotency must precede
 * ingestion in the eventual implementation. Decision metadata is per evaluation attempt.
 */
@Service
public class RiskEvaluationCoordinator {
    private final EventAdapterRegistry adapters;
    private final FeatureService featureService;
    private final RuleEngine ruleEngine;
    private final DecisionEngine decisionEngine;

    public RiskEvaluationCoordinator(EventAdapterRegistry adapters, FeatureService featureService,
                                     RuleEngine ruleEngine, DecisionEngine decisionEngine) {
        this.adapters = adapters;
        this.featureService = featureService;
        this.ruleEngine = ruleEngine;
        this.decisionEngine = decisionEngine;
    }

    public RiskEvaluationResponse evaluate(RiskEvaluationRequest request) {
        AdaptedEvent adapted = adapters.resolve(request.event().eventType(), request.event().source())
                .adapt(request.event());
        EventModel event = adapted.event();

        featureService.persistEvents(adapted.featureEvents());
        FeatureSnapshot features = featureService.queryFeatures(adapted.featureEvents());
        RuleEvaluation evaluation = ruleEngine.evaluate(event, features);
        Decision decision = decisionEngine.decide(evaluation);
        boolean hasRecommendation = decision.status() != RiskEvaluationStatus.UNAVAILABLE;
        return new RiskEvaluationResponse(
                request.requestId(), hasRecommendation ? UUID.randomUUID().toString() : null,
                event.eventId(), event.operationId(), decision.status(), decision.recommendedAction(),
                decision.reasons(), hasRecommendation ? RuleEngine.RULE_SET_VERSION : null,
                hasRecommendation ? DecisionEngine.POLICY_VERSION : null, hasRecommendation ? Instant.now() : null);
    }
}
