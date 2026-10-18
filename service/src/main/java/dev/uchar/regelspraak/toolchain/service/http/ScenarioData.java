package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.engine.ScenarioResult.ExpectationResult;
import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.ir.ResolvedScenario.AttributeValue;
import dev.uchar.regelspraak.ir.ResolvedScenario.Instance;
import dev.uchar.regelspraak.ir.ResolvedScenario.CharacteristicValue;
import dev.uchar.regelspraak.ir.ResolvedScenario.ParameterValue;
import dev.uchar.regelspraak.ir.ResolvedScenario.TimeWindow;
import dev.uchar.regelspraak.ir.ResolvedScenario.RoleValue;
import dev.uchar.regelspraak.ir.ResolvedScenario.Segment;
import dev.uchar.regelspraak.ir.ResolvedScenario.ScenarioValue;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.toolchain.engine.CaseSession;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.toolchain.service.FixpointGivens;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The data of one scenario the playground gives beside uchar's report: its situation as uchar's scenario resolver
 * gives it ({@link ResolvedScenario}), the source line of each expectation, and what stands at the fixpoint of the
 * case's own session ({@link CaseSession.Outcome#fixpoint}). It reads uchar's public API and computes nothing itself.
 *
 * <p><b>Names</b> are those of the chosen {@link Naming}; where a name there differs from the canonical one, the
 * canonical name stands beside it under the key with {@code Canoniek} appended ({@code attribuut} and
 * {@code attribuutCanoniek}). <b>A value</b> is a JSON string for a number (the exact lexeme or plain decimal, with its
 * scale), a date, a date-time, a text and an enumeration value (in the chosen vocabulary), a JSON boolean for a truth
 * value and null for leeg; a timeline is {@code timeline}, a list of {@code {van, tot, waarde}}, and a dimensioned
 * value {@code dimensies}, its values by coordinate. {@code unit} is the declared unit of a number.
 *
 * <p><b>Order.</b> The objects of a situation stand in its order. The fixpoint is rendered by
 * {@link FixpointGivens}, which the decision service uses as well, with the objects of the situation as the input; its
 * order is described there. A label is a word with a capital, so it never meets the {@code #<n>} of a made object.
 */
final class ScenarioData {

    private ScenarioData() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    /** The situation of {@code s}: {@code peildatum} (the case's evaluation date, which the session carries as its
     *  {@code rekendatum}; null without one), {@code objects} with their label, type, attribute values,
     *  characteristics and roles, each role with the fact type uchar's scenario resolver found it in, and
     *  {@code parameters}: the values of the chosen parameter set on the peildatum first, then those of the
     *  situation. Every assignment carries its source line ({@code line}). */
    static ObjectNode situation(ResolvedScenario s, RegelSpraakModel model, Naming ng) {
        ObjectNode n = NF.objectNode();
        if (s.asOfDate() == null) n.putNull("peildatum"); else n.put("peildatum", s.asOfDate().toString());
        ArrayNode objects = n.putArray("objecten");
        for (Instance i : s.situation().instances()) {
            Objecttype ot = i.objectType();
            ObjectNode on = objects.addObject();
            on.put("label", i.label());
            name(on, "objecttype", ng.objectType(ot.naam), ot.naam);
            ArrayNode attributes = on.putArray("attributen");
            for (AttributeValue a : i.attributen()) {
                ObjectNode an = attributes.addObject();
                name(an, "attribuut", ng.attribute(ot, a.attribute()), a.attribute());
                Datatype dt = ot.attribute(a.attribute());
                if (a.tijdlijn() != null) an.set("tijdlijn", timeline(a.tijdlijn(), dt, ng));
                else value(an, a.value(), dt, ng);
                unit(an, dt);
                an.put("regel", a.line());
            }
            ArrayNode characteristics = on.putArray("kenmerken");
            for (CharacteristicValue k : i.kenmerken()) {
                ObjectNode kn = characteristics.addObject();
                name(kn, "kenmerk", ng.characteristic(ot, k.characteristic()), k.characteristic());
                if (k.periods() != null) {
                    ArrayNode periods = kn.putArray("perioden");
                    for (TimeWindow p : k.periods())
                        periods.addObject().put("van", p.van().toString()).put("tot", p.until().toString());
                }
                kn.put("regel", k.line());
            }
            ArrayNode roles = on.putArray("rollen");
            for (RoleValue r : i.rollen()) {
                ObjectNode rn = roles.addObject();
                name(rn, "rol", ng.roleFrom(ot, r.key()), r.key());
                name(rn, "feittype", ng.factType(r.factType().naam), r.factType().naam);
                name(rn, "doeltype", ng.objectType(r.targetType().naam), r.targetType().naam);
                ArrayNode labels = rn.putArray("labels");
                for (String l : r.targetLabels()) labels.add(l);
                rn.put("meervoudig", r.meervoudig());
                rn.put("regel", r.line());
            }
        }
        ArrayNode parameters = n.putArray("parameters");
        for (ParameterValue p : s.situation().parameters()) {
            ObjectNode pn = parameters.addObject();
            name(pn, "parameter", ng.parameter(p.parameter()), p.parameter());
            Datatype dt = parameterType(model, p.parameter());
            if (p.tijdlijn() != null) pn.set("tijdlijn", timeline(p.tijdlijn(), dt, ng));
            else value(pn, p.value(), dt, ng);
            unit(pn, dt);
            pn.put("regel", p.line());
        }
        return n;
    }

    /** Puts on {@code vn} where expectation {@code v} of uchar's report stands in the source: {@code line}, the line
     *  uchar's scenario resolver gives the expectation ({@link ResolvedScenario.Expectation#line}, the line of its
     *  first bullet), found by the report's position of the expectation in the case, and {@code regeltekst}, the text
     *  of that line as written. uchar gives the line on which an expectation begins and no end, so the line text is
     *  the whole expectation only where one bullet stands on one line. */
    static void place(ObjectNode vn, ExpectationResult v, ResolvedScenario s, List<String> sourceLines) {
        int i = v.position() - 1;
        if (i < 0 || i >= s.expectations().size())
            throw new IllegalStateException("uchars rapport geeft verwachting " + v.position() + " van het scenario '"
                    + s.naam() + "', dat er " + s.expectations().size() + " heeft");
        int line = s.expectations().get(i).line();
        if (line < 1 || line > sourceLines.size())
            throw new IllegalStateException("de verwachting " + v.position() + " van het scenario '" + s.naam()
                    + "' staat volgens uchar op regel " + line + ", en de bron telt " + sourceLines.size() + " regels");
        vn.put("regel", line);
        vn.put("regeltekst", sourceLines.get(line - 1).strip());
    }

    /**
     * What stands at the fixpoint of {@code session}, the case of scenario {@code s}, as {@link FixpointGivens#fixpoint}
     * renders it, with the objects of the situation as the input: each by its label, with the values and the
     * characteristics (those without periods) the situation gives it.
     *
     * @throws FixpointGivens.UnrenderableValue a value in a form this view does not show
     */
    static ObjectNode fixpoint(CaseSession.Outcome session, ResolvedScenario s, RegelSpraakModel model, Naming ng) {
        List<FixpointGivens.InputObject> input = new ArrayList<>();
        for (Instance i : s.situation().instances()) {
            Object bean = session.beans().get(i.label());
            if (bean == null)
                throw new IllegalStateException("de sessie van het scenario '" + s.naam() + "' heeft geen object '"
                        + i.label() + "' van de situatie");
            Objecttype ot = i.objectType();
            Map<String, ObjectNode> attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (AttributeValue a : i.attributen()) {
                Datatype dt = ot.attribute(a.attribute());
                ObjectNode an = NF.objectNode();
                if (a.tijdlijn() != null) an.set("tijdlijn", timeline(a.tijdlijn(), dt, ng));
                else value(an, a.value(), dt, ng);
                attributes.put(a.attribute(), an);
            }
            Set<String> characteristics = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (CharacteristicValue k : i.kenmerken()) if (k.periods() == null) characteristics.add(k.characteristic());
            input.add(new FixpointGivens.InputObject(i.label(), ot, bean, attributes, characteristics));
        }
        if (session.beans().size() != input.size())
            throw new IllegalStateException("de sessie van het scenario '" + s.naam() + "' heeft de objecten "
                    + session.beans().keySet() + ", de situatie " + input.size());
        return FixpointGivens.fixpoint(session.fixpoint(), input, model, ng);
    }

    private static void name(ObjectNode n, String key, String chosen, String canonical) {
        FixpointGivens.name(n, key, chosen, canonical);
    }

    /** A value of the situation under {@code waarde}: the case value as uchar's resolver normalised it. */
    private static void value(ObjectNode n, ScenarioValue w, Datatype dt, Naming ng) {
        if (w.number() != null) n.put("waarde", w.number());
        else if (w.date() != null) n.put("waarde", w.date().toString());
        else if (w.dateTime() != null) n.put("waarde", w.dateTime().toString());
        else if (w.bool() != null) n.put("waarde", w.bool());
        else if (w.tekst() != null) n.put("waarde", w.tekst());
        else if (w.enumValue() != null) enumValue(n, w.enumValue(), dt, ng);
        else throw new IllegalStateException("uchars resolver gaf een waarde zonder variant");
    }

    private static ArrayNode timeline(List<Segment> segments, Datatype dt, Naming ng) {
        ArrayNode sink = NF.arrayNode();
        for (Segment seg : segments) {
            ObjectNode sn = sink.addObject();
            sn.put("van", seg.van().toString());
            sn.put("tot", seg.until().toString());
            value(sn, seg.value(), dt, ng);
        }
        return sink;
    }

    private static void enumValue(ObjectNode n, String value, Datatype dt, Naming ng) {
        FixpointGivens.enumValue(n, value, dt, ng);
    }

    private static void unit(ObjectNode n, Datatype dt) {
        FixpointGivens.unit(n, dt);
    }

    private static Datatype parameterType(RegelSpraakModel model, String name) {
        for (RegelSpraakModel.Parameter p : model.parameters.values())
            if (p.naam().equalsIgnoreCase(name)) return p.datatype();
        throw new IllegalStateException("de situatie geeft de parameter '" + name + "', die het model niet declareert");
    }

    /** The source split in lines as uchar's lexer counts them: a line ends at a line feed. */
    static List<String> lines(String source) {
        return List.of(source.split("\n", -1));
    }
}
