package com.crm.odontologico.kassar.odontograma.application.validation;

import com.crm.odontologico.kassar.odontograma.domain.model.ArcadaDental;
import com.crm.odontologico.kassar.odontograma.domain.model.CatalogoFdi;
import com.crm.odontologico.kassar.odontograma.domain.model.CatalogoFdi.DienteFdiMetadata;
import com.crm.odontologico.kassar.odontograma.domain.model.EstadoClinico;
import com.crm.odontologico.kassar.odontograma.domain.model.GrupoDental;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validador clínico y estructural de la respuesta JSON producida por el modelo de IA.
 * <p>
 * Verifica:
 * <ul>
 *   <li>Sintaxis JSON válida.</li>
 *   <li>Existencia de al menos un diente en {@code dientes} (o {@code diente}).</li>
 *   <li>Números de diente pertenecientes al estándar FDI (32 dientes: 11-18, 21-28, 31-38, 41-48) sin duplicados.</li>
 *   <li>Estados clínicos válidos dentro del catálogo cerrado {@link EstadoClinico}.</li>
 *   <li>Coherencia anatómica (no usar 'oclusal' en dientes anteriores ni 'incisal' en posteriores;
 *       no usar 'palatino' en dientes inferiores ni 'lingual' en superiores).</li>
 *   <li>Coherencia clínica entre el {@code estadoGeneral} (ej. Ausente/Extraído) y las 5 caras del diente.</li>
 * </ul>
 */
@Component
public class AiResponseValidator {

    private final ObjectMapper objectMapper;

    public AiResponseValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationResult validar(String rawAiResponse) {
        List<String> errores = new ArrayList<>();
        List<ValidatedToothDraft> dientesValidados = new ArrayList<>();

        if (rawAiResponse == null || rawAiResponse.isBlank()) {
            errores.add("La respuesta del modelo está vacía. Debes responder exclusivamente con un objeto JSON válido.");
            return new ValidationResult(List.of(), errores);
        }

        String sanitizedJson = limpiarBloquesMarkdown(rawAiResponse);
        JsonNode root;
        try {
            root = objectMapper.readTree(sanitizedJson);
        } catch (JacksonException ex) {
            errores.add("El texto devuelto no es un JSON válido (" + ex.getOriginalMessage()
                    + "). Devuelve únicamente un objeto JSON sin texto adicional.");
            return new ValidationResult(List.of(), errores);
        }

        if (root == null || !root.isObject()) {
            errores.add("La raíz de la respuesta debe ser un objeto JSON con la propiedad 'dientes'.");
            return new ValidationResult(List.of(), errores);
        }

        List<JsonNode> nodosDientes = extraerNodosDientes(root, errores);
        if (nodosDientes.isEmpty()) {
            return new ValidationResult(List.of(), errores);
        }

        Set<Integer> dientesVistos = new HashSet<>();
        for (int idx = 0; idx < nodosDientes.size(); idx++) {
            validarNodoDiente(nodosDientes.get(idx), idx, dientesVistos, dientesValidados, errores);
        }

        return new ValidationResult(dientesValidados, errores);
    }

    /**
     * Valida la respuesta del modelo de IA y además verifica que coincida con todos los dientes
     * y hallazgos detectados en el dictado clínico original.
     */
    public ValidationResult validarContraDictado(
            String rawAiResponse,
            List<ValidatedToothDraft> dientesEsperadosDelDictado
    ) {
        ValidationResult baseResult = validar(rawAiResponse);
        if (!baseResult.isValid() || dientesEsperadosDelDictado == null || dientesEsperadosDelDictado.isEmpty()) {
            return baseResult;
        }

        List<String> errores = new ArrayList<>(baseResult.errores());
        Map<Integer, ValidatedToothDraft> porNumeroEnAi = new LinkedHashMap<>();
        for (ValidatedToothDraft d : baseResult.dientesValidados()) {
            porNumeroEnAi.put(d.metadata().numero(), d);
        }

        List<Integer> faltantes = new ArrayList<>();
        for (ValidatedToothDraft esperado : dientesEsperadosDelDictado) {
            int num = esperado.metadata().numero();
            ValidatedToothDraft recibido = porNumeroEnAi.get(num);
            if (recibido == null) {
                faltantes.add(num);
                continue;
            }
            verificarConcordanciaDeHallazgos(esperado, recibido, errores);
        }

        if (!faltantes.isEmpty()) {
            errores.add("El modelo omitió los siguientes dientes mencionados explícitamente en el dictado: " + faltantes + ".");
        }

        return new ValidationResult(baseResult.dientesValidados(), errores);
    }

    private void verificarConcordanciaDeHallazgos(
            ValidatedToothDraft esperado,
            ValidatedToothDraft recibido,
            List<String> errores
    ) {
        int num = esperado.metadata().numero();
        if (esperado.mesial() != recibido.mesial()) {
            errores.add("Diente " + num + ": cara mesial esperaba '" + esperado.mesial().getEtiqueta()
                    + "' según el dictado, pero el modelo devolvió '" + recibido.mesial().getEtiqueta() + "'.");
        }
        if (esperado.distal() != recibido.distal()) {
            errores.add("Diente " + num + ": cara distal esperaba '" + esperado.distal().getEtiqueta()
                    + "' según el dictado, pero el modelo devolvió '" + recibido.distal().getEtiqueta() + "'.");
        }
        if (esperado.vestibular() != recibido.vestibular()) {
            errores.add("Diente " + num + ": cara vestibular esperaba '" + esperado.vestibular().getEtiqueta()
                    + "' según el dictado, pero el modelo devolvió '" + recibido.vestibular().getEtiqueta() + "'.");
        }
        if (esperado.palatinoLingual() != recibido.palatinoLingual()) {
            errores.add("Diente " + num + ": cara " + esperado.metadata().etiquetaCaraInterna()
                    + " esperaba '" + esperado.palatinoLingual().getEtiqueta()
                    + "' según el dictado, pero el modelo devolvió '" + recibido.palatinoLingual().getEtiqueta() + "'.");
        }
        if (esperado.oclusalIncisal() != recibido.oclusalIncisal()) {
            errores.add("Diente " + num + ": cara " + esperado.metadata().etiquetaCaraMasticatoria()
                    + " esperaba '" + esperado.oclusalIncisal().getEtiqueta()
                    + "' según el dictado, pero el modelo devolvió '" + recibido.oclusalIncisal().getEtiqueta() + "'.");
        }
    }

    private List<JsonNode> extraerNodosDientes(JsonNode root, List<String> errores) {
        JsonNode dientesNode = root.get("dientes");
        if (dientesNode != null && dientesNode.isArray()) {
            if (dientesNode.isEmpty()) {
                errores.add("El arreglo 'dientes' está vacío. Debe incluir al menos un diente interpretado del dictado.");
                return List.of();
            }
            List<JsonNode> list = new ArrayList<>();
            dientesNode.forEach(list::add);
            return list;
        }

        // Soporte tolerante si el modelo devuelve un único objeto bajo "diente"
        JsonNode dienteUnicoNode = root.get("diente");
        if (dienteUnicoNode != null && dienteUnicoNode.isObject()) {
            return List.of(dienteUnicoNode);
        }

        // Soporte tolerante si el modelo (ej. odontograma-llama) devuelve el diente directamente en la raíz
        if (root.has("numero") || root.has("numeroDiente")) {
            return List.of(root);
        }

        errores.add("El JSON debe contener el arreglo 'dientes' con los objetos de cada diente mencionado.");
        return List.of();
    }

    private void validarNodoDiente(
            JsonNode dienteNode,
            int index,
            Set<Integer> dientesVistos,
            List<ValidatedToothDraft> dientesValidados,
            List<String> errores
    ) {
        String prefijo = "dientes[" + index + "]";
        if (dienteNode == null || !dienteNode.isObject()) {
            errores.add(prefijo + " debe ser un objeto JSON válido.");
            return;
        }

        JsonNode numeroNode = dienteNode.get("numero");
        if (numeroNode == null || numeroNode.isNull()) {
            numeroNode = dienteNode.get("numeroDiente");
        }
        if (numeroNode == null || !numeroNode.canConvertToInt()) {
            errores.add(prefijo + ".numero es obligatorio y debe ser un número entero FDI (ej. 15, 21, 36).");
            return;
        }

        int numero = numeroNode.asInt();
        Optional<DienteFdiMetadata> metadataOpt = CatalogoFdi.buscarPorNumero(numero);
        if (metadataOpt.isEmpty()) {
            errores.add("El número de diente " + numero + " en " + prefijo
                    + " NO existe en la nomenclatura FDI de 32 dientes permanentes. "
                    + "Rangos permitidos: 11-18, 21-28, 31-38 y 41-48.");
            return;
        }

        if (!dientesVistos.add(numero)) {
            errores.add("El diente " + numero + " aparece duplicado en la respuesta. Consolida todos los hallazgos del diente "
                    + numero + " en un único elemento dentro de 'dientes'.");
            return;
        }

        DienteFdiMetadata metadata = metadataOpt.get();
        int erroresAntesDelDiente = errores.size();

        EstadoClinico estadoGeneral = extraerEstadoGeneral(dienteNode.get("estadoGeneral"), numero, errores);

        JsonNode carasNode = dienteNode.get("caras");
        if (carasNode == null || !carasNode.isObject()) {
            if (estadoGeneral != null && estadoGeneral.isInhabilitaCarasIndividuales()) {
                // Si el diente completo está Ausente/Extraído/Retenido/Implante y el modelo omitió caras,
                // asignamos el mismo estado general a sus 5 caras de manera consistente.
                dientesValidados.add(new ValidatedToothDraft(
                        metadata,
                        estadoGeneral,
                        estadoGeneral,
                        estadoGeneral,
                        estadoGeneral,
                        estadoGeneral,
                        estadoGeneral
                ));
                return;
            }
            errores.add("Diente " + numero + ": falta el objeto 'caras' con las 5 superficies dentales.");
            return;
        }

        validarLlavesAnatomicas(carasNode, metadata, errores);

        EstadoClinico mesial = extraerEstadoCara(carasNode, "mesial", null, metadata, "Mesial", estadoGeneral, errores);
        EstadoClinico distal = extraerEstadoCara(carasNode, "distal", null, metadata, "Distal", estadoGeneral, errores);
        EstadoClinico vestibular = extraerEstadoCara(carasNode, "vestibular", null, metadata, "Vestibular", estadoGeneral, errores);

        String aliasInterna = metadata.arcada() == ArcadaDental.SUPERIOR ? "palatino" : "lingual";
        EstadoClinico palatinoLingual = extraerEstadoCara(
                carasNode, "palatino_lingual", aliasInterna, metadata, metadata.etiquetaCaraInterna(), estadoGeneral, errores);

        String aliasMasticatoria = metadata.grupo() == GrupoDental.ANTERIOR ? "incisal" : "oclusal";
        EstadoClinico oclusalIncisal = extraerEstadoCara(
                carasNode, "oclusal_incisal", aliasMasticatoria, metadata, metadata.etiquetaCaraMasticatoria(), estadoGeneral, errores);

        if (errores.size() == erroresAntesDelDiente && estadoGeneral != null) {
            validarCoherenciaClinica(
                    metadata, estadoGeneral, mesial, distal, vestibular, palatinoLingual, oclusalIncisal, errores);
        }

        if (errores.size() == erroresAntesDelDiente) {
            EstadoClinico estadoGeneralNormalizado = normalizarEstadoGeneralSegunCaras(
                    estadoGeneral, mesial, distal, vestibular, palatinoLingual, oclusalIncisal);
            dientesValidados.add(new ValidatedToothDraft(
                    metadata,
                    estadoGeneralNormalizado,
                    mesial,
                    distal,
                    vestibular,
                    palatinoLingual,
                    oclusalIncisal
            ));
        }
    }

    private EstadoClinico extraerEstadoGeneral(JsonNode estadoGeneralNode, int numeroDiente, List<String> errores) {
        if (estadoGeneralNode == null || estadoGeneralNode.isNull()) {
            return EstadoClinico.PRESENTE;
        }

        String textoEstado = null;
        if (estadoGeneralNode.isString()) {
            textoEstado = estadoGeneralNode.asString();
        } else if (estadoGeneralNode.isObject() && estadoGeneralNode.hasNonNull("estado")) {
            textoEstado = estadoGeneralNode.get("estado").asString();
        }

        if (textoEstado == null || textoEstado.isBlank()) {
            errores.add("Diente " + numeroDiente + ": 'estadoGeneral' tiene un formato inválido.");
            return null;
        }

        Optional<EstadoClinico> estadoOpt = EstadoClinico.fromTexto(textoEstado);
        if (estadoOpt.isEmpty()) {
            errores.add("Diente " + numeroDiente + ": estadoGeneral '" + textoEstado
                    + "' no es válido. Estados clínicos permitidos: ["
                    + EstadoClinico.valoresPermitidosComoTexto() + "].");
            return null;
        }
        return estadoOpt.get();
    }

    private void validarLlavesAnatomicas(JsonNode carasNode, DienteFdiMetadata metadata, List<String> errores) {
        int numero = metadata.numero();

        // Validación Oclusal vs Incisal según grupo dental
        if (metadata.esAnterior() && carasNode.has("oclusal")) {
            errores.add("Error anatómico en diente " + numero + " (" + metadata.nombreColoquial()
                    + "): es un diente ANTERIOR, por lo que tiene borde 'Incisal' y NO cara 'Oclusal'. "
                    + "Usa la propiedad 'oclusal_incisal'.");
        }
        if (metadata.esPosterior() && carasNode.has("incisal")) {
            errores.add("Error anatómico en diente " + numero + " (" + metadata.nombreColoquial()
                    + "): es un diente POSTERIOR, por lo que tiene cara 'Oclusal' y NO borde 'Incisal'. "
                    + "Usa la propiedad 'oclusal_incisal'.");
        }

        // Validación Palatino vs Lingual según arcada
        if (metadata.arcada() == ArcadaDental.SUPERIOR && carasNode.has("lingual")) {
            errores.add("Error anatómico en diente " + numero + " (" + metadata.nombreColoquial()
                    + "): pertenece a la arcada SUPERIOR, por lo que su cara interna es 'Palatino' y NO 'Lingual'. "
                    + "Usa la propiedad 'palatino_lingual'.");
        }
        if (metadata.arcada() == ArcadaDental.INFERIOR && carasNode.has("palatino")) {
            errores.add("Error anatómico en diente " + numero + " (" + metadata.nombreColoquial()
                    + "): pertenece a la arcada INFERIOR, por lo que su cara interna es 'Lingual' y NO 'Palatino'. "
                    + "Usa la propiedad 'palatino_lingual'.");
        }
    }

    private EstadoClinico extraerEstadoCara(
            JsonNode carasNode,
            String propiedadPrincipal,
            String propiedadAlias,
            DienteFdiMetadata metadata,
            String etiquetaAnatomicaEsperada,
            EstadoClinico estadoGeneral,
            List<String> errores
    ) {
        JsonNode caraNode = carasNode.get(propiedadPrincipal);
        if (caraNode == null && propiedadAlias != null) {
            caraNode = carasNode.get(propiedadAlias);
        }

        int numero = metadata.numero();
        if (caraNode == null || caraNode.isNull()) {
            if (estadoGeneral != null && estadoGeneral.isInhabilitaCarasIndividuales()) {
                return estadoGeneral;
            }
            errores.add("Diente " + numero + ": falta especificar el estado de la cara '"
                    + propiedadPrincipal + "' (" + etiquetaAnatomicaEsperada + ").");
            return null;
        }

        String textoEstado = null;
        if (caraNode.isString()) {
            textoEstado = caraNode.asString();
        } else if (caraNode.isObject()) {
            if (caraNode.hasNonNull("etiquetaVisual")) {
                String etiquetaEnviada = caraNode.get("etiquetaVisual").asString().trim();
                validarEtiquetaVisualEnviada(etiquetaEnviada, etiquetaAnatomicaEsperada, metadata, propiedadPrincipal, errores);
            }
            if (caraNode.hasNonNull("estado")) {
                textoEstado = caraNode.get("estado").asString();
            }
        }

        if (textoEstado == null || textoEstado.isBlank()) {
            errores.add("Diente " + numero + ": la cara '" + propiedadPrincipal + "' no contiene un campo 'estado' válido.");
            return null;
        }

        Optional<EstadoClinico> estadoOpt = EstadoClinico.fromTexto(textoEstado);
        if (estadoOpt.isEmpty()) {
            errores.add("Diente " + numero + ", cara '" + propiedadPrincipal + "': el estado '" + textoEstado
                    + "' no existe. Valores permitidos: [" + EstadoClinico.valoresPermitidosComoTexto() + "].");
            return null;
        }

        return estadoOpt.get();
    }

    private void validarEtiquetaVisualEnviada(
            String etiquetaEnviada,
            String etiquetaEsperada,
            DienteFdiMetadata metadata,
            String propiedadCara,
            List<String> errores
    ) {
        if (!etiquetaEnviada.equalsIgnoreCase(etiquetaEsperada)) {
            errores.add("Error anatómico en diente " + metadata.numero() + " (" + metadata.nombreColoquial()
                    + "), cara '" + propiedadCara + "': se indicó etiquetaVisual '" + etiquetaEnviada
                    + "', pero anatómicamente corresponde a '" + etiquetaEsperada + "'.");
        }
    }

    private void validarCoherenciaClinica(
            DienteFdiMetadata metadata,
            EstadoClinico estadoGeneral,
            EstadoClinico mesial,
            EstadoClinico distal,
            EstadoClinico vestibular,
            EstadoClinico palatinoLingual,
            EstadoClinico oclusalIncisal,
            List<String> errores
    ) {
        int numero = metadata.numero();
        List<CaraEstadoPar> caras = List.of(
                new CaraEstadoPar("mesial", mesial),
                new CaraEstadoPar("distal", distal),
                new CaraEstadoPar("vestibular", vestibular),
                new CaraEstadoPar("palatino_lingual", palatinoLingual),
                new CaraEstadoPar("oclusal_incisal", oclusalIncisal)
        );

        if (estadoGeneral.isInhabilitaCarasIndividuales()) {
            for (CaraEstadoPar par : caras) {
                if (par.estado() != estadoGeneral
                        && par.estado() != EstadoClinico.SANO
                        && par.estado() != EstadoClinico.PRESENTE) {
                    errores.add("Contradicción clínica en diente " + numero
                            + ": el diente tiene estadoGeneral '" + estadoGeneral.getEtiqueta()
                            + "', por lo tanto la cara '" + par.nombreCara()
                            + "' no puede tener estado '" + par.estado().getEtiqueta() + "'.");
                }
            }
        } else {
            for (CaraEstadoPar par : caras) {
                if (par.estado().isInhabilitaCarasIndividuales()) {
                    errores.add("Contradicción clínica en diente " + numero
                            + ": el estado '" + par.estado().getEtiqueta()
                            + "' aplica únicamente al diente completo ('estadoGeneral'), no a una cara individual ('"
                            + par.nombreCara() + "').");
                }
            }
        }
    }

    private EstadoClinico normalizarEstadoGeneralSegunCaras(
            EstadoClinico estadoGeneral,
            EstadoClinico mesial,
            EstadoClinico distal,
            EstadoClinico vestibular,
            EstadoClinico palatinoLingual,
            EstadoClinico oclusalIncisal
    ) {
        if (estadoGeneral == null) {
            return EstadoClinico.PRESENTE;
        }
        if (estadoGeneral.isInhabilitaCarasIndividuales() || estadoGeneral == EstadoClinico.CORONA) {
            return estadoGeneral;
        }
        boolean todasIguales = (mesial == distal)
                && (distal == vestibular)
                && (vestibular == palatinoLingual)
                && (palatinoLingual == oclusalIncisal);

        // Si solo algunas caras tienen patología y otras están sanas, el estado general del diente es Presente
        if (!todasIguales) {
            return EstadoClinico.PRESENTE;
        }
        return estadoGeneral;
    }

    private String limpiarBloquesMarkdown(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewLine = trimmed.indexOf('\n');
            if (firstNewLine != -1) {
                trimmed = trimmed.substring(firstNewLine + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }

    private record CaraEstadoPar(String nombreCara, EstadoClinico estado) {
    }
}
