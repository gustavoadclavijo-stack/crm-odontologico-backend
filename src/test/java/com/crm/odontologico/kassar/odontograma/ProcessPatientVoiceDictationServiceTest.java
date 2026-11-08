package com.crm.odontologico.kassar.odontograma;

import com.crm.odontologico.kassar.odontograma.application.dto.PatientVoiceCaptureRequest;
import com.crm.odontologico.kassar.odontograma.application.dto.PatientVoiceCaptureResponse;
import com.crm.odontologico.kassar.odontograma.application.port.AiChatMessage;
import com.crm.odontologico.kassar.odontograma.application.port.OdontogramaAiPort;
import com.crm.odontologico.kassar.odontograma.application.service.ProcessPatientVoiceDictationService;
import com.crm.odontologico.kassar.odontograma.domain.exception.AiProviderCommunicationException;
import com.crm.odontologico.kassar.odontograma.domain.exception.InvalidVoiceCaptureRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessPatientVoiceDictationServiceTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
    }

    @Test
    void debeExtraerTodosLosCamposEIgnorarInstruccionesEnElDictado() {
        AtomicReference<List<AiChatMessage>> messagesSent = new AtomicReference<>();
        OdontogramaAiPort aiPort = messages -> {
            messagesSent.set(messages);
            return """
                    {
                      "nombres": "María José",
                      "apellidos": "López Pérez",
                      "identificacion": "123456789",
                      "edad": "34",
                      "genero": "Femenino",
                      "telefono": "3001234567",
                      "correo": "maria@example.com",
                      "direccion": "Calle 10 # 5-20",
                      "ocupacion": "Profesora",
                      "aseguradora": "Plan dental",
                      "contactoEmergencia": "Ana López, hermana, 3007654321",
                      "alergias": "Penicilina",
                      "medicamentos": "Ninguno",
                      "antecedentesMedicos": "Hipertensión",
                      "antecedentesOdontologicos": "Ortodoncia previa"
                    }
                    """;
        };
        ProcessPatientVoiceDictationService service =
                new ProcessPatientVoiceDictationService(aiPort, objectMapper);

        PatientVoiceCaptureResponse response = service.procesarDictado(
                new PatientVoiceCaptureRequest("María José López Pérez, 34 años. Alergia a penicilina.")
        );

        assertEquals("María José", response.nombres());
        assertEquals("López Pérez", response.apellidos());
        assertEquals("123456789", response.identificacion());
        assertEquals("34", response.edad());
        assertEquals("Femenino", response.genero());
        assertEquals("3001234567", response.telefono());
        assertEquals("maria@example.com", response.correo());
        assertEquals("Calle 10 # 5-20", response.direccion());
        assertEquals("Profesora", response.ocupacion());
        assertEquals("Plan dental", response.aseguradora());
        assertEquals("Ana López, hermana, 3007654321", response.contactoEmergencia());
        assertEquals("Penicilina", response.alergias());
        assertEquals("Ninguno", response.medicamentos());
        assertEquals("Hipertensión", response.antecedentesMedicos());
        assertEquals("Ortodoncia previa", response.antecedentesOdontologicos());
        assertEquals(2, messagesSent.get().size());
        assertTrue(messagesSent.get().get(0).content().contains("No inventes"));
        assertEquals("user", messagesSent.get().get(1).role().getValue());
    }

    @Test
    void debeDevolverVaciosParaLosCamposNoMencionados() {
        OdontogramaAiPort aiPort = messages -> """
                {
                  "nombres": "Luis",
                  "apellidos": "",
                  "identificacion": "",
                  "edad": "",
                  "genero": "",
                  "telefono": "",
                  "correo": "",
                  "direccion": "",
                  "ocupacion": "",
                  "aseguradora": "",
                  "contactoEmergencia": "",
                  "alergias": "",
                  "medicamentos": "",
                  "antecedentesMedicos": "",
                  "antecedentesOdontologicos": ""
                }
                """;
        ProcessPatientVoiceDictationService service =
                new ProcessPatientVoiceDictationService(aiPort, objectMapper);

        PatientVoiceCaptureResponse response =
                service.procesarDictado(new PatientVoiceCaptureRequest("El paciente se llama Luis."));

        assertEquals("Luis", response.nombres());
        assertEquals("", response.apellidos());
        assertEquals("", response.antecedentesOdontologicos());
    }

    @Test
    void debeRechazarTranscripcionVaciaOMuyLarga() {
        ProcessPatientVoiceDictationService service =
                new ProcessPatientVoiceDictationService(messages -> "{}", objectMapper);

        assertThrows(
                InvalidVoiceCaptureRequestException.class,
                () -> service.procesarDictado(new PatientVoiceCaptureRequest(" "))
        );
        assertThrows(
                InvalidVoiceCaptureRequestException.class,
                () -> service.procesarDictado(new PatientVoiceCaptureRequest("a".repeat(10_001)))
        );
    }

    @Test
    void debeRechazarRespuestaDeIaQueNoSeaJsonEstructurado() {
        ProcessPatientVoiceDictationService service =
                new ProcessPatientVoiceDictationService(messages -> "no es JSON", objectMapper);

        assertThrows(
                AiProviderCommunicationException.class,
                () -> service.procesarDictado(new PatientVoiceCaptureRequest("Nombre: Ana."))
        );
    }
}
