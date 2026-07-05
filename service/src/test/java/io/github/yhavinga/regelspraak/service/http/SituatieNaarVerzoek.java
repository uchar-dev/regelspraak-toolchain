package io.github.yhavinga.regelspraak.service.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.yhavinga.regelspraak.ir.ResolvedScenario;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.AttribuutWaarde;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.Instantie;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.KenmerkWaarde;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.ParameterWaarde;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.RolWaarde;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.Waarde;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.Temporal;

/**
 * Test-support (G2): MECHANICALLY derive the HTTP request body from a resolved scenario's merged
 * {@link ResolvedScenario.Situatie}. Nothing here is hand-typed — every value, label, type, rol target,
 * parameter and the peildatum is read straight off the resolver's typed IR, so the "one truth, two transports"
 * guarantee holds: the same {@link io.github.yhavinga.regelspraak.resolver.ScenarioResolver} output that the in-process
 * runner beans up is the HTTP request the golden harness posts.
 *
 * <p>The value → JSON mapping mirrors {@code ScenarioRunner.waardeNaarArg} — the situatie value is ALREADY
 * normalised by the resolver to the target attribuut/parameter's declared unit (2 uur → 120, 90 minuut → 90),
 * so a numeric value emits as a bare EXACT JSON number (a {@code BigDecimal} node, never a double) with no
 * eenheid object; a date/datetime as its ISO string; a boolean/tekst/enum as its plain JSON scalar. Those are
 * exactly the forms {@link io.github.yhavinga.regelspraak.service.WaardeCoder} accepts (a plain number is read in the
 * declared unit — so the byte-for-byte-normalised value round-trips), so no request value is ever approximated.
 *
 * <p>The response shape produced matches {@link io.github.yhavinga.regelspraak.service.Verzoek}:
 * {@code instanties[]{type,id,attributen,kenmerken,rollen}}, {@code parameters}, {@code rekendatum}.
 */
final class SituatieNaarVerzoek {

    /** Exact BigDecimal rendering: a JSON number is a BigDecimal node, never a double. */
    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    private SituatieNaarVerzoek() {}

    /** Build the request tree from a resolved scenario's situatie + peildatum. */
    static ObjectNode bouw(ResolvedScenario scenario) {
        ResolvedScenario.Situatie situatie = scenario.situatie();
        ObjectNode root = NF.objectNode();

        // rekendatum (peildatum) — the mapper reads it as an ISO date; a datetime peildatum is narrowed to its
        // date exactly as ScenarioRunner.zetGlobals does (rekendatum global is a LocalDate).
        Temporal peildatum = scenario.peildatum();
        if (peildatum != null) {
            LocalDate d = peildatum instanceof LocalDateTime dt ? dt.toLocalDate() : (LocalDate) peildatum;
            root.put("rekendatum", d.toString());
        }

        // parameters — the last-wins merged parameter values, keyed by declared parameter name.
        if (!situatie.parameters().isEmpty()) {
            ObjectNode params = root.putObject("parameters");
            for (ParameterWaarde p : situatie.parameters())
                params.set(p.parameter(), waardeNaarJson(p.waarde()));
        }

        // instanties — one per labelled instance, in first-seen order.
        ArrayNode instanties = root.putArray("instanties");
        for (Instantie i : situatie.instanties()) {
            ObjectNode inst = instanties.addObject();
            inst.put("type", i.objecttype().naam);
            inst.put("id", i.label());

            if (!i.attributen().isEmpty()) {
                ObjectNode attributen = inst.putObject("attributen");
                for (AttribuutWaarde a : i.attributen())
                    attributen.set(a.attribuut(), waardeNaarJson(a.waarde()));
            }
            if (!i.kenmerken().isEmpty()) {
                ObjectNode kenmerken = inst.putObject("kenmerken");
                for (KenmerkWaarde k : i.kenmerken())
                    kenmerken.put(k.kenmerk(), Boolean.TRUE);   // a situatie only ever ASSERTS a kenmerk
            }
            if (!i.rollen().isEmpty()) {
                ObjectNode rollen = inst.putObject("rollen");
                for (RolWaarde r : i.rollen()) {
                    if (r.meervoudig()) {
                        ArrayNode doelen = rollen.putArray(r.rol());
                        for (String label : r.doelLabels()) doelen.add(label);
                    } else {
                        rollen.put(r.rol(), r.doelLabels().get(0));
                    }
                }
            }
        }
        return root;
    }

    /** The already-normalised IR value → its JSON form the request mapper accepts (mirrors waardeNaarArg then
     *  the WaardeCoder-accepted JSON node). BigDecimal → exact number; LocalDate/LocalDateTime → ISO string;
     *  Boolean → boolean; String/enum → string. A leeg value (never produced for a situatie assignment) → null. */
    static com.fasterxml.jackson.databind.JsonNode waardeNaarJson(Waarde w) {
        if (w == null || w.leeg()) return NF.nullNode();
        if (w.getal() != null) return NF.numberNode(w.getal());          // EXACT BigDecimal, no double
        if (w.datum() != null) return NF.textNode(w.datum().toString()); // yyyy-MM-dd
        if (w.datumTijd() != null) return NF.textNode(w.datumTijd().toString());
        if (w.bool() != null) return NF.booleanNode(w.bool());
        if (w.enumWaarde() != null) return NF.textNode(w.enumWaarde());  // enum literal
        return NF.textNode(w.tekst());
    }
}
