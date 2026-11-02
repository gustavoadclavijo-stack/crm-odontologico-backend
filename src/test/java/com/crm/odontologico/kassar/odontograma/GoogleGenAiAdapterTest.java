package com.crm.odontologico.kassar.odontograma;

import com.crm.odontologico.kassar.odontograma.application.port.AiChatMessage;
import com.crm.odontologico.kassar.odontograma.domain.exception.AiProviderCommunicationException;
import com.crm.odontologico.kassar.odontograma.infrastructure.ai.google.GoogleGenAiAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GoogleGenAiAdapterTest {

    @Test
    void debeTraducirHistorialAlFormatoSpringAiYRetornarLaRespuestaDelModelo() {
        String json = "{\"dientes\":[]}";
        AtomicReference<Prompt> promptRecibido = new AtomicReference<>();
        ChatModel chatModel = prompt -> {
            promptRecibido.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
        };
        GoogleGenAiAdapter adapter = new GoogleGenAiAdapter(chatModel, "test-api-key");

        String response = adapter.generarRespuestaJson(List.of(
                AiChatMessage.system("Devuelve JSON."),
                AiChatMessage.user("Diente 11 sano."),
                AiChatMessage.assistant("{\"dientes\":[]}")
        ));

        assertEquals(json, response);
        List<Message> instructions = promptRecibido.get().getInstructions();
        assertEquals(3, instructions.size());
        assertInstanceOf(SystemMessage.class, instructions.get(0));
        assertEquals("Devuelve JSON.", instructions.get(0).getText());
        assertInstanceOf(UserMessage.class, instructions.get(1));
        assertEquals("Diente 11 sano.", instructions.get(1).getText());
        assertInstanceOf(AssistantMessage.class, instructions.get(2));
        assertEquals("{\"dientes\":[]}", instructions.get(2).getText());
    }

    @Test
    void debeConservarLaCausaOriginalCuandoFallaSpringAi() {
        String apiKey = "test-api-key";
        RuntimeException sdkFailure = new RuntimeException(
            "Unexpected char 0x1b in x-goog-api-key value: " + apiKey);
        ChatModel chatModel = prompt -> {
            throw sdkFailure;
        };
        GoogleGenAiAdapter adapter = new GoogleGenAiAdapter(chatModel, apiKey);

        AiProviderCommunicationException exception = assertThrows(
                AiProviderCommunicationException.class,
                () -> adapter.generarRespuestaJson(List.of(AiChatMessage.user("Prueba")))
        );

        assertSame(sdkFailure, exception.getCause());
        org.junit.jupiter.api.Assertions.assertFalse(exception.getMessage().contains(apiKey));
        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("[REDACTED]"));
    }
}