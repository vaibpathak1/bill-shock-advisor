package com.telco.billshock.api;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.telco.billshock.agent.ConversationNotFoundException;

/**
 * RFC 9457 Problem Details with an error code for every API error (SPEC §4.7, §4.8). Framework
 * errors (validation, bad JSON, unsupported media type) come from {@link ResponseEntityExceptionHandler}.
 * Authentication and authorisation errors stay with Spring Security (401/403).
 */
@RestControllerAdvice
class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ConversationNotFoundException.class)
    ProblemDetail conversationNotFound(ConversationNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Conversation not found");
        problem.setType(URI.create("urn:billshock:problem:conversation-not-found"));
        problem.setProperty("code", "CONVERSATION_NOT_FOUND");
        return problem;
    }
}
