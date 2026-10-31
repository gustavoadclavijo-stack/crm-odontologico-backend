package com.crm.odontologico.kassar.odontograma.infrastructure.ai.google;

import com.crm.odontologico.kassar.odontograma.application.port.AiChatMessage;
import com.crm.odontologico.kassar.odontograma.application.port.OdontogramaAiPort;
import com.crm.odontologico.kassar.odontograma.domain.exception.AiProviderCommunicationException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/** Spring AI adapter for Google AI Studio's Gemini chat model. */
@Component
@ConditionalOnProperty(prefix = "odontograma.ai", name = "provider", havingValue = "google-genai", matchIfMissing = true)
public class GoogleGenAiAdapter implements OdontogramaAiPort {

    private static final Logger log = LoggerFactory.getLogger(GoogleGenAiAdapter.class);
    private static final Pattern API_KEY_HEADER_PATTERN = Pattern.compile(
        "(?i)(x-goog-api-key(?:\\s+value)?\\s*:\\s*)[^\\r\\n]+"
    );

    private final ChatModel chatModel;
    private final String apiKey;

    public GoogleGenAiAdapter(
        ChatModel chatModel,
        @Value("${spring.ai.google.genai.api-key:}") String apiKey
    ) {
        this.chatModel = chatModel;
    this.apiKey = apiKey;
    }

    @Override
    public String generarRespuestaJson(List<AiChatMessage> messages) {
        List<Message> springAiMessages = messages.stream()
                .map(this::toSpringAiMessage)
                .toList();

        try {
            ChatResponse response = chatModel.call(new Prompt(springAiMessages));
            if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null
                    || response.getResult().getOutput().getText() == null
                    || response.getResult().getOutput().getText().isBlank()) {
                throw new AiProviderCommunicationException(
                        "Google AI Studio devolvió una respuesta vacía.");
            }
            return response.getResult().getOutput().getText();
        } catch (AiProviderCommunicationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String safeMessage = redactSensitiveData(ex.getMessage());
            log.error("Falló la llamada a Google AI Studio. Causas: {}", summarizeCauses(ex));
            throw new AiProviderCommunicationException(
                    "Error al comunicarse con Google AI Studio (Gemini): " + safeMessage, ex);
        }
    }

    private String summarizeCauses(Throwable exception) {
        StringBuilder summary = new StringBuilder();
        Throwable cause = exception;
        while (cause != null) {
            if (!summary.isEmpty()) {
                summary.append(" <- ");
            }
            summary.append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                summary.append(": ").append(redactSensitiveData(cause.getMessage()));
            }
            cause = cause.getCause();
        }
        return summary.toString();
    }

    private String redactSensitiveData(String value) {
        if (value == null) {
            return "sin detalle";
        }
        String redacted = apiKey == null || apiKey.isBlank()
                ? value
                : value.replace(apiKey, "[REDACTED]");
        return API_KEY_HEADER_PATTERN.matcher(redacted).replaceAll("$1[REDACTED]");
    }

    private Message toSpringAiMessage(AiChatMessage message) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.content());
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> new AssistantMessage(message.content());
        };
    }
}