package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.ir.ResolvedScenario.AttribuutWaarde;
import dev.uchar.regelspraak.ir.ResolvedScenario.Instantie;
import dev.uchar.regelspraak.ir.ResolvedScenario.KenmerkWaarde;
import dev.uchar.regelspraak.ir.ResolvedScenario.ParameterWaarde;
import dev.uchar.regelspraak.ir.ResolvedScenario.RolWaarde;
import dev.uchar.regelspraak.ir.ResolvedScenario.Waarde;

import java.math.BigDecimal;

/**
 * MECHANICALLY derive the request body of the service from a resolved scenario's merged
 * {@link ResolvedScenario.Situatie}. Nothing here is hand-typed — every value, label, type, rol target,
 * parameter and the peildatum is read straight off uchar's scenario resolver, so the "one truth, two transports"
 * guarantee holds: the same {@link dev.uchar.regelspraak.resolver.ScenarioResolver} output that the scenario
 * runner lowers to a case file is the request the golden harness posts and the playground's {@code via=dienst}
 * hands to the core ({@link Playground}). Each instance keeps its label as its {@code id}, so an expectation of the
 * case names the instance by an id of the request.
 *
 * <p>The situatie value is ALREADY normalised by the resolver to the target attribuut/parameter's declared unit
 * (2 uur → 120, 90 minuut → 90) as an exact case lexeme, so a numeric value emits as a bare EXACT JSON number (a
 * {@code BigDecimal} node, never a double) with no eenheid object; a date/datetime as its ISO string; a
 * boolean/tekst/enum as its plain JSON scalar. Those are exactly the forms
 * {@link dev.uchar.regelspraak.toolchain.service.WaardeCoder} accepts (a plain number is read in the declared unit),
 * so no request value is ever approximated. A timeline value, a time-dependent kenmerk and a fraction that has no
 * exact decimal form have no request form, and fail here by name ({@link NietDraagbaar}).
 *
 * <p>The response shape produced matches {@link dev.uchar.regelspraak.toolchain.service.Verzoek}:
 * {@code instanties[]{type,id,attributen,kenmerken,rollen}}, {@code parameters}, {@code rekendatum}.
 */
final class SituatieNaarVerzoek {

    /** Exact BigDecimal rendering: a JSON number is a BigDecimal node, never a double. */
    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    private SituatieNaarVerzoek() {}

    /** A value of the situation that a request of the service cannot carry, named in the sentence. */
    static final class NietDraagbaar extends IllegalArgumentException {
        NietDraagbaar(String zin) { super(zin); }
        NietDraagbaar(String zin, Throwable oorzaak) { super(zin, oorzaak); }
    }

    /** Build the request tree from a resolved scenario's situatie + peildatum. */
    static ObjectNode bouw(ResolvedScenario scenario) {
        ResolvedScenario.Situatie situatie = scenario.situatie();
        ObjectNode root = NF.objectNode();

        // rekendatum (peildatum) — the mapper reads it as an ISO date.
        if (scenario.peildatum() != null) root.put("rekendatum", scenario.peildatum().toString());

        // parameters — the last-wins merged parameter values, keyed by declared parameter name.
        if (!situatie.parameters().isEmpty()) {
            ObjectNode params = root.putObject("parameters");
            for (ParameterWaarde p : situatie.parameters()) {
                if (p.tijdlijn() != null)
                    throw new NietDraagbaar("parameter '" + p.parameter() + "' heeft een tijdlijn; het "
                            + "verzoek van de dienst draagt geen tijdlijnwaarde");
                params.set(p.parameter(), waardeNaarJson(p.waarde()));
            }
        }

        // instanties — one per labelled instance, in first-seen order.
        ArrayNode instanties = root.putArray("instanties");
        for (Instantie i : situatie.instanties()) {
            ObjectNode inst = instanties.addObject();
            inst.put("type", i.objecttype().naam);
            inst.put("id", i.label());

            if (!i.attributen().isEmpty()) {
                ObjectNode attributen = inst.putObject("attributen");
                for (AttribuutWaarde a : i.attributen()) {
                    if (a.tijdlijn() != null)
                        throw new NietDraagbaar("attribuut '" + a.attribuut() + "' van " + i.label()
                                + " heeft een tijdlijn; het verzoek van de dienst draagt geen tijdlijnwaarde");
                    attributen.set(a.attribuut(), waardeNaarJson(a.waarde()));
                }
            }
            if (!i.kenmerken().isEmpty()) {
                ObjectNode kenmerken = inst.putObject("kenmerken");
                for (KenmerkWaarde k : i.kenmerken()) {
                    if (k.perioden() != null)
                        throw new NietDraagbaar("kenmerk '" + k.kenmerk() + "' van " + i.label()
                                + " is tijdsafhankelijk; het verzoek van de dienst draagt geen perioden");
                    kenmerken.put(k.kenmerk(), Boolean.TRUE);   // a situatie only ever ASSERTS a kenmerk
                }
            }
            if (!i.rollen().isEmpty()) {
                ObjectNode rollen = inst.putObject("rollen");
                for (RolWaarde r : i.rollen()) {
                    if (r.meervoudig()) {
                        ArrayNode doelen = rollen.putArray(r.sleutel());
                        for (String label : r.doelLabels()) doelen.add(label);
                    } else {
                        rollen.put(r.sleutel(), r.doelLabels().get(0));
                    }
                }
            }
        }
        return root;
    }

    /** The already-normalised value → its JSON form the request mapper accepts. A number lexeme → exact number;
     *  LocalDate/LocalDateTime → ISO string; Boolean → boolean; String/enum → string. */
    static com.fasterxml.jackson.databind.JsonNode waardeNaarJson(Waarde w) {
        if (w.getal() != null) return NF.numberNode(getal(w.getal()));   // EXACT BigDecimal, no double
        if (w.datum() != null) return NF.textNode(w.datum().toString()); // yyyy-MM-dd
        if (w.datumTijd() != null) return NF.textNode(w.datumTijd().toString());
        if (w.bool() != null) return NF.booleanNode(w.bool());
        if (w.enumWaarde() != null) return NF.textNode(w.enumWaarde());  // enum literal
        return NF.textNode(w.tekst());
    }

    /** A case number lexeme as an exact BigDecimal: a decimal with a dot, or a reduced fraction {@code p/q} whose
     *  quotient terminates. A fraction that does not terminate has no exact decimal, and no request carries it. */
    static BigDecimal getal(String lexeem) {
        int streep = lexeem.indexOf('/');
        if (streep < 0) return new BigDecimal(lexeem);
        BigDecimal teller = new BigDecimal(lexeem.substring(0, streep));
        BigDecimal noemer = new BigDecimal(lexeem.substring(streep + 1));
        try {
            return teller.divide(noemer);
        } catch (ArithmeticException e) {
            throw new NietDraagbaar("de breuk " + lexeem + " heeft geen exacte decimale vorm", e);
        }
    }
}
