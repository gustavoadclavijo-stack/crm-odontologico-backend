package com.crm.odontologico.kassar.odontograma;

import com.crm.odontologico.kassar.odontograma.application.port.OdontogramaAiPort;
import com.crm.odontologico.kassar.odontograma.infrastructure.ai.ollama.OllamaAiAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest(properties = "odontograma.ai.provider=ollama")
class OllamaProviderSelectionTest {

    @Autowired
    private OdontogramaAiPort aiPort;

    @Test
    void debeSeleccionarOllamaSinNecesitarLaClaveDeGoogle() {
        assertInstanceOf(OllamaAiAdapter.class, aiPort);
    }
}