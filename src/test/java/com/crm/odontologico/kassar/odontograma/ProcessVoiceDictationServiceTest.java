package com.crm.odontologico.kassar.odontograma;

import com.crm.odontologico.kassar.odontograma.application.dto.DienteData;
import com.crm.odontologico.kassar.odontograma.application.dto.OdontogramaUpdateResponse;
import com.crm.odontologico.kassar.odontograma.application.dto.VoiceCaptureRequest;
import com.crm.odontologico.kassar.odontograma.application.mapper.OdontogramaDomainMapper;
import com.crm.odontologico.kassar.odontograma.application.port.AiChatMessage;
import com.crm.odontologico.kassar.odontograma.application.port.OdontogramaAiPort;
import com.crm.odontologico.kassar.odontograma.application.service.OdontogramaPromptBuilder;
import com.crm.odontologico.kassar.odontograma.application.service.ProcessVoiceDictationService;
import com.crm.odontologico.kassar.odontograma.application.validation.AiResponseValidator;
import com.crm.odontologico.kassar.odontograma.domain.exception.AiValidationExhaustedException;
import com.crm.odontologico.kassar.odontograma.domain.model.EstadoClinico;
import com.crm.odontologico.kassar.odontograma.domain.model.GrupoDental;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessVoiceDictationServiceTest {

    private ObjectMapper objectMapper;
    private AiResponseValidator validator;
    private OdontogramaDomainMapper mapper;
    private OdontogramaPromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        validator = new AiResponseValidator(objectMapper);
        mapper = new OdontogramaDomainMapper();
        promptBuilder = new OdontogramaPromptBuilder();
    }

    @Test
    @DisplayName("Debe procesar el formato envuelto en 'prompt' para el diente 15 y enriquecer anatomía FDI y colores")
    void debeProcesarPeticionEnvueltaEnPromptParaDiente15() {
        OdontogramaAiPort fakeAiPort = messages -> """
                {
                  "dientes": [
                    {
                      "numero": 15,
                      "estadoGeneral": { "estado": "Presente" },
                      "caras": {
                        "mesial": { "estado": "Sano" },
                        "distal": { "estado": "Sano" },
                        "vestibular": { "estado": "Sano" },
                        "palatino_lingual": { "estado": "Sano" },
                        "oclusal_incisal": { "estado": "Caries" }
                      }
                    }
                  ]
                }
                """;

        ProcessVoiceDictationService service = new ProcessVoiceDictationService(
                fakeAiPort, promptBuilder, validator, mapper, objectMapper, 3
        );

        VoiceCaptureRequest request = new VoiceCaptureRequest(
                null,
                null,
                "{\"odontogramaId\": \"a1b2c3d4-5678\", \"textoCapturado\": \"diente 15 distal y vestibular sano, oclusal caries, el resto sano.\"}"
        );

        OdontogramaUpdateResponse response = service.procesarDictado(request);

        assertEquals("a1b2c3d4-5678", response.odontogramaId());
        assertEquals(1, response.dientes().size());

        DienteData diente15 = response.dientes().get(0);
        assertEquals(15, diente15.numero());
        assertEquals("Segundo premolar superior derecho", diente15.nombreColoquial());
        assertEquals(1, diente15.cuadrante());
        assertEquals(GrupoDental.POSTERIOR, diente15.grupoDental());
        assertEquals(EstadoClinico.PRESENTE, diente15.estadoGeneral().estado());
        assertNull(diente15.estadoGeneral().colorHex());

        // Verifica etiquetas anatómicas exactas para superior posterior (Palatino + Oclusal)
        assertEquals("Palatino", diente15.caras().palatinoLingual().etiquetaVisual());
        assertEquals(EstadoClinico.SANO, diente15.caras().palatinoLingual().estado());
        assertEquals("#22C55E", diente15.caras().palatinoLingual().colorHex());

        assertEquals("Oclusal", diente15.caras().oclusalIncisal().etiquetaVisual());
        assertEquals(EstadoClinico.CARIES, diente15.caras().oclusalIncisal().estado());
        assertEquals("#EF4444", diente15.caras().oclusalIncisal().colorHex());
    }

    @Test
    @DisplayName("Debe distinguir Lingual e Incisal en dientes anteriores inferiores y soportar múltiples dientes")
    void debeSoportarMultiplesDientesConAnatomiaFdiExacta() {
        OdontogramaAiPort fakeAiPort = messages -> """
                {
                  "dientes": [
                    {
                      "numero": 31,
                      "estadoGeneral": "Presente",
                      "caras": {
                        "mesial": "Sano",
                        "distal": "Sano",
                        "vestibular": "Restaurado",
                        "palatino_lingual": "Sano",
                        "oclusal_incisal": "Fracturado"
                      }
                    },
                    {
                      "numero": 46,
                      "estadoGeneral": "Ausente"
                    }
                  ]
                }
                """;

        ProcessVoiceDictationService service = new ProcessVoiceDictationService(
                fakeAiPort, promptBuilder, validator, mapper, objectMapper, 3
        );

        VoiceCaptureRequest request = new VoiceCaptureRequest(
                "odo-999",
                "incisivo central inferior izquierdo vestibular restaurado, borde incisal fracturado, y primer molar inferior derecho ausente"
        );

        OdontogramaUpdateResponse response = service.procesarDictado(request);

        assertEquals(2, response.dientes().size());

        DienteData diente31 = response.dientes().get(0);
        assertEquals(31, diente31.numero());
        assertEquals("Incisivo central inferior izquierdo", diente31.nombreColoquial());
        assertEquals(GrupoDental.ANTERIOR, diente31.grupoDental());
        assertEquals("Lingual", diente31.caras().palatinoLingual().etiquetaVisual());
        assertEquals("Incisal", diente31.caras().oclusalIncisal().etiquetaVisual());
        assertEquals(EstadoClinico.FRACTURADO, diente31.caras().oclusalIncisal().estado());

        DienteData diente46 = response.dientes().get(1);
        assertEquals(46, diente46.numero());
        assertEquals("Primer molar inferior derecho", diente46.nombreColoquial());
        assertEquals(EstadoClinico.AUSENTE, diente46.estadoGeneral().estado());
        assertEquals(EstadoClinico.AUSENTE, diente46.caras().oclusalIncisal().estado());
    }

    @Test
    @DisplayName("Debe enviar feedback de error al modelo de IA y autocorregirse cuando el primer intento tiene errores anatómicos o FDI")
    void debeAutocorregirRespuestaCuandoElPrimerIntentoFallaValidacion() {
        AtomicInteger contadorLlamadas = new AtomicInteger(0);
        List<List<AiChatMessage>> historialesRecibidos = new ArrayList<>();

        OdontogramaAiPort selfCorrectingAiPort = messages -> {
            historialesRecibidos.add(List.copyOf(messages));
            int intento = contadorLlamadas.incrementAndGet();
            if (intento == 1) {
                // Intento 1 con error: usa etiqueta 'Oclusal' en un diente anterior (11) y un diente FDI inválido (19)
                return """
                        {
                          "dientes": [
                            {
                              "numero": 19,
                              "estadoGeneral": "Presente",
                              "caras": {
                                "mesial": "Sano",
                                "distal": "Sano",
                                "vestibular": "Sano",
                                "palatino_lingual": "Sano",
                                "oclusal_incisal": "Caries"
                              }
                            },
                            {
                              "numero": 11,
                              "estadoGeneral": "Presente",
                              "caras": {
                                "mesial": { "etiquetaVisual": "Mesial", "estado": "Sano" },
                                "distal": { "etiquetaVisual": "Distal", "estado": "Sano" },
                                "vestibular": { "etiquetaVisual": "Vestibular", "estado": "Sano" },
                                "palatino_lingual": { "etiquetaVisual": "Palatino", "estado": "Sano" },
                                "oclusal_incisal": { "etiquetaVisual": "Oclusal", "estado": "Fracturado" }
                              }
                            }
                          ]
                        }
                        """;
            }
            // Intento 2: el modelo corrige su salida tras recibir el mensaje de validación del backend
            return """
                    {
                      "dientes": [
                        {
                          "numero": 11,
                          "estadoGeneral": { "estado": "Presente" },
                          "caras": {
                            "mesial": { "etiquetaVisual": "Mesial", "estado": "Sano" },
                            "distal": { "etiquetaVisual": "Distal", "estado": "Sano" },
                            "vestibular": { "etiquetaVisual": "Vestibular", "estado": "Sano" },
                            "palatino_lingual": { "etiquetaVisual": "Palatino", "estado": "Sano" },
                            "oclusal_incisal": { "etiquetaVisual": "Incisal", "estado": "Fracturado" }
                          }
                        }
                      ]
                    }
                    """;
        };

        ProcessVoiceDictationService service = new ProcessVoiceDictationService(
                selfCorrectingAiPort, promptBuilder, validator, mapper, objectMapper, 3
        );

        OdontogramaUpdateResponse response = service.procesarDictado(
                new VoiceCaptureRequest("odo-11", "incisivo central superior derecho incisal fracturado, resto sano")
        );

        assertEquals(2, contadorLlamadas.get());
        assertEquals(1, response.dientes().size());
        assertEquals(11, response.dientes().get(0).numero());
        assertEquals("Incisal", response.dientes().get(0).caras().oclusalIncisal().etiquetaVisual());

        // Verifica que en el segundo llamado se haya enviado el feedback con los errores detectados
        List<AiChatMessage> segundoHistorial = historialesRecibidos.get(1);
        assertEquals(4, segundoHistorial.size());
        String feedbackMessage = segundoHistorial.get(3).content();
        assertTrue(feedbackMessage.contains("19"));
        assertTrue(feedbackMessage.contains("Incisal"));
    }

    @Test
    @DisplayName("Debe lanzar AiValidationExhaustedException si el modelo no corrige sus errores tras agotar los intentos")
    void debeLanzarExcepcionCuandoSeAgotanLosIntentosDeCorreccion() {
        OdontogramaAiPort brokenAiPort = messages -> "{ \"dientes\": [ { \"numero\": 99 } ] }";

        ProcessVoiceDictationService service = new ProcessVoiceDictationService(
                brokenAiPort, promptBuilder, validator, mapper, objectMapper, 2
        );

        AiValidationExhaustedException ex = assertThrows(
                AiValidationExhaustedException.class,
                () -> service.procesarDictado(new VoiceCaptureRequest("odo-err", "diente 99 sano"))
        );

        assertEquals(2, ex.getIntentosRealizados());
        assertNotNull(ex.getUltimosErrores());
        assertTrue(ex.getUltimosErrores().get(0).contains("99"));
    }

    @Test
    @DisplayName("Debe extraer y reconciliar los 9 dientes cuando el dictado incluye grupos, sintaxis invertida y el modelo trunca la salida")
    void debeReconciliarLos9DientesEnDictadoComplejoMultiPieza() {
        // Simula la respuesta truncada del modelo local que solo devolvió del 18 al 12 y omitió 11 y 32
        OdontogramaAiPort truncatedAiPort = messages -> """
                {
                  "dientes": [
                    {
                      "numero": 18,
                      "estadoGeneral": "Caries",
                      "caras": {
                        "mesial": "Caries",
                        "distal": "Sano",
                        "vestibular": "Sano",
                        "palatino_lingual": "Sano",
                        "oclusal_incisal": "Caries"
                      }
                    },
                    {
                      "numero": 17,
                      "estadoGeneral": "Sano",
                      "caras": {
                        "mesial": "Sano",
                        "distal": "Sano",
                        "vestibular": "Sano",
                        "palatino_lingual": "Sano",
                        "oclusal_incisal": "Sano"
                      }
                    }
                  ]
                }
                """;

        ProcessVoiceDictationService service = new ProcessVoiceDictationService(
                truncatedAiPort, promptBuilder, validator, mapper, objectMapper, 3
        );

        VoiceCaptureRequest request = new VoiceCaptureRequest(
                "odo-front-002",
                "cliente 18 oclusal caries 17 distal caries 16 15 14 13 y 12 sanos 11 caries en mesial caries en 32 resto de dientes sano"
        );

        OdontogramaUpdateResponse response = service.procesarDictado(request);

        assertEquals(9, response.dientes().size());

        // Diente 18: solo oclusal caries, resto sano, estadoGeneral Presente
        DienteData d18 = response.dientes().get(0);
        assertEquals(18, d18.numero());
        assertEquals(EstadoClinico.PRESENTE, d18.estadoGeneral().estado());
        assertEquals(EstadoClinico.CARIES, d18.caras().oclusalIncisal().estado());
        assertEquals(EstadoClinico.SANO, d18.caras().mesial().estado());

        // Diente 17: distal caries, resto sano
        DienteData d17 = response.dientes().get(1);
        assertEquals(17, d17.numero());
        assertEquals(EstadoClinico.PRESENTE, d17.estadoGeneral().estado());
        assertEquals(EstadoClinico.CARIES, d17.caras().distal().estado());
        assertEquals(EstadoClinico.SANO, d17.caras().oclusalIncisal().estado());

        // Dientes 16, 15, 14, 13, 12: sanos
        for (int idx = 2; idx <= 6; idx++) {
            DienteData sano = response.dientes().get(idx);
            assertEquals(EstadoClinico.SANO, sano.estadoGeneral().estado());
            assertEquals("#22C55E", sano.estadoGeneral().colorHex());
        }

        // Diente 11: caries en mesial, resto sano
        DienteData d11 = response.dientes().get(7);
        assertEquals(11, d11.numero());
        assertEquals(EstadoClinico.PRESENTE, d11.estadoGeneral().estado());
        assertEquals(EstadoClinico.CARIES, d11.caras().mesial().estado());
        assertEquals("Incisal", d11.caras().oclusalIncisal().etiquetaVisual());

        // Diente 32: caries en 32 (Incisivo lateral inferior izquierdo: Lingual + Incisal)
        DienteData d32 = response.dientes().get(8);
        assertEquals(32, d32.numero());
        assertEquals(EstadoClinico.CARIES, d32.estadoGeneral().estado());
        assertEquals("Lingual", d32.caras().palatinoLingual().etiquetaVisual());
        assertEquals("Incisal", d32.caras().oclusalIncisal().etiquetaVisual());
        assertEquals(EstadoClinico.CARIES, d32.caras().oclusalIncisal().estado());
    }

    @Test
    @DisplayName("Debe soportar de forma genérica hasta los 32 dientes FDI con rangos y múltiples hallazgos por pieza")
    void debeSoportarLos32DientesFdiConRangosYMultiplesHallazgos() {
        OdontogramaAiPort partialAiPort = messages -> """
                {
                  "dientes": [
                    { "numero": 48, "estadoGeneral": "Ausente" }
                  ]
                }
                """;

        ProcessVoiceDictationService service = new ProcessVoiceDictationService(
                partialAiPort, promptBuilder, validator, mapper, objectMapper, 3
        );

        VoiceCaptureRequest request = new VoiceCaptureRequest(
                "odo-32-full",
                "diente 11 incisal fracturado y mesial caries, del 12 al 18 sanos, del 21 al 28 sanos, del 31 al 38 sanos, del 41 al 47 sanos, diente 48 ausente"
        );

        OdontogramaUpdateResponse response = service.procesarDictado(request);

        assertEquals(32, response.dientes().size());
        assertEquals(11, response.dientes().get(0).numero());
        assertEquals(EstadoClinico.FRACTURADO, response.dientes().get(0).caras().oclusalIncisal().estado());
        assertEquals(EstadoClinico.CARIES, response.dientes().get(0).caras().mesial().estado());
        assertEquals(48, response.dientes().get(31).numero());
        assertEquals(EstadoClinico.AUSENTE, response.dientes().get(31).estadoGeneral().estado());
    }
}
