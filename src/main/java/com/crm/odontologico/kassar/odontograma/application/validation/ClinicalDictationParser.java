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
 * Analizador determinístico de lenguaje natural clínico odontológico.
 * <p>
 * Cumple dos propósitos clave dentro de la arquitectura:
 * <ol>
 *   <li><b>Pre-procesamiento y estructuración:</b> Corrige errores fonéticos de reconocimiento de voz
 *       (ej. "cliente 18" -> "diente 18"), resuelve nombres coloquiales a códigos FDI, agrupa dientes
 *       con diagnóstico compartido (ej. "16 15 14 13 y 12 sanos") y normaliza sintaxis invertida
 *       (ej. "caries en 32" -> "diente 32 caries").</li>
 *   <li><b>Validación cruzada y reconciliación:</b> Permite verificar que el modelo de IA no omita
 *       ningún diente dictado ni mezcle hallazgos entre dientes contiguos cuando se dictan múltiples
 *       piezas en una sola frase.</li>
 * </ol>
 */
@Component
public class ClinicalDictationParser {

    private static final String ESTADOS_REGEX =
            "(caries|sanos?|restaurados?|resinas?|amalgamas?|obturados?|obturacion|fracturados?|fracturas?|"
                    + "ausentes?|extraidos?|extraccion|exodoncia|retenidos?|coronas?|implantes?|sellantes?|presentes?)";

    private static final String CARAS_REGEX =
            "(mesial|distal|vestibular|palatino|palatina|lingual|oclusal|incisal)";

    /**
     * Detecta expresiones donde el diagnóstico precede al número de diente (ej. "caries en 32", "ausente en el 48")
     * siempre que NO sea parte de "<diente> <estado> en <cara>".
     */
    private static final Pattern ESTADO_EN_DIENTE_PATTERN = Pattern.compile(
            "\\b" + ESTADOS_REGEX + "\\s+en\\s+(?:el\\s+|la\\s+)?(?:diente\\s+|pieza\\s+)?([1-4][1-8])\\b"
    );

    private static final Pattern TOKEN_FDI_PATTERN = Pattern.compile("\\b([1-4][1-8])\\b");

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

        // Elimina muletillas de cierre como "el resto sano" / "resto de dientes sano" (ya que por regla
        // toda cara no mencionada con patología en un diente presente queda en estado Sano).
        text = text.replaceAll(
                "\\b(?:el\\s+)?resto\\s+(?:del\\s+diente\\s+|de\\s+los\\s+diente\\s+|de\\s+diente\\s+|de\\s+caras\\s+)?sanos?\\b",
                " "
        );

        // Reescribe "<estado> en [diente] <FDI>" -> "diente <FDI> <estado>" (ej. "caries en 32" -> "diente 32 caries")
        Matcher matcher = ESTADO_EN_DIENTE_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String estado = matcher.group(1);
            String numeroFdi = matcher.group(2);
            matcher.appendReplacement(sb, "diente " + numeroFdi + " " + estado);
        }
        matcher.appendTail(sb);

        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * Extrae de manera determinística todos los dientes y sus estados clínicos/superficies
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

        // Agrupa dientes consecutivos separados únicamente por espacios, comas o conjunción "y"/"e"
        // (ej. "16 15 14 13 y 12 sanos")
        List<ToothGroupClause> groups = agruparDientesYClausulas(normalized, matches);

        Map<Integer, ValidatedToothDraft> consolidado = new LinkedHashMap<>();
        for (ToothGroupClause group : groups) {
            for (int numero : group.numerosDientes()) {
                DienteFdiMetadata metadata = CatalogoFdi.obtenerPorNumero(numero);
                ValidatedToothDraft draft = interpretarClausulaParaDiente(metadata, group.clausulaTexto());
                consolidado.put(numero, draft);
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

        boolean mencionaAlgunaCara = Pattern.compile("\\b" + CARAS_REGEX + "\\b").matcher(clausula).find();

        if (!mencionaAlgunaCara) {
            EstadoClinico estadoGeneral = extraerPrimerEstado(clausula).orElse(EstadoClinico.SANO);
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

        // Si menciona caras específicas, asignamos los estados por cara y dejamos estadoGeneral en Presente
        asignarEstadosACarasEnClausula(clausula, estadosPorCara);

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

    private void asignarEstadosACarasEnClausula(
            String clausula,
            Map<CaraCanonica, EstadoClinico> estadosPorCara
    ) {
        // Caso A: "<estado> en <cara(s)>" (ej. "caries en mesial", "caries en oclusal y distal")
        Pattern estadoEnCaraPattern = Pattern.compile(
                "\\b" + ESTADOS_REGEX + "\\s+(?:en\\s+)?((?:" + CARAS_REGEX + "(?:\\s*(?:,|y|e)\\s*)?)+)\\b"
        );
        Matcher m1 = estadoEnCaraPattern.matcher(clausula);
        boolean matchedEstadoAntes = false;
        while (m1.find()) {
            Optional<EstadoClinico> estadoOpt = resolverEstado(m1.group(1));
            List<CaraCanonica> caras = extraerCaras(m1.group(2));
            if (estadoOpt.isPresent() && !caras.isEmpty()) {
                for (CaraCanonica c : caras) {
                    estadosPorCara.put(c, estadoOpt.get());
                }
                matchedEstadoAntes = true;
            }
        }

        // Caso B: "<cara(s)> <estado>" (ej. "distal y vestibular sano, oclusal caries", "distal caries")
        Pattern caraAntesDeEstadoPattern = Pattern.compile(
                "\\b((?:" + CARAS_REGEX + "(?:\\s*(?:,|y|e)\\s*)?)+)\\s+(?:con\\s+|en\\s+)?" + ESTADOS_REGEX + "\\b"
        );
        Matcher m2 = caraAntesDeEstadoPattern.matcher(clausula);
        while (m2.find()) {
            List<CaraCanonica> caras = extraerCaras(m2.group(1));
            Optional<EstadoClinico> estadoOpt = resolverEstado(m2.group(3));
            if (estadoOpt.isPresent() && !caras.isEmpty()) {
                for (CaraCanonica c : caras) {
                    estadosPorCara.put(c, estadoOpt.get());
                }
            }
        }

        // Fallback si solo hay 1 estado y 1 o varias caras en orden libre
        if (!matchedEstadoAntes && estadosPorCara.values().stream().allMatch(e -> e == EstadoClinico.SANO)) {
            Optional<EstadoClinico> estadoUnico = extraerPrimerEstado(clausula);
            List<CaraCanonica> carasMencionadas = extraerCaras(clausula);
            if (estadoUnico.isPresent() && !carasMencionadas.isEmpty()) {
                for (CaraCanonica c : carasMencionadas) {
                    estadosPorCara.put(c, estadoUnico.get());
                }
            }
        }
    }

    private Optional<EstadoClinico> extraerPrimerEstado(String texto) {
        Matcher m = Pattern.compile("\\b" + ESTADOS_REGEX + "\\b").matcher(texto);
        if (m.find()) {
            return resolverEstado(m.group(1));
        }
        return Optional.empty();
    }

    private Optional<EstadoClinico> resolverEstado(String rawEstado) {
        if (rawEstado == null || rawEstado.isBlank()) {
            return Optional.empty();
        }
        String clean = rawEstado.trim().toLowerCase(Locale.ROOT);
        if (clean.endsWith("s") && !clean.equals("caries")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return EstadoClinico.fromTexto(clean);
    }

    private List<CaraCanonica> extraerCaras(String fragmento) {
        List<CaraCanonica> caras = new ArrayList<>();
        Matcher m = Pattern.compile("\\b" + CARAS_REGEX + "\\b").matcher(fragmento);
        while (m.find()) {
            String caraStr = m.group(1);
            switch (caraStr) {
                case "mesial" -> caras.add(CaraCanonica.MESIAL);
                case "distal" -> caras.add(CaraCanonica.DISTAL);
                case "vestibular" -> caras.add(CaraCanonica.VESTIBULAR);
                case "palatino", "palatina", "lingual" -> caras.add(CaraCanonica.PALATINO_LINGUAL);
                case "oclusal", "incisal" -> caras.add(CaraCanonica.OCLUSAL_INCISAL);
            }
        }
        return caras;
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
}
