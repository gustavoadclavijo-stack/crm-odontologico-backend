package com.crm.odontologico.kassar.odontograma.application.service;

import com.crm.odontologico.kassar.odontograma.application.dto.OdontogramaUpdateResponse;
import com.crm.odontologico.kassar.odontograma.application.dto.VoiceCaptureRequest;
import com.crm.odontologico.kassar.odontograma.application.mapper.OdontogramaDomainMapper;
import com.crm.odontologico.kassar.odontograma.application.port.AiChatMessage;
import com.crm.odontologico.kassar.odontograma.application.port.OdontogramaAiPort;
import com.crm.odontologico.kassar.odontograma.application.validation.AiResponseValidator;
import com.crm.odontologico.kassar.odontograma.application.validation.ClinicalDictationParser;
import com.crm.odontologico.kassar.odontograma.application.validation.ValidatedToothDraft;
import com.crm.odontologico.kassar.odontograma.application.validation.ValidationResult;
import com.crm.odontologico.kassar.odontograma.domain.exception.AiValidationExhaustedException;
import com.crm.odontologico.kassar.odontograma.domain.exception.InvalidVoiceCaptureRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Caso de uso central que orquesta:
 * <ol>
 *   <li>La normalización y análisis clínico del {@link VoiceCaptureRequest} proveniente del Frontend.</li>
 *   <li>La invocación al modelo de IA a través de {@link OdontogramaAiPort}.</li>
 *   <li>La validación clínica, anatómica y de completitud de la respuesta mediante {@link AiResponseValidator}
 *       y {@link ClinicalDictationParser}, con bucle de autocorrección hacia el modelo.</li>
 *   <li>La reconciliación de piezas dentales cuando el modelo local omite dientes en dictados extensos
 *       y el enriquecimiento autoritativo del {@link OdontogramaUpdateResponse}.</li>
 * </ol>
 */
@Service
public class ProcessVoiceDictationService {

    private static final Logger log = LoggerFactory.getLogger(ProcessVoiceDictationService.class);

    private final OdontogramaAiPort aiPort;
    private final OdontogramaPromptBuilder promptBuilder;
    private final AiResponseValidator responseValidator;
    private final ClinicalDictationParser dictationParser;
    private final OdontogramaDomainMapper domainMapper;
    private final ObjectMapper objectMapper;
    private final int maxValidationAttempts;

    @Autowired
    public ProcessVoiceDictationService(
            OdontogramaAiPort aiPort,
            OdontogramaPromptBuilder promptBuilder,
            AiResponseValidator responseValidator,
            ClinicalDictationParser dictationParser,
            OdontogramaDomainMapper domainMapper,
            ObjectMapper objectMapper,
            @Value("${odontograma.ai.max-attempts:3}") int maxValidationAttempts
    ) {
        this.aiPort = aiPort;
        this.promptBuilder = promptBuilder;
        this.responseValidator = responseValidator;
        this.dictationParser = dictationParser;
        this.domainMapper = domainMapper;
        this.objectMapper = objectMapper;
        this.maxValidationAttempts = Math.max(1, maxValidationAttempts);
    }

    public ProcessVoiceDictationService(
            OdontogramaAiPort aiPort,
            OdontogramaPromptBuilder promptBuilder,
            AiResponseValidator responseValidator,
            OdontogramaDomainMapper domainMapper,
            ObjectMapper objectMapper,
            int maxValidationAttempts
    ) {
        this(
                aiPort,
                promptBuilder,
                responseValidator,
                new ClinicalDictationParser(),
                domainMapper,
                objectMapper,
                maxValidationAttempts
        );
    }

    public OdontogramaUpdateResponse procesarDictado(VoiceCaptureRequest rawRequest) {
        NormalizedRequest request = normalizarYValidarRequest(rawRequest);

        String textoNormalizado = dictationParser.normalizarTextoDictado(request.textoCapturado());
        List<ValidatedToothDraft> dientesEsperados = dictationParser.extraerDientesDelDictado(request.textoCapturado());

        List<AiChatMessage> historial = new ArrayList<>();
        historial.add(AiChatMessage.system(promptBuilder.obtenerSystemPrompt()));
        historial.add(AiChatMessage.user(promptBuilder.construirPromptUsuario(
                textoNormalizado.isEmpty() ? request.textoCapturado() : textoNormalizado)));

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
            ValidationResult resultadoEstricto = responseValidator.validarContraDictado(
                    ultimaRespuestaCruda, dientesEsperados);

            if (resultadoEstricto.isValid()) {
                log.info("Respuesta del modelo validada exitosamente en el intento {} ({} diente(s) procesado(s))",
                        intento, resultadoEstricto.dientesValidados().size());
                return domainMapper.toUpdateResponse(request.odontogramaId(), resultadoEstricto.dientesValidados());
            }

            // Verifica si la estructura JSON/FDI base es válida pero el modelo local omitió o desalineó dientes
            // en un dictado múltiple complejo: en ese caso reconciliamos con los dientes extraídos del dictado.
            ValidationResult resultadoBase = responseValidator.validar(ultimaRespuestaCruda);
            if (resultadoBase.isValid() && !dientesEsperados.isEmpty()) {
                log.info("Reconciliando respuesta parcial del modelo ({} diente(s)) con los {} diente(s) detectados en el dictado clínico: {}",
                        resultadoBase.dientesValidados().size(), dientesEsperados.size(), resultadoEstricto.errores());
                List<ValidatedToothDraft> reconciliados = reconciliarDientes(
                        resultadoBase.dientesValidados(), dientesEsperados);
                return domainMapper.toUpdateResponse(request.odontogramaId(), reconciliados);
            }

            ultimosErrores = resultadoEstricto.errores();
            log.warn("Intento {}/{} falló validación para odontogramaId='{}'. Errores detectados: {}",
                    intento, maxValidationAttempts, request.odontogramaId(), ultimosErrores);

            if (intento < maxValidationAttempts) {
                historial.add(AiChatMessage.assistant(ultimaRespuestaCruda != null ? ultimaRespuestaCruda : ""));
                historial.add(AiChatMessage.user(promptBuilder.construirPromptCorreccion(ultimosErrores)));
            }
        }

        if (!dientesEsperados.isEmpty()) {
            log.warn("El modelo no produjo un JSON válido tras {} intentos; aplicando extracción clínica determinística de respaldo ({} diente(s)).",
                    maxValidationAttempts, dientesEsperados.size());
            return domainMapper.toUpdateResponse(request.odontogramaId(), dientesEsperados);
        }

        throw new AiValidationExhaustedException(maxValidationAttempts, ultimosErrores, ultimaRespuestaCruda);
    }

    private List<ValidatedToothDraft> reconciliarDientes(
            List<ValidatedToothDraft> dientesDelModelo,
            List<ValidatedToothDraft> dientesDelDictado
    ) {
        Map<Integer, ValidatedToothDraft> consolidado = new LinkedHashMap<>();
        for (ValidatedToothDraft esperado : dientesDelDictado) {
            consolidado.put(esperado.metadata().numero(), esperado);
        }
        for (ValidatedToothDraft delModelo : dientesDelModelo) {
            consolidado.putIfAbsent(delModelo.metadata().numero(), delModelo);
        }
        return List.copyOf(consolidado.values());
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
