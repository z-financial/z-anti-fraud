package com.example.zantifraud.evaluation.api.http;

import com.example.zantifraud.evaluation.model.EvaluationStatus;
import com.example.zantifraud.evaluation.api.http.request.RiskEvaluationRequest;
import com.example.zantifraud.evaluation.api.http.response.RiskEvaluationResponse;
import com.example.zantifraud.evaluation.service.RiskEvaluationCoordinator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/risk-evaluations")
public class RiskEvaluationController {
    private final RiskEvaluationCoordinator coordinator;

    public RiskEvaluationController(RiskEvaluationCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RiskEvaluationResponse> evaluate(@RequestBody RiskEvaluationRequest request) {
        RiskEvaluationResponse response = coordinator.evaluate(request);
        HttpStatus status = response.status() == EvaluationStatus.UNAVAILABLE
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}
