package com.crm.odontologico.kassar.odontograma.application.service;

import com.crm.odontologico.kassar.odontograma.application.dto.OdontogramaUpdateResponse;
import com.crm.odontologico.kassar.odontograma.application.dto.VoiceCaptureRequest;
import com.crm.odontologico.kassar.odontograma.application.mapper.OdontogramaDomainMapper;
import com.crm.odontologico.kassar.odontograma.application.port.AiChatMessage;
import com.crm.odontologico.kassar.odontograma.application.port.OdontogramaAiPort;
import com.crm.odontologico.kassar.odontograma.application.validation.AiResponseValidator;
import com.crm.odontologico.kassar.odontograma.application.validation.ValidationResult;
import com.crm.odontologico.kassar.odontograma.domain.exception.AiValidationExhaustedException;
import com.crm.odontologico.kassar.odontograma.domain.exception.InvalidVoiceCaptureRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Caso de uso central que orquesta:
 * <ol>
 *   <li>La normalización y validación del {@link VoiceCaptureRequest} proveniente del Frontend.</li>
 *   <li>La invocación al modelo de IA a través de {@link OdontogramaAiPort}.</li>
 *   <li>La validación clínica y anatómica de la respuesta mediante {@link AiResponseValidator}
 *       con un bucle de autocorrección (enviando retroalimentación de errores al modelo).</li>
 *   <li>El enriquecimiento autoritativo del {@link OdontogramaUpdateResponse} mediante {@link OdontogramaDomainMapper}.</li>
 * </ol>
 */
@Service
public class ProcessVoiceDictationService {

    private static final Logger log = LoggerFactory.getLogger(ProcessVoiceDictationService.class);

    private final OdontogramaAiPort aiPort;
    private final OdontogramaPromptBuilder promptBuilder;
    private final AiResponseValidator responseValidator;
    private final OdontogramaDomainMapper domainMapper;
    private final ObjectMapper objectMapper;
    private final int maxValidationAttempts;

    public ProcessVoiceDictationService(
            OdontogramaAiPort aiPort,
            OdontogramaPromptBuilder promptBuilder,
            AiResponseValidator responseValidator,
            OdontogramaDomainMapper domainMapper,
            ObjectMapper objectMapper,
            @Value("${odontograma.ai.max-attempts:3}") int maxValidationAttempts
    ) {
        this.aiPort = aiPort;
        this.promptBuilder = promptBuilder;
        this.responseValidator = responseValidator;
        this.domainMapper = domainMapper;
        this.objectMapper = objectMapper;
        this.maxValidationAttempts = Math.max(1, maxValidationAttempts);
    }

    public OdontogramaUpdateResponse procesarDictado(VoiceCaptureRequest rawRequest) {
        NormalizedRequest request = normalizarYValidarRequest(rawRequest);

        List<AiChatMessage> historial = new ArrayList<>();
        historial.add(AiChatMessage.system(promptBuilder.obtenerSystemPrompt()));
        historial.add(AiChatMessage.user(promptBuilder.construirPromptUsuario(request.textoCapturado())));

        List<String> ultimosErrores = List.of();
        String ultimaRespuestaCruda = "";

        for (int intento = 1; intento <= maxValidationAttempts; intento++) {
            log.info("Procesando dictado para odontogramaId='{}' (intento {}/{})",
                    request.odontogramaId(), intento, maxValidationAttempts);

            long inicioLlamadaAi = System.nanoTime();
            try {
                ultimaRespuestaCruda = aiPort.generarRespuestaJson(historial);
            } finally {
                long duracionMs = (System.nanoTime() - inicioLlamadaAi) / 1_000_000;
                log.info("Duración de llamada al LLM para odontogramaId='{}' (intento {}/{}): {} ms",
                        request.odontogramaId(), intento, maxValidationAttempts, duracionMs);
            }
            ValidationResult resultado = responseValidator.validar(ultimaRespuestaCruda);

            if (resultado.isValid()) {
                log.info("Respuesta del modelo validada exitosamente en el intento {} ({} diente(s) procesado(s))",
                        intento, resultado.dientesValidados().size());
                return domainMapper.toUpdateResponse(request.odontogramaId(), resultado.dientesValidados());
            }

            ultimosErrores = resultado.errores();
            log.warn("Intento {}/{} falló validación para odontogramaId='{}'. Errores detectados: {}",
                    intento, maxValidationAttempts, request.odontogramaId(), ultimosErrores);

            if (intento < maxValidationAttempts) {
                historial.add(AiChatMessage.assistant(ultimaRespuestaCruda != null ? ultimaRespuestaCruda : ""));
                historial.add(AiChatMessage.user(promptBuilder.construirPromptCorreccion(ultimosErrores)));
            }
        }

        throw new AiValidationExhaustedException(maxValidationAttempts, ultimosErrores, ultimaRespuestaCruda);
    }

    /**
     * Soporta tanto el contrato directo {@code {"odontogramaId": "...", "textoCapturado": "..."}}
     * como el payload envuelto en {@code {"prompt": "{\"odontogramaId\": \"...\", \"textoCapturado\": \"...\"}"}}.
     */
    private NormalizedRequest normalizarYValidarRequest(VoiceCaptureRequest rawRequest) {
        if (rawRequest == null) {
            throw new InvalidVoiceCaptureRequestException("El cuerpo de la petición no puede ser nulo.");
        }

        String odontogramaId = rawRequest.odontogramaId();
        String textoCapturado = rawRequest.textoCapturado();

        if ((odontogramaId == null || odontogramaId.isBlank() || textoCapturado == null || textoCapturado.isBlank())
                && rawRequest.prompt() != null && !rawRequest.prompt().isBlank()) {
            try {
                JsonNode promptNode = objectMapper.readTree(rawRequest.prompt());
                if (promptNode != null && promptNode.isObject()) {
                    if (promptNode.hasNonNull("odontogramaId")) {
                        odontogramaId = promptNode.get("odontogramaId").asString();
                    }
                    if (promptNode.hasNonNull("textoCapturado")) {
                        textoCapturado = promptNode.get("textoCapturado").asString();
                    }
                }
            } catch (JacksonException ex) {
                throw new InvalidVoiceCaptureRequestException(
                        "El campo 'prompt' no contiene un JSON válido con 'odontogramaId' y 'textoCapturado'.");
            }
        }

        if (odontogramaId == null || odontogramaId.isBlank()) {
            throw new InvalidVoiceCaptureRequestException("El campo 'odontogramaId' es obligatorio.");
        }
        if (textoCapturado == null || textoCapturado.isBlank()) {
            throw new InvalidVoiceCaptureRequestException("El campo 'textoCapturado' es obligatorio y no puede estar vacío.");
        }

        return new NormalizedRequest(odontogramaId.trim(), textoCapturado.trim());
    }

    private record NormalizedRequest(String odontogramaId, String textoCapturado) {
    }
}
