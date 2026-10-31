package com.crm.odontologico.kassar.odontograma.infrastructure.web;

import com.crm.odontologico.kassar.odontograma.domain.exception.AiProviderCommunicationException;
import com.crm.odontologico.kassar.odontograma.domain.exception.AiValidationExhaustedException;
import com.crm.odontologico.kassar.odontograma.domain.exception.InvalidVoiceCaptureRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

/**
 * Manejo centralizado de excepciones bajo el estándar RFC 7807 ({@link ProblemDetail}).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidVoiceCaptureRequestException.class)
    public ProblemDetail handleInvalidVoiceCaptureRequest(InvalidVoiceCaptureRequestException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Petición de captura de voz inválida");
        problem.setType(URI.create("https://crm-odontologico.com/errors/invalid-voice-capture-request"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(AiValidationExhaustedException.class)
    public ProblemDetail handleAiValidationExhausted(AiValidationExhaustedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("El modelo de IA no superó las validaciones clínicas tras los reintentos");
        problem.setType(URI.create("https://crm-odontologico.com/errors/ai-validation-exhausted"));
        problem.setProperty("intentosRealizados", ex.getIntentosRealizados());
        problem.setProperty("erroresValidacion", ex.getUltimosErrores());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(AiProviderCommunicationException.class)
    public ProblemDetail handleAiProviderCommunication(AiProviderCommunicationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
        problem.setTitle("Error de comunicación con el proveedor de IA");
        problem.setType(URI.create("https://crm-odontologico.com/errors/ai-provider-unavailable"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
