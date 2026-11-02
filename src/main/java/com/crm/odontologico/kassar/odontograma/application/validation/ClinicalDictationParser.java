package com.crm.odontologico.kassar.odontograma.application.validation;

import com.crm.odontologico.kassar.odontograma.domain.model.CatalogoFdi;
import com.crm.odontologico.kassar.odontograma.domain.model.CatalogoFdi.DienteFdiMetadata;
import com.crm.odontologico.kassar.odontograma.domain.model.EstadoClinico;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analizador genérico de lenguaje natural clínico odontológico para 1 a 32 dientes FDI.
 * <p>
 * Soporta cualquier combinación y orden de dictado:
 * <ul>
 *   <li>De 1 hasta los 32 dientes permanentes FDI (11-18, 21-28, 31-38, 41-48) en cualquier orden.</li>
 *   <li>Listas agrupadas (ej. "16 15 14 13 y 12 sanos") y rangos (ej. "del 14 al 18 sanos").</li>
 *   <li>Sintaxis directa ("diente 18 oclusal caries"), invertida por cara ("11 caries en mesial")
 *       e invertida por diente ("caries en 32", "caries en mesial del 32").</li>
 *   <li>Múltiples superficies con distintos estados en un mismo diente ("15 oclusal caries, mesial restaurado, resto sano")
 *       y consolidación si un mismo diente es mencionado varias veces durante el dictado.</li>
 * </ul>
 */
@Component
public class ClinicalDictationParser {

    private static final String ESTADOS_REGEX =
            "(caries|sanos?|sanas?|restaurados?|restauradas?|resinas?|amalgamas?|obturados?|obturadas?|obturacion|"
                    + "fracturados?|fracturadas?|fracturas?|ausentes?|extraidos?|extraidas?|extraccion|exodoncia|"
                    + "retenidos?|retenidas?|coronas?|implantes?|sellantes?|presentes?)";

    private static final String CARAS_REGEX =
            "(mesial|distal|vestibular|palatino|palatina|lingual|oclusal|incisal)";

    private static final Pattern RANGO_DIENTES_PATTERN = Pattern.compile(
            "\\b(?:del?\\s+)?(?:diente\\s+)?([1-4][1-8])\\s+(?:al?|hasta(?:\\s+el)?)\\s+(?:el\\s+)?(?:diente\\s+)?([1-4][1-8])\\b"
    );

    /**
     * Detecta expresiones donde el diagnóstico (y opcionalmente la cara) precede al número de diente
     * (ej. "caries en 32", "caries en mesial del 32", "ausente en el 48").
     */
    private static final Pattern ESTADO_EN_DIENTE_PATTERN = Pattern.compile(
            "\\b(" + ESTADOS_REGEX + "(?:\\s+(?:en\\s+)?" + CARAS_REGEX + ")?)\\s+(?:en|del|de)\\s+(?:el\\s+|la\\s+)?(?:diente\\s+|pieza\\s+)?([1-4][1-8])\\b"
    );

    private static final Pattern TOKEN_FDI_PATTERN = Pattern.compile("\\b([1-4][1-8])\\b");

    private static final Pattern TOKEN_CLINICO_PATTERN = Pattern.compile(
            "\\b" + CARAS_REGEX + "\\b|\\b" + ESTADOS_REGEX + "\\b"
    );

    public enum CaraCanonica {
        MESIAL,
        DISTAL,
        VESTIBULAR,
        PALATINO_LINGUAL,
        OCLUSAL_INCISAL
    }

    /**
     * Normaliza el texto dictado por voz para facilitar tanto la interpretación del LLM como el análisis local.
     */
    public String normalizarTextoDictado(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }

        String text = Normalizer.normalize(rawText, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);

        // Corrección de errores fonéticos frecuentes de Speech-to-Text (STT)
        text = text.replaceAll("\\bclientes?\\b", "diente");
        text = text.replaceAll("\\bdientes\\b", "diente");
        text = text.replaceAll("\\bpiezas?\\b", "diente");
        text = text.replaceAll("\\bocluzal\\b", "oclusal");
        text = text.replaceAll("\\bincizal\\b", "incisal");
        text = text.replaceAll("\\bmecial\\b", "mesial");
        text = text.replaceAll("\\bpalatina\\b", "palatino");

        // Traducción de nombres coloquiales a su número FDI correspondiente
        text = reemplazarNombresColoquialesPorFdi(text);

        // Expansión de rangos dentales (ej. "del 14 al 18" -> "14 15 16 17 18")
        text = expandirRangosDentales(text);

        // Elimina muletillas de cierre como "el resto sano" / "resto de dientes sano"
        text = text.replaceAll(
                "\\b(?:el\\s+)?resto\\s+(?:del\\s+diente\\s+|de\\s+los\\s+diente\\s+|de\\s+diente\\s+|de\\s+caras\\s+)?sanos?\\b",
                " "
        );

        // Reescribe "<estado [cara]> en/del [diente] <FDI>" -> "diente <FDI> <estado [cara]>"
        Matcher matcher = ESTADO_EN_DIENTE_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String hallazgo = matcher.group(1);
            String numeroFdi = matcher.group(4);
            matcher.appendReplacement(sb, "diente " + numeroFdi + " " + hallazgo);
        }
        matcher.appendTail(sb);

        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * Extrae de manera genérica todos los dientes (de 1 a 32) y sus estados clínicos/superficies
     * a partir del dictado por voz.
     */
    public List<ValidatedToothDraft> extraerDientesDelDictado(String rawText) {
        String normalized = normalizarTextoDictado(rawText);
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<FdiTokenMatch> matches = new ArrayList<>();
        Matcher matcher = TOKEN_FDI_PATTERN.matcher(normalized);
        while (matcher.find()) {
            int numero = Integer.parseInt(matcher.group(1));
            if (CatalogoFdi.esFdiValido(numero)) {
                matches.add(new FdiTokenMatch(numero, matcher.start(), matcher.end()));
            }
        }

        if (matches.isEmpty()) {
            return List.of();
        }

        List<ToothGroupClause> groups = agruparDientesYClausulas(normalized, matches);

        Map<Integer, ValidatedToothDraft> consolidado = new LinkedHashMap<>();
        for (ToothGroupClause group : groups) {
            for (int numero : group.numerosDientes()) {
                DienteFdiMetadata metadata = CatalogoFdi.obtenerPorNumero(numero);
                ValidatedToothDraft nuevoDraft = interpretarClausulaParaDiente(metadata, group.clausulaTexto());
                consolidado.merge(numero, nuevoDraft, this::combinarHallazgosMismoDiente);
            }
        }

        return List.copyOf(consolidado.values());
    }

    private List<ToothGroupClause> agruparDientesYClausulas(String text, List<FdiTokenMatch> matches) {
        List<ToothGroupClause> groups = new ArrayList<>();
        int i = 0;
        while (i < matches.size()) {
            List<Integer> numerosGrupo = new ArrayList<>();
            numerosGrupo.add(matches.get(i).numero());

            int j = i;
            while (j + 1 < matches.size()) {
                String entreDientes = text.substring(matches.get(j).end(), matches.get(j + 1).start()).trim();
                if (esConectorDeListaDental(entreDientes)) {
                    numerosGrupo.add(matches.get(j + 1).numero());
                    j++;
                } else {
                    break;
                }
            }

            int clauseStart = matches.get(j).end();
            int clauseEnd = (j + 1 < matches.size()) ? matches.get(j + 1).start() : text.length();
            String rawClause = text.substring(clauseStart, clauseEnd)
                    .replaceAll("\\bdiente\\s*$", "")
                    .trim();

            groups.add(new ToothGroupClause(numerosGrupo, rawClause));
            i = j + 1;
        }
        return groups;
    }

    private boolean esConectorDeListaDental(String textoEntreNumeros) {
        if (textoEntreNumeros.isEmpty()) {
            return true;
        }
        return textoEntreNumeros.matches("^[,;\\s]*(?:y|e)?[,;\\s]*(?:diente)?[,;\\s]*$");
    }

    private ValidatedToothDraft interpretarClausulaParaDiente(DienteFdiMetadata metadata, String clausula) {
        Map<CaraCanonica, EstadoClinico> estadosPorCara = new EnumMap<>(CaraCanonica.class);
        for (CaraCanonica cara : CaraCanonica.values()) {
            estadosPorCara.put(cara, EstadoClinico.SANO);
        }

        List<ClinicalToken> tokens = tokenizarClausula(clausula);
        boolean mencionaAlgunaCara = tokens.stream().anyMatch(t -> t.cara() != null);

        if (!mencionaAlgunaCara) {
            EstadoClinico estadoGeneral = tokens.stream()
                    .filter(t -> t.estado() != null)
                    .map(ClinicalToken::estado)
                    .findFirst()
                    .orElse(EstadoClinico.SANO);

            return new ValidatedToothDraft(
                    metadata,
                    estadoGeneral,
                    estadoGeneral,
                    estadoGeneral,
                    estadoGeneral,
                    estadoGeneral,
                    estadoGeneral
            );
        }

        evaluarTokensDeCarasYEstados(tokens, estadosPorCara);

        return new ValidatedToothDraft(
                metadata,
                EstadoClinico.PRESENTE,
                estadosPorCara.get(CaraCanonica.MESIAL),
                estadosPorCara.get(CaraCanonica.DISTAL),
                estadosPorCara.get(CaraCanonica.VESTIBULAR),
                estadosPorCara.get(CaraCanonica.PALATINO_LINGUAL),
                estadosPorCara.get(CaraCanonica.OCLUSAL_INCISAL)
        );
    }

    /**
     * Máquina de estados sobre flujo de tokens clínicos que soporta:
     * <ul>
     *   <li>Caras antes del estado: {@code [FACE(distal), FACE(vestibular), STATE(Sano), FACE(oclusal), STATE(Caries)]}</li>
     *   <li>Estado antes de las caras: {@code [STATE(Caries), FACE(mesial), FACE(distal)]}</li>
     *   <li>Combinaciones mixtas en la misma cláusula.</li>
     * </ul>
     */
    private void evaluarTokensDeCarasYEstados(
            List<ClinicalToken> tokens,
            Map<CaraCanonica, EstadoClinico> estadosPorCara
    ) {
        List<CaraCanonica> carasPendientes = new ArrayList<>();
        EstadoClinico estadoPendiente = null;

        for (int i = 0; i < tokens.size(); i++) {
            ClinicalToken token = tokens.get(i);
            if (token.cara() != null) {
                // Si tenemos un estado previo esperando caras (ej. "caries en mesial"), verificamos
                // si esta cara en realidad precede a un nuevo estado propio (ej. "... y oclusal restaurado")
                boolean siguienteEsEstado = (i + 1 < tokens.size()) && (tokens.get(i + 1).estado() != null);
                if (estadoPendiente != null && !siguienteEsEstado) {
                    estadosPorCara.put(token.cara(), estadoPendiente);
                } else {
                    if (estadoPendiente != null && siguienteEsEstado && carasPendientes.isEmpty()) {
                        estadoPendiente = null;
                    }
                    carasPendientes.add(token.cara());
                }
            } else if (token.estado() != null) {
                if (!carasPendientes.isEmpty()) {
                    for (CaraCanonica c : carasPendientes) {
                        estadosPorCara.put(c, token.estado());
                    }
                    carasPendientes.clear();
                    estadoPendiente = null;
                } else {
                    estadoPendiente = token.estado();
                }
            }
        }

        if (estadoPendiente != null && !carasPendientes.isEmpty()) {
            for (CaraCanonica c : carasPendientes) {
                estadosPorCara.put(c, estadoPendiente);
            }
        }
    }

    private List<ClinicalToken> tokenizarClausula(String clausula) {
        List<ClinicalToken> tokens = new ArrayList<>();
        Matcher m = TOKEN_CLINICO_PATTERN.matcher(clausula);
        while (m.find()) {
            String rawCara = m.group(1);
            String rawEstado = m.group(2);
            if (rawCara != null) {
                CaraCanonica cara = switch (rawCara) {
                    case "mesial" -> CaraCanonica.MESIAL;
                    case "distal" -> CaraCanonica.DISTAL;
                    case "vestibular" -> CaraCanonica.VESTIBULAR;
                    case "palatino", "palatina", "lingual" -> CaraCanonica.PALATINO_LINGUAL;
                    case "oclusal", "incisal" -> CaraCanonica.OCLUSAL_INCISAL;
                    default -> null;
                };
                if (cara != null) {
                    tokens.add(new ClinicalToken(cara, null));
                }
            } else if (rawEstado != null) {
                resolverEstado(rawEstado).ifPresent(estado -> tokens.add(new ClinicalToken(null, estado)));
            }
        }
        return tokens;
    }

    private ValidatedToothDraft combinarHallazgosMismoDiente(
            ValidatedToothDraft previo,
            ValidatedToothDraft nuevo
    ) {
        if (nuevo.estadoGeneral().isInhabilitaCarasIndividuales()) {
            return nuevo;
        }
        EstadoClinico mesial = nuevo.mesial() != EstadoClinico.SANO ? nuevo.mesial() : previo.mesial();
        EstadoClinico distal = nuevo.distal() != EstadoClinico.SANO ? nuevo.distal() : previo.distal();
        EstadoClinico vestibular = nuevo.vestibular() != EstadoClinico.SANO ? nuevo.vestibular() : previo.vestibular();
        EstadoClinico palatinoLingual = nuevo.palatinoLingual() != EstadoClinico.SANO
                ? nuevo.palatinoLingual() : previo.palatinoLingual();
        EstadoClinico oclusalIncisal = nuevo.oclusalIncisal() != EstadoClinico.SANO
                ? nuevo.oclusalIncisal() : previo.oclusalIncisal();

        boolean todasSanas = mesial == EstadoClinico.SANO
                && distal == EstadoClinico.SANO
                && vestibular == EstadoClinico.SANO
                && palatinoLingual == EstadoClinico.SANO
                && oclusalIncisal == EstadoClinico.SANO;

        EstadoClinico estadoGeneral = todasSanas ? EstadoClinico.SANO : EstadoClinico.PRESENTE;
        return new ValidatedToothDraft(
                previo.metadata(),
                estadoGeneral,
                mesial,
                distal,
                vestibular,
                palatinoLingual,
                oclusalIncisal
        );
    }

    private String expandirRangosDentales(String text) {
        Matcher m = RANGO_DIENTES_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int inicio = Integer.parseInt(m.group(1));
            int fin = Integer.parseInt(m.group(2));
            int cuadranteInicio = inicio / 10;
            int cuadranteFin = fin / 10;
            if (cuadranteInicio == cuadranteFin && CatalogoFdi.esFdiValido(inicio) && CatalogoFdi.esFdiValido(fin)) {
                List<String> expandidos = new ArrayList<>();
                int step = inicio <= fin ? 1 : -1;
                for (int d = inicio; step > 0 ? d <= fin : d >= fin; d += step) {
                    if (CatalogoFdi.esFdiValido(d)) {
                        expandidos.add(String.valueOf(d));
                    }
                }
                m.appendReplacement(sb, "diente " + String.join(" ", expandidos));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Optional<EstadoClinico> resolverEstado(String rawEstado) {
        if (rawEstado == null || rawEstado.isBlank()) {
            return Optional.empty();
        }
        String clean = rawEstado.trim().toLowerCase(Locale.ROOT);
        if (clean.endsWith("s") && !clean.equals("caries")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.endsWith("a") && !clean.equals("corona") && !clean.equals("resina")
                && !clean.equals("amalgama") && !clean.equals("fractura") && !clean.equals("exodoncia")) {
            clean = clean.substring(0, clean.length() - 1) + "o";
        }
        return EstadoClinico.fromTexto(clean);
    }

    private String reemplazarNombresColoquialesPorFdi(String text) {
        String result = text;
        for (DienteFdiMetadata meta : CatalogoFdi.obtenerTodos().values()) {
            String coloquialNorm = Normalizer.normalize(meta.nombreColoquial(), Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "")
                    .toLowerCase(Locale.ROOT)
                    .replace(" (cordal)", "");
            result = result.replace(coloquialNorm, "diente " + meta.numero());
        }
        return result;
    }

    private record FdiTokenMatch(int numero, int start, int end) {
    }

    private record ToothGroupClause(List<Integer> numerosDientes, String clausulaTexto) {
    }

    private record ClinicalToken(CaraCanonica cara, EstadoClinico estado) {
    }
}
