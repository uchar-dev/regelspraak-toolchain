package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.core.FixedStartPoint;
import dev.uchar.regelspraak.model.RegelSpraakModel.Granularity;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.transpiler.tms.Period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The value of a time-dependent parameter (a parameter {@code voor elke maand}, uchar's RUNTIME-SEMANTICS ch. 3.4, 8.3)
 * as the request and the descriptor give it: its timeline, in the form of uchar's case, {@code {"timeline": [{"from":
 * <datum>, "to": <datum>, "value": <waarde>}, ...]}}, each value as the parameter's datatype takes it. A segment without
 * {@code from} begins before every day and one without {@code to} runs past every day (an open bound, ch. 3.1); a day
 * no segment covers is a gap and reads leeg. uchar's Drools branch reads such a parameter whole, from a global of type
 * {@code List<Period>} with the open bounds {@link Period#OPEN_FROM} and {@link Period#OPEN_TO}, and the service sets
 * its globals itself, so this is the value it sets.
 *
 * <p>The checks are those of uchar's case reader ({@code CaseRunner.timelineSegments}, which mirrors the reference
 * interpreter's loader): at least one segment, {@code from} before {@code to}, the segments in order and apart, only the
 * first open at its start and only the last open at its end, and every bound that is there a wall of the parameter's
 * grid ({@link FixedStartPoint#opWand}). The calendar's week and quarter grids carry no case timeline in uchar, so a
 * parameter on one is refused by name. A number its declaration does not hold is uchar's EF-004, which uchar's input
 * fence raises per segment ({@link CaseRunner#inputFits}) in the decoder of a segment value, as for a scalar; the
 * caller answers it as it answers that fault of a scalar.
 */
final class ParameterTimeline {

    private ParameterTimeline() {}

    /** Whether {@code p} is time-dependent: its value is a timeline, not a scalar. */
    static boolean timeDependent(RegelSpraakModel.Parameter p) {
        return p.granularity() != null;
    }

    /** Whether {@code value} is given as a timeline: an object with {@code timeline}. */
    static boolean isTimeline(JsonNode value) {
        return value != null && value.isObject() && value.has("timeline");
    }

    /** The violation of a value of {@code p} on {@code field} in the other form than its declaration takes, or null
     *  where the form fits: a scalar on a time-dependent parameter, a timeline on another. */
    static Violation formViolation(RegelSpraakModel.Parameter p, JsonNode value, String field) {
        if (timeDependent(p) == isTimeline(value)) return null;
        return Violation.of(Violation.Kind.WRONG_TYPE, field, timeDependent(p)
                ? "parameter '" + p.name() + "' is tijdsafhankelijk (voor elke " + gridWord(p.granularity())
                        + "): zijn waarde is een tijdlijn, {\"timeline\": [{\"from\": <datum>, \"to\": <datum>,"
                        + " \"value\": <waarde>}, ...]}, met from of to weggelaten voor een open grens"
                : "parameter '" + p.name() + "' is niet tijdsafhankelijk: zijn waarde is één waarde, geen tijdlijn");
    }

    /**
     * The timeline {@code value} of the time-dependent parameter {@code p} on {@code field}, as uchar's branch reads
     * it, or null after adding the violations to {@code sink}. {@code segmentValue} decodes the value of one segment as
     * a scalar of the parameter (null after adding its violation), holding a number to uchar's input fence.
     *
     * @throws CaseRunner.InputPrecisionFault from {@code segmentValue}: a segment value with more decimals than the
     *                                        parameter declares (EF-004)
     */
    static List<Period> decode(RegelSpraakModel.Parameter p, JsonNode value, String field, SegmentValue segmentValue,
                               List<Violation> sink) {
        int before = sink.size();
        Granularity g = p.granularity();
        if (p.fixedStartPoint() == null && (g == Granularity.WEEK || g == Granularity.KWARTAAL)) {
            sink.add(Violation.of(Violation.Kind.UNSUPPORTED, field, "parameter '" + p.name() + "' ligt op het "
                    + gridWord(g) + "raster van de kalender, waarop uchars case-lezer geen tijdlijn draagt"));
            return null;
        }
        Iterator<String> keys = value.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.equals("timeline"))
                sink.add(Violation.of(Violation.Kind.MALFORMED, field, "een tijdlijn heeft alleen timeline, niet " + key));
        }
        JsonNode segments = value.get("timeline");
        if (!segments.isArray() || segments.isEmpty()) {
            sink.add(Violation.of(Violation.Kind.MALFORMED, field + ".timeline",
                    "timeline is een niet-lege lijst segmenten {from, to, value}"));
            return null;
        }
        List<Period> out = new ArrayList<>();
        LocalDate previousTo = null;
        for (int k = 0; k < segments.size(); k++) {
            JsonNode s = segments.get(k);
            String at = field + ".timeline[" + k + "]";
            if (!s.isObject()) {
                sink.add(Violation.of(Violation.Kind.MALFORMED, at, "een segment is een object {from, to, value}"));
                continue;
            }
            List<String> other = new ArrayList<>();
            s.fieldNames().forEachRemaining(f -> { if (!List.of("from", "to", "value").contains(f)) other.add(f); });
            if (!other.isEmpty()) {
                sink.add(Violation.of(Violation.Kind.MALFORMED, at, "een segment heeft alleen from, to en value, niet "
                        + String.join(", ", other)));
                continue;
            }
            LocalDate from = bound(s, "from", at, p, sink), to = bound(s, "to", at, p, sink);
            if (s.has("from") && from == null || s.has("to") && to == null) continue;
            if (from != null && to != null && !from.isBefore(to)) {
                sink.add(Violation.of(Violation.Kind.MALFORMED, at, "to " + to + " ligt niet na from " + from));
                continue;
            }
            // only the first segment may begin open and only the last may end open; the segments stand apart
            if (k > 0 && from == null) {
                sink.add(Violation.of(Violation.Kind.MALFORMED, at, "alleen het eerste segment mag zonder from"
                        + " beginnen; dit segment volgt op een ander"));
                continue;
            }
            if (k < segments.size() - 1 && to == null) {
                sink.add(Violation.of(Violation.Kind.MALFORMED, at, "alleen het laatste segment mag zonder to eindigen;"
                        + " er volgt nog een segment"));
                continue;
            }
            if (previousTo != null && from.isBefore(previousTo)) {
                sink.add(Violation.of(Violation.Kind.MALFORMED, at, "het segment begint op " + from + ", vóór het einde "
                        + previousTo + " van het segment ervoor: de segmenten staan op volgorde en overlappen niet"));
                continue;
            }
            previousTo = to;
            if (!s.has("value") || s.get("value").isNull()) {
                sink.add(Violation.of(Violation.Kind.MALFORMED, at + ".value", "value ontbreekt; een dag zonder waarde"
                        + " is een gat tussen twee segmenten"));
                continue;
            }
            Object v = segmentValue.decode(s.get("value"), at + ".value", sink);
            if (v == null) continue;
            if (!(v instanceof BigDecimal number)) {
                sink.add(Violation.of(Violation.Kind.UNSUPPORTED, at + ".value", "uchars tak houdt een segment van een"
                        + " parameter als getal; parameter '" + p.name() + "' heeft het datatype " + p.datatype().name()));
                continue;
            }
            out.add(new Period(from == null ? Period.OPEN_FROM : from, to == null ? Period.OPEN_TO : to, number));
        }
        return sink.size() > before ? null : List.copyOf(out);
    }

    /** Decodes the value of one segment as a scalar of the parameter. */
    @FunctionalInterface
    interface SegmentValue {
        Object decode(JsonNode value, String field, List<Violation> sink);
    }

    /** The bound {@code key} of segment {@code s}, null where the segment leaves it out (an open bound) or after adding
     *  the violation of one that is no date or no wall of the parameter's grid. */
    private static LocalDate bound(JsonNode s, String key, String at, RegelSpraakModel.Parameter p, List<Violation> sink) {
        if (!s.has(key)) return null;
        String field = at + "." + key;
        JsonNode n = s.get(key);
        LocalDate d;
        try {
            d = n.isTextual() ? LocalDate.parse(n.asText()) : null;
        } catch (DateTimeParseException e) {
            d = null;
        }
        if (d == null) {
            sink.add(Violation.of(Violation.Kind.WRONG_TYPE, field, key + " moet een ISO-datum (yyyy-MM-dd) zijn, of"
                    + " ontbreken voor een open grens"));
            return null;
        }
        if (Boolean.FALSE.equals(FixedStartPoint.opWand(p.granularity(), 1, p.fixedStartPoint(), d))) {
            sink.add(Violation.of(Violation.Kind.WRONG_TYPE, field, d + " ligt niet op het " + gridWord(p.granularity())
                    + "raster van parameter '" + p.name() + "'"
                    + (p.fixedStartPoint() == null ? "" : " vanaf " + p.fixedStartPoint())));
            return null;
        }
        return d;
    }

    /** The unit of grid {@code g} as RegelSpraak names it after {@code voor elke}. */
    private static String gridWord(Granularity g) {
        return switch (g) {
            case DAG -> "dag";
            case WEEK -> "week";
            case MAAND -> "maand";
            case KWARTAAL -> "kwartaal";
            case JAAR -> "jaar";
        };
    }
}
