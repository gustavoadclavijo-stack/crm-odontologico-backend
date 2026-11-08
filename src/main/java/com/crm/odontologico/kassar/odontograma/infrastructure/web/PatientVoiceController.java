package com.crm.odontologico.kassar.odontograma.infrastructure.web;

import com.crm.odontologico.kassar.odontograma.application.dto.PatientVoiceCaptureRequest;
import com.crm.odontologico.kassar.odontograma.application.dto.PatientVoiceCaptureResponse;
import com.crm.odontologico.kassar.odontograma.application.service.ProcessPatientVoiceDictationService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint para estructurar con IA la transcripción de voz de los datos de un paciente.
 */
@RestController
@RequestMapping("/api/v1/pacientes")
@CrossOrigin(origins = "*")
public class PatientVoiceController {

    private final ProcessPatientVoiceDictationService processPatientVoiceDictationService;

    public PatientVoiceController(ProcessPatientVoiceDictationService processPatientVoiceDictationService) {
        this.processPatientVoiceDictationService = processPatientVoiceDictationService;
    }

    @PostMapping(
            value = "/voice-capture",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<PatientVoiceCaptureResponse> procesarDictadoPaciente(
            @RequestBody PatientVoiceCaptureRequest request
    ) {
        return ResponseEntity.ok(processPatientVoiceDictationService.procesarDictado(request));
    }
}
