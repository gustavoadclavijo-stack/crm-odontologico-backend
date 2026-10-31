package com.crm.odontologico.kassar.odontograma.infrastructure.ai.ollama;

import com.crm.odontologico.kassar.odontograma.application.port.AiChatMessage;
import com.crm.odontologico.kassar.odontograma.application.port.OdontogramaAiPort;
import com.crm.odontologico.kassar.odontograma.domain.exception.AiProviderCommunicationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Adaptador de infraestructura que implementa {@link OdontogramaAiPort} conectándose
 * a la API de chat de Ollama ({@code POST /api/chat}) con salida forzada a JSON ({@code format: "json"}).
 * <p>
 * Soporta historial multi-turno para enviar retroalimentación al modelo cuando una respuesta
 * falla las validaciones clínicas del Backend.
 */
@Component
@ConditionalOnProperty(prefix = "odontograma.ai", name = "provider", havingValue = "ollama")
public class OllamaAiAdapter implements OdontogramaAiPort {

    private static final Logger log = LoggerFactory.getLogger(OllamaAiAdapter.class);

    private final RestClient ollamaRestClient;
    private final OllamaProperties properties;

    public OllamaAiAdapter(RestClient ollamaRestClient, OllamaProperties properties) {
        this.ollamaRestClient = ollamaRestClient;
        this.properties = properties;
    }

    @Override
    public String generarRespuestaJson(List<AiChatMessage> messages) {
        List<OllamaMessagePayload> ollamaMessages = messages.stream()
                .map(msg -> new OllamaMessagePayload(msg.role().getValue(), msg.content()))
                .toList();

        OllamaChatRequest requestPayload = new OllamaChatRequest(
                properties.model(),
                ollamaMessages,
                "json",
                false,
                Map.of("temperature", properties.temperature())
        );

        try {
            log.debug("Enviando petición a Ollama ({}{}) con modelo '{}' y {} mensaje(s) en el historial",
                    properties.baseUrl(), properties.chatEndpoint(), properties.model(), ollamaMessages.size());

            OllamaChatResponse response = ollamaRestClient.post()
                    .uri(properties.chatEndpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestPayload)
                    .retrieve()
                    .body(OllamaChatResponse.class);

            if (response == null) {
                throw new AiProviderCommunicationException("El servidor Ollama devolvió una respuesta nula.");
            }

            if (response.message() != null && response.message().content() != null) {
                return response.message().content();
            }

            // Fallback compatible en caso de que el servidor responda con campo "response" (estilo /api/generate)
            if (response.response() != null) {
                return response.response();
            }

            throw new AiProviderCommunicationException(
                    "La respuesta de Ollama no contiene el campo 'message.content' ni 'response'.");
        } catch (RestClientException ex) {
            throw new AiProviderCommunicationException(
                    "Error al comunicarse con el servidor de IA Ollama en " + properties.baseUrl()
                            + properties.chatEndpoint() + " (modelo: " + properties.model() + "): " + ex.getMessage(),
                    ex
            );
        }
    }

    public record OllamaChatRequest(
            String model,
            List<OllamaMessagePayload> messages,
            String format,
            boolean stream,
            Map<String, Object> options
    ) {
    }

    public record OllamaMessagePayload(
            String role,
            String content
    ) {
    }

    public record OllamaChatResponse(
            String model,
            OllamaMessagePayload message,
            String response,
            Boolean done
    ) {
    }
}
