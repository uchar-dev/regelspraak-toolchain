package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.core.FixedStartPoint;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel.Granularity;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.transpiler.tms.ConstructorValue;
import dev.uchar.regelspraak.transpiler.tms.Period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * The input of a time-dependent member (uchar's RUNTIME-SEMANTICS ch. 3.1, 3.4, 8.3) as the request and the descriptor
 * give it, in the form of uchar's case.
 *
 * <p>A time-dependent parameter (a parameter {@code voor elke maand}) and a time-dependent attribute take their
 * timeline, {@code {"timeline": [{"from": <datum>, "to": <datum>, "value": <waarde>}, ...]}}, each value as the member's
 * datatype takes it. A segment without {@code from} begins before every day and one without {@code to} runs past every
 * day (an open bound, ch. 3.1); a day no segment covers is a gap and reads leeg. uchar's Drools branch reads such a
 * parameter whole, from a global of type {@code List<Period>} with the open bounds {@link Period#OPEN_FROM} and
 * {@link Period#OPEN_TO}, and the service sets its globals itself, so for a parameter this is the value it sets; an
 * attribute's timeline the request mapper writes into the case, whose input step reads it.
 *
 * <p>The checks are those of uchar's case reader ({@code CaseRunner.timelineSegments}, which mirrors the reference
 * interpreter's loader): at least one segment, {@code from} before {@code to}, the segments in order and apart, only the
 * first open at its start and only the last open at its end, and every bound that is there a wall of the member's grid
 * ({@link FixedStartPoint#opWand}). The calendar's week and quarter grids carry no case timeline in uchar, so a member on
 * one is refused by name; and a segment of uchar's branch holds a number or a text, or on an attribute with absence
 * markers a constructor value of a marker or of a number, so a segment value of another datatype is refused by name as
 * well. A number its declaration does not hold is uchar's EF-004, which uchar's input fence raises
 * per segment ({@link CaseRunner#inputFits}), as for a scalar.
 *
 * <p>A time-dependent characteristic takes the periods in which it holds, {@code {"periods": [{"from": <datum>, "to":
 * <datum>}, ...]}}, which the request mapper writes into the case as its {@code characteristicTimelines}. The checks are
 * those of uchar's input step ({@code CaseInput}): both bounds are dates, {@code from} before {@code to}, and the
 * periods, in the order of their start, do not overlap; a period sits on no grid, since a characteristic holds per day.
 */
final class TimelineInput {

    private TimelineInput() {}

    /** The grid a timeline of a member sits on, and the member as a sentence names it ({@code parameter 'havenheffing'},
     *  {@code attribuut 'strandloon'}), with its datatype. */
    record Grid(String member, Granularity granularity, String fixedStartPoint, Datatype datatype) {}

    /** One segment of a timeline that passed the checks: its bounds, null where it is open, and its value, a number, a
     *  text or, on an attribute with absence markers, uchar's constructor value of a marker or of a number. */
    record Segment(LocalDate from, LocalDate to, Object value) {}

    /** One period of a characteristic that passed the checks. */
    record Window(LocalDate from, LocalDate to) {}

    /** Whether {@code p} is time-dependent: its value is a timeline, not a scalar. */
    static boolean timeDependent(RegelSpraakModel.Parameter p) {
        return p.granularity() != null;
    }

    /** Whether {@code value} is given as a timeline: an object with {@code timeline}. */
    static boolean isTimeline(JsonNode value) {
        return value != null && value.isObject() && value.has("timeline");
    }

    /** Whether {@code value} is given as the periods of a characteristic: an object with {@code periods}. */
    static boolean isPeriods(JsonNode value) {
        return value != null && value.isObject() && value.has("periods");
    }

    /** The form a timeline takes, as a sentence writes it. */
    static final String TIMELINE_FORM = "{\"timeline\": [{\"from\": <datum>, \"to\": <datum>, \"value\": <waarde>},"
            + " ...]}, met from of to weggelaten voor een open grens";

    /** The form the periods of a characteristic take, as a sentence writes it. */
    static final String PERIODS_FORM = "{\"periods\": [{\"from\": <datum>, \"to\": <datum>}, ...]}";

    /** The violation of a value of {@code p} on {@code field} in the other form than its declaration takes, or null
     *  where the form fits: a scalar on a time-dependent parameter, a timeline on another. */
    static Violation formViolation(RegelSpraakModel.Parameter p, JsonNode value, String field) {
        if (timeDependent(p) == isTimeline(value)) return null;
        return Violation.of(Violation.Kind.WRONG_TYPE, field, timeDependent(p)
                ? "parameter '" + p.name() + "' is tijdsafhankelijk (" + perGrid(p.granularity())
                        + "): zijn waarde is een tijdlijn, " + TIMELINE_FORM
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
        List<Segment> segments = segments(new Grid("parameter '" + p.name() + "'", p.granularity(),
                p.fixedStartPoint(), p.datatype()), value, field, segmentValue, sink);
        if (segments == null) return null;
        List<Period> out = new ArrayList<>();
        for (Segment s : segments)
            out.add(new Period(s.from() == null ? Period.OPEN_FROM : s.from(), s.to() == null ? Period.OPEN_TO : s.to(),
                    s.value()));
        return List.copyOf(out);
    }

    /**
     * The segments of the timeline {@code value} of a member on {@code grid}, on {@code field}, or null after adding
     * the violations to {@code sink}. {@code segmentValue} decodes the value of one segment as a scalar of the member
     * (null after adding its violation).
     */
    static List<Segment> segments(Grid grid, JsonNode value, String field, SegmentValue segmentValue,
                                  List<Violation> sink) {
        int before = sink.size();
        Granularity g = grid.granularity();
        if (grid.fixedStartPoint() == null && (g == Granularity.WEEK || g == Granularity.KWARTAAL)) {
            sink.add(Violation.of(Violation.Kind.UNSUPPORTED, field, grid.member() + " ligt op het " + gridWord(g)
                    + "raster van de kalender, waarop uchars case-lezer geen tijdlijn draagt"));
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
        List<Segment> out = new ArrayList<>();
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
            LocalDate from = bound(s, "from", at, grid, sink), to = bound(s, "to", at, grid, sink);
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
            if (!(v instanceof BigDecimal || v instanceof String || v instanceof ConstructorValue c
                    && (c.payload() == null || c.payload() instanceof BigDecimal))) {
                sink.add(Violation.of(Violation.Kind.UNSUPPORTED, at + ".value", "uchars tak houdt een segment van een"
                        + " tijdlijn als getal of tekst; " + grid.member() + " heeft het datatype "
                        + grid.datatype().name()));
                continue;
            }
            out.add(new Segment(from, to, v));
        }
        return sink.size() > before ? null : List.copyOf(out);
    }

    /**
     * The periods {@code value} of the time-dependent characteristic {@code member} (as a sentence names it) on
     * {@code field}, in the order of the request, or null after adding the violations to {@code sink}.
     */
    static List<Window> periods(String member, JsonNode value, String field, List<Violation> sink) {
        int before = sink.size();
        Iterator<String> keys = value.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.equals("periods"))
                sink.add(Violation.of(Violation.Kind.MALFORMED, field, "de perioden van een kenmerk hebben alleen"
                        + " periods, niet " + key));
        }
        JsonNode periods = value.get("periods");
        if (!periods.isArray()) {
            sink.add(Violation.of(Violation.Kind.MALFORMED, field + ".periods", "periods is een lijst perioden"
                    + " {from, to}"));
            return null;
        }
        List<Window> out = new ArrayList<>();
        List<Integer> place = new ArrayList<>();
        for (int k = 0; k < periods.size(); k++) {
            JsonNode p = periods.get(k);
            String at = field + ".periods[" + k + "]";
            if (!p.isObject()) {
                sink.add(Violation.of(Violation.Kind.MALFORMED, at, "een periode is een object {from, to}"));
                continue;
            }
            List<String> other = new ArrayList<>();
            p.fieldNames().forEachRemaining(f -> { if (!List.of("from", "to").contains(f)) other.add(f); });
            if (!other.isEmpty()) {
                sink.add(Violation.of(Violation.Kind.MALFORMED, at, "een periode heeft alleen from en to, niet "
                        + String.join(", ", other)));
                continue;
            }
            LocalDate from = date(p, "from", at, sink), to = date(p, "to", at, sink);
            if (from == null || to == null) continue;
            if (!from.isBefore(to)) {
                sink.add(Violation.of(Violation.Kind.MALFORMED, at, "to " + to + " ligt niet na from " + from));
                continue;
            }
            out.add(new Window(from, to));
            place.add(k);
        }
        if (sink.size() > before) return null;
        // uchar's input step lays the periods in the order of their start and refuses two that overlap
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) order.add(i);
        order.sort(Comparator.comparing((Integer i) -> out.get(i).from()).thenComparing(i -> out.get(i).to()));
        for (int i = 1; i < order.size(); i++) {
            Window earlier = out.get(order.get(i - 1)), later = out.get(order.get(i));
            if (later.from().isBefore(earlier.to())) {
                sink.add(Violation.of(Violation.Kind.MALFORMED, field + ".periods[" + place.get(order.get(i)) + "]",
                        "de periode van " + later.from() + " tot " + later.to() + " overlapt de periode van "
                                + earlier.from() + " tot " + earlier.to() + " van " + member));
                return null;
            }
        }
        return List.copyOf(out);
    }

    /** Decodes the value of one segment as a scalar of the member. */
    @FunctionalInterface
    interface SegmentValue {
        Object decode(JsonNode value, String field, List<Violation> sink);
    }

    /** The bound {@code key} of segment {@code s}, null where the segment leaves it out (an open bound) or after adding
     *  the violation of one that is no date or no wall of the member's grid. */
    private static LocalDate bound(JsonNode s, String key, String at, Grid grid, List<Violation> sink) {
        if (!s.has(key)) return null;
        String field = at + "." + key;
        LocalDate d = parse(s.get(key));
        if (d == null) {
            sink.add(Violation.of(Violation.Kind.WRONG_TYPE, field, key + " moet een ISO-datum (yyyy-MM-dd) zijn, of"
                    + " ontbreken voor een open grens"));
            return null;
        }
        if (Boolean.FALSE.equals(FixedStartPoint.opWand(grid.granularity(), 1, grid.fixedStartPoint(), d))) {
            sink.add(Violation.of(Violation.Kind.WRONG_TYPE, field, d + " ligt niet op het "
                    + gridWord(grid.granularity()) + "raster van " + grid.member()
                    + (grid.fixedStartPoint() == null ? "" : " vanaf " + grid.fixedStartPoint())));
            return null;
        }
        return d;
    }

    /** The date {@code key} of period {@code p}, or null after adding the violation of one that is absent or no
     *  date: a period of a characteristic has both bounds. */
    private static LocalDate date(JsonNode p, String key, String at, List<Violation> sink) {
        LocalDate d = p.has(key) ? parse(p.get(key)) : null;
        if (d == null)
            sink.add(Violation.of(Violation.Kind.WRONG_TYPE, at + "." + key, key + " moet een ISO-datum (yyyy-MM-dd)"
                    + " zijn; een periode van een kenmerk heeft beide grenzen"));
        return d;
    }

    /** The ISO date {@code n} writes, or null where it writes none. */
    private static LocalDate parse(JsonNode n) {
        try {
            return n.isTextual() ? LocalDate.parse(n.asText()) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Grid {@code g} as a declaration writes it: {@code voor elke maand}, {@code voor elk jaar}. */
    static String perGrid(Granularity g) {
        return switch (g) {
            case DAG -> "voor elke dag";
            case WEEK -> "voor elke week";
            case MAAND -> "voor elke maand";
            case KWARTAAL -> "voor elk kwartaal";
            case JAAR -> "voor elk jaar";
        };
    }

    /** The unit of grid {@code g}, as the word before {@code raster} spells it ({@code maandraster}). */
    static String gridWord(Granularity g) {
        return switch (g) {
            case DAG -> "dag";
            case WEEK -> "week";
            case MAAND -> "maand";
            case KWARTAAL -> "kwartaal";
            case JAAR -> "jaar";
        };
    }
}
