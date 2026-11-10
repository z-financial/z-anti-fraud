package com.example.zantifraud.evaluation.api.http;

import com.example.zantifraud.event.mapping.EventAdaptationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RiskEvaluationController.class)
public class RiskApiExceptionHandler {
    @ExceptionHandler(EventAdaptationException.class)
    public ProblemDetail invalidEvent(EventAdaptationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid event");
        problem.setProperty("code", exception.code());
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail invalidRequest(HttpMessageNotReadableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Provide a valid risk request, event metadata, and an object-valued JSON payload.");
        problem.setTitle("Invalid risk evaluation request");
        problem.setProperty("code", "INVALID_REQUEST");
        return problem;
    }
}
