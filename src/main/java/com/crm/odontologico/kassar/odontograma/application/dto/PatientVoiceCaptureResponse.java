package com.crm.odontologico.kassar.odontograma.application.dto;

/**
 * Datos del paciente extraídos de un dictado. Los valores no mencionados se devuelven vacíos.
 */
public record PatientVoiceCaptureResponse(
        String nombres,
        String apellidos,
        String identificacion,
        String edad,
        String genero,
        String telefono,
        String correo,
        String direccion,
        String ocupacion,
        String aseguradora,
        String contactoEmergencia,
        String alergias,
        String medicamentos,
        String antecedentesMedicos,
        String antecedentesOdontologicos
) {
}
