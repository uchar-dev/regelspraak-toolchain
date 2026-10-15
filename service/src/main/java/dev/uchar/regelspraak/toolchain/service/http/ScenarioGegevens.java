package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.engine.ScenarioResultaat.VerwachtingResultaat;
import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.ir.ResolvedScenario.AttribuutWaarde;
import dev.uchar.regelspraak.ir.ResolvedScenario.Instantie;
import dev.uchar.regelspraak.ir.ResolvedScenario.KenmerkWaarde;
import dev.uchar.regelspraak.ir.ResolvedScenario.ParameterWaarde;
import dev.uchar.regelspraak.ir.ResolvedScenario.Periode;
import dev.uchar.regelspraak.ir.ResolvedScenario.RolWaarde;
import dev.uchar.regelspraak.ir.ResolvedScenario.Segment;
import dev.uchar.regelspraak.ir.ResolvedScenario.Waarde;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.toolchain.engine.CaseSessie;
import dev.uchar.regelspraak.toolchain.engine.Naamgeving;
import dev.uchar.regelspraak.toolchain.service.FixpuntGegevens;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The data of one scenario the playground gives beside uchar's report: its situation as uchar's scenario resolver
 * gives it ({@link ResolvedScenario}), the source line of each expectation, and what stands at the fixpoint of the
 * case's own session ({@link CaseSessie.Uitkomst#fixpunt}). It reads uchar's public API and computes nothing itself.
 *
 * <p><b>Names</b> are those of the chosen {@link Naamgeving}; where a name there differs from the canonical one, the
 * canonical name stands beside it under the key with {@code Canoniek} appended ({@code attribuut} and
 * {@code attribuutCanoniek}). <b>A value</b> is a JSON string for a number (the exact lexeme or plain decimal, with its
 * scale), a date, a date-time, a text and an enumeration value (in the chosen vocabulary), a JSON boolean for a truth
 * value and null for leeg; a timeline is {@code tijdlijn}, a list of {@code {van, tot, waarde}}, and a dimensioned
 * value {@code dimensies}, its values by coordinate. {@code eenheid} is the declared unit of a number.
 *
 * <p><b>Order.</b> The objects of a situation stand in its order. The fixpoint is rendered by
 * {@link FixpuntGegevens}, which the decision service uses as well, with the objects of the situation as the input; its
 * order is described there. A label is a word with a capital, so it never meets the {@code #<n>} of a made object.
 */
final class ScenarioGegevens {

    private ScenarioGegevens() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    /** The situation of {@code s}: {@code peildatum} (the case's evaluation date, which the session carries as its
     *  {@code rekendatum}; null without one), {@code objecten} with their label, type, attribute values,
     *  characteristics and roles, each role with the fact type uchar's scenario resolver found it in, and
     *  {@code parameters}: the values of the chosen parameter set on the peildatum first, then those of the
     *  situation. Every assignment carries its source line ({@code regel}). */
    static ObjectNode situatie(ResolvedScenario s, RegelSpraakModel model, Naamgeving ng) {
        ObjectNode n = NF.objectNode();
        if (s.peildatum() == null) n.putNull("peildatum"); else n.put("peildatum", s.peildatum().toString());
        ArrayNode objecten = n.putArray("objecten");
        for (Instantie i : s.situatie().instanties()) {
            Objecttype ot = i.objecttype();
            ObjectNode on = objecten.addObject();
            on.put("label", i.label());
            naam(on, "objecttype", ng.objecttype(ot.naam), ot.naam);
            ArrayNode attributen = on.putArray("attributen");
            for (AttribuutWaarde a : i.attributen()) {
                ObjectNode an = attributen.addObject();
                naam(an, "attribuut", ng.attribuut(ot, a.attribuut()), a.attribuut());
                Datatype dt = ot.attribuut(a.attribuut());
                if (a.tijdlijn() != null) an.set("tijdlijn", tijdlijn(a.tijdlijn(), dt, ng));
                else waarde(an, a.waarde(), dt, ng);
                eenheid(an, dt);
                an.put("regel", a.line());
            }
            ArrayNode kenmerken = on.putArray("kenmerken");
            for (KenmerkWaarde k : i.kenmerken()) {
                ObjectNode kn = kenmerken.addObject();
                naam(kn, "kenmerk", ng.kenmerk(ot, k.kenmerk()), k.kenmerk());
                if (k.perioden() != null) {
                    ArrayNode perioden = kn.putArray("perioden");
                    for (Periode p : k.perioden())
                        perioden.addObject().put("van", p.van().toString()).put("tot", p.tot().toString());
                }
                kn.put("regel", k.line());
            }
            ArrayNode rollen = on.putArray("rollen");
            for (RolWaarde r : i.rollen()) {
                ObjectNode rn = rollen.addObject();
                naam(rn, "rol", ng.rolVanaf(ot, r.sleutel()), r.sleutel());
                naam(rn, "feittype", ng.feittype(r.feittype().naam), r.feittype().naam);
                naam(rn, "doeltype", ng.objecttype(r.doeltype().naam), r.doeltype().naam);
                ArrayNode labels = rn.putArray("labels");
                for (String l : r.doelLabels()) labels.add(l);
                rn.put("meervoudig", r.meervoudig());
                rn.put("regel", r.line());
            }
        }
        ArrayNode parameters = n.putArray("parameters");
        for (ParameterWaarde p : s.situatie().parameters()) {
            ObjectNode pn = parameters.addObject();
            naam(pn, "parameter", ng.parameter(p.parameter()), p.parameter());
            Datatype dt = parameterType(model, p.parameter());
            if (p.tijdlijn() != null) pn.set("tijdlijn", tijdlijn(p.tijdlijn(), dt, ng));
            else waarde(pn, p.waarde(), dt, ng);
            eenheid(pn, dt);
            pn.put("regel", p.line());
        }
        return n;
    }

    /** Puts on {@code vn} where expectation {@code v} of uchar's report stands in the source: {@code regel}, the line
     *  uchar's scenario resolver gives the expectation ({@link ResolvedScenario.Verwachting#line}, the line of its
     *  first bullet), found by the report's position of the expectation in the case, and {@code regeltekst}, the text
     *  of that line as written. uchar gives the line on which an expectation begins and no end, so the line text is
     *  the whole expectation only where one bullet stands on one line. */
    static void plaats(ObjectNode vn, VerwachtingResultaat v, ResolvedScenario s, List<String> bronregels) {
        int i = v.positie() - 1;
        if (i < 0 || i >= s.verwachtingen().size())
            throw new IllegalStateException("uchars rapport geeft verwachting " + v.positie() + " van het scenario '"
                    + s.naam() + "', dat er " + s.verwachtingen().size() + " heeft");
        int regel = s.verwachtingen().get(i).line();
        if (regel < 1 || regel > bronregels.size())
            throw new IllegalStateException("de verwachting " + v.positie() + " van het scenario '" + s.naam()
                    + "' staat volgens uchar op regel " + regel + ", en de bron telt " + bronregels.size() + " regels");
        vn.put("regel", regel);
        vn.put("regeltekst", bronregels.get(regel - 1).strip());
    }

    /**
     * What stands at the fixpoint of {@code sessie}, the case of scenario {@code s}, as {@link FixpuntGegevens#fixpunt}
     * renders it, with the objects of the situation as the input: each by its label, with the values and the
     * characteristics (those without periods) the situation gives it.
     *
     * @throws FixpuntGegevens.OnweergeefbareWaarde a value in a form this view does not show
     */
    static ObjectNode fixpunt(CaseSessie.Uitkomst sessie, ResolvedScenario s, RegelSpraakModel model, Naamgeving ng) {
        List<FixpuntGegevens.Invoerobject> invoer = new ArrayList<>();
        for (Instantie i : s.situatie().instanties()) {
            Object bean = sessie.beans().get(i.label());
            if (bean == null)
                throw new IllegalStateException("de sessie van het scenario '" + s.naam() + "' heeft geen object '"
                        + i.label() + "' van de situatie");
            Objecttype ot = i.objecttype();
            Map<String, ObjectNode> attributen = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (AttribuutWaarde a : i.attributen()) {
                Datatype dt = ot.attribuut(a.attribuut());
                ObjectNode an = NF.objectNode();
                if (a.tijdlijn() != null) an.set("tijdlijn", tijdlijn(a.tijdlijn(), dt, ng));
                else waarde(an, a.waarde(), dt, ng);
                attributen.put(a.attribuut(), an);
            }
            Set<String> kenmerken = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (KenmerkWaarde k : i.kenmerken()) if (k.perioden() == null) kenmerken.add(k.kenmerk());
            invoer.add(new FixpuntGegevens.Invoerobject(i.label(), ot, bean, attributen, kenmerken));
        }
        if (sessie.beans().size() != invoer.size())
            throw new IllegalStateException("de sessie van het scenario '" + s.naam() + "' heeft de objecten "
                    + sessie.beans().keySet() + ", de situatie " + invoer.size());
        return FixpuntGegevens.fixpunt(sessie.fixpunt(), invoer, model, ng);
    }

    private static void naam(ObjectNode n, String sleutel, String gekozen, String canoniek) {
        FixpuntGegevens.naam(n, sleutel, gekozen, canoniek);
    }

    /** A value of the situation under {@code waarde}: the case value as uchar's resolver normalised it. */
    private static void waarde(ObjectNode n, Waarde w, Datatype dt, Naamgeving ng) {
        if (w.getal() != null) n.put("waarde", w.getal());
        else if (w.datum() != null) n.put("waarde", w.datum().toString());
        else if (w.datumTijd() != null) n.put("waarde", w.datumTijd().toString());
        else if (w.bool() != null) n.put("waarde", w.bool());
        else if (w.tekst() != null) n.put("waarde", w.tekst());
        else if (w.enumWaarde() != null) enumWaarde(n, w.enumWaarde(), dt, ng);
        else throw new IllegalStateException("uchars resolver gaf een waarde zonder variant");
    }

    private static ArrayNode tijdlijn(List<Segment> segmenten, Datatype dt, Naamgeving ng) {
        ArrayNode uit = NF.arrayNode();
        for (Segment seg : segmenten) {
            ObjectNode sn = uit.addObject();
            sn.put("van", seg.van().toString());
            sn.put("tot", seg.tot().toString());
            waarde(sn, seg.waarde(), dt, ng);
        }
        return uit;
    }

    private static void enumWaarde(ObjectNode n, String waarde, Datatype dt, Naamgeving ng) {
        FixpuntGegevens.enumWaarde(n, waarde, dt, ng);
    }

    private static void eenheid(ObjectNode n, Datatype dt) {
        FixpuntGegevens.eenheid(n, dt);
    }

    private static Datatype parameterType(RegelSpraakModel model, String naam) {
        for (RegelSpraakModel.Parameter p : model.parameters.values())
            if (p.naam().equalsIgnoreCase(naam)) return p.datatype();
        throw new IllegalStateException("de situatie geeft de parameter '" + naam + "', die het model niet declareert");
    }

    /** The source split in lines as uchar's lexer counts them: a line ends at a line feed. */
    static List<String> regels(String bron) {
        return List.of(bron.split("\n", -1));
    }
}
