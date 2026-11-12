package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Designatable;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.transpiler.core.Namen;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a {@code <naam>.service.json} descriptor and validates it against the RESOLVED model — every regel,
 * regelgroep, objecttype, attribuut, kenmerk and parameter it names must exist, or the load fails naming the
 * offending value. ALL violations are collected first, then thrown together as a single {@link ServiceError},
 * so a malformed descriptor surfaces its complete diagnostic set in one round trip (decision 1).
 *
 * <p>The parse is a Jackson bind to {@link ServiceDescriptor} that refuses a key the form does not have
 * ({@link #read}); the numeric values under {@code parameters} are kept as raw {@link JsonNode}s and decoded
 * per-parameter datatype later — never as {@code double}.
 *
 * <p>A name of the model is found as uchar's case reader finds it ({@link #sameName}), and a descriptor that validates
 * goes on in the declared spelling of its names ({@link #declared}).
 */
public final class DescriptorLoader {

    /** A shared, thread-safe ObjectMapper: BigDecimal-for-floats so no descriptor number is ever a double, and a key
     *  the descriptor form does not have is an error ({@link #read}). */
    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    /** The paths of the routes beside the entry points ({@code GET /info}, the playground's): no entry point takes
     *  one. */
    private static final List<String> RESERVED_PATHS = List.of("info", "playground");

    private DescriptorLoader() {}

    /** Parse + validate a descriptor from its JSON text against {@code model}. */
    public static ServiceDescriptor load(String json, RegelSpraakModel model) {
        ServiceDescriptor d = read(json);
        List<Violation> s = validate(d, model);
        if (!s.isEmpty()) throw new ServiceError("descriptor valideert niet tegen het model", s);
        return declared(d, model);
    }

    /** The descriptor in {@code json}, bound to {@link ServiceDescriptor}. A key the descriptor form does not have, at
     *  any level, is a {@link Violation.Kind#MALFORMED} on its path ({@code entrypoints[0].outputs}): an entry point
     *  with a misspelt key would otherwise start and answer nothing, an old name ({@code uitvoer}) included.
     *
     *  @throws ServiceError a text that is no JSON, or a key the form does not have */
    static ServiceDescriptor read(String json) {
        try {
            return MAPPER.readValue(json, ServiceDescriptor.class);
        } catch (UnrecognizedPropertyException e) {
            StringBuilder path = new StringBuilder();
            for (JsonMappingException.Reference r : e.getPath())
                if (r.getFieldName() != null) path.append(path.isEmpty() ? "" : ".").append(r.getFieldName());
                else path.append('[').append(r.getIndex()).append(']');
            throw new ServiceError("descriptor valideert niet", List.of(Violation.of(Violation.Kind.MALFORMED,
                    path.toString(), "onbekende sleutel '" + e.getPropertyName() + "' in de descriptor; de sleutels"
                            + " hier zijn " + e.getKnownPropertyIds())));
        } catch (IOException e) {
            throw new ServiceError("descriptor is geen geldige JSON", List.of(
                    Violation.of(Violation.Kind.MALFORMED, "", e.getMessage())));
        }
    }

    /** {@code d}, which {@link #validate} accepted against {@code model}, with every object type, attribute,
     *  characteristic and parameter it names in its declared spelling: the lookups of the model the service goes on
     *  with compare case-insensitively but take a run of spaces or a tab as written. */
    static ServiceDescriptor declared(ServiceDescriptor d, RegelSpraakModel model) {
        List<ServiceDescriptor.Entrypoint> eps = new ArrayList<>();
        // a descriptor without entrypoints starts, as a service that decides nothing (startWarnings)
        for (ServiceDescriptor.Entrypoint ep : d.entrypoints() == null ? List.<ServiceDescriptor.Entrypoint>of()
                : d.entrypoints())
            eps.add(new ServiceDescriptor.Entrypoint(ep.name(), ep.path(), ep.scope(),
                    ep.input() == null ? null : ep.input().stream().map(o -> objectType(model, o).name).toList(),
                    ep.output() == null ? null : ep.output().stream().map(u -> declaredSelector(model, u)).toList(),
                    ep.inputElements() == null ? null
                            : ep.inputElements().stream().map(u -> declaredSelector(model, u)).toList(),
                    ep.scopeCheckOptOut(), ep.vocabulary(), ep.explanation()));
        java.util.Map<String, JsonNode> parameters = null;
        if (d.parameters() != null) {
            parameters = new java.util.LinkedHashMap<>();
            for (var e : d.parameters().entrySet()) parameters.put(parameter(model, e.getKey()).name(), e.getValue());
        }
        return new ServiceDescriptor(d.service(), eps, parameters, d.parameterSets());
    }

    /** The selector {@code sel}, which {@link #validateOutput} accepted, in the declared spelling of its names. */
    private static String declaredSelector(RegelSpraakModel model, String sel) {
        OutputSelector u = OutputSelector.parse(sel);
        ObjectType ot = objectType(model, u.objectType());
        return u.characteristic() ? ot.name + ":" + characteristic(ot, u.member()).name()
                : ot.name + "." + attribute(ot, u.member()).name();
    }

    /** Collect every descriptor→model mismatch (no throwing) — the validation core, reused by tests. The rule
     *  names of a scope are checked against the forms of {@code model} with its own defaults groups. */
    public static List<Violation> validate(ServiceDescriptor d, RegelSpraakModel model) {
        return validate(d, model, FormIndex.of(model, model.prioritizedDefaults));
    }

    /** As {@link #validate(ServiceDescriptor, RegelSpraakModel)}, with the forms a scope may name given: a rule name
     *  must carry a form of {@code forms} or be a rule the source declares. */
    public static List<Violation> validate(ServiceDescriptor d, RegelSpraakModel model, FormIndex forms) {
        List<Violation> sink = new ArrayList<>();
        if (d == null) { sink.add(Violation.of(Violation.Kind.MALFORMED, "", "leeg descriptor")); return sink; }
        if (d.service() == null || d.service().name() == null || d.service().name().isBlank())
            sink.add(Violation.of(Violation.Kind.MALFORMED, "service.name", "service.name ontbreekt"));
        // 'canoniek' chooses the canonical names in a request, so a vocabulary of that name could never be chosen
        String reserved = Naming.reserved(model);
        if (reserved != null) sink.add(Violation.of(Violation.Kind.MALFORMED, "vocabularies", reserved));

        // parameters block
        if (d.parameters() != null)
            for (String p : d.parameters().keySet())
                if (parameter(model, p) == null)
                    sink.add(Violation.of(Violation.Kind.UNKNOWN_REFERENCE, "parameters." + p,
                            "onbekende parameter '" + p + "'"));

        List<ServiceDescriptor.Entrypoint> eps = d.entrypoints() == null ? List.of() : d.entrypoints();
        for (int i = 0; i < eps.size(); i++) {
            ServiceDescriptor.Entrypoint ep = eps.get(i);
            String path = "entrypoints[" + i + "]";
            if (ep.name() == null || ep.name().isBlank())
                sink.add(Violation.of(Violation.Kind.MALFORMED, path + ".name", "entrypoint-naam ontbreekt"));
            if (ep.path() == null || ep.path().isBlank())
                sink.add(Violation.of(Violation.Kind.MALFORMED, path + ".path", "entrypoint-pad ontbreekt"));
            else
                pathTaken(eps, i, sink);
            if (ep.vocabulary() != null && reserved == null) {
                try {
                    Naming.of(model, ep.vocabulary());
                } catch (Naming.VocabularyError f) {
                    sink.add(Violation.of(Violation.Kind.UNKNOWN_VOCABULARY, path + ".vocabulary", f.getMessage()));
                }
            }

            // scope
            if (ep.scope() == null
                    || (ep.scope().ruleNamesOrEmpty().isEmpty() && ep.scope().ruleGroupsOrEmpty().isEmpty())) {
                sink.add(Violation.of(Violation.Kind.MALFORMED, path + ".scope",
                        "scope moet minstens één regelnaam of regelgroep noemen"));
            } else {
                List<String> rn = ep.scope().ruleNamesOrEmpty();
                for (int j = 0; j < rn.size(); j++)
                    if (!forms.exists(rn.get(j)))
                        sink.add(Violation.of(Violation.Kind.UNKNOWN_REFERENCE,
                                path + ".scope.ruleNames[" + j + "]", "onbekende regel '" + rn.get(j) + "'"));
                List<String> rg = ep.scope().ruleGroupsOrEmpty();
                List<String> unknownGroups = forms.scope(ep.scope()).unknownGroups();
                for (int j = 0; j < rg.size(); j++)
                    if (unknownGroups.contains(rg.get(j)))
                        sink.add(Violation.of(Violation.Kind.UNKNOWN_REFERENCE,
                                path + ".scope.ruleGroups[" + j + "]", "onbekende regelgroep '" + rg.get(j) + "'"));
            }

            // invoer objecttypen
            List<String> inv = ep.input() == null ? List.of() : ep.input();
            for (int j = 0; j < inv.size(); j++)
                if (objectType(model, inv.get(j)) == null)
                    sink.add(Violation.of(Violation.Kind.UNKNOWN_REFERENCE, path + ".input[" + j + "]",
                            "onbekend objecttype '" + inv.get(j) + "'"));

            // uitvoer: "Objecttype.attribute" | "Objecttype:kenmerk"
            List<String> outputs = ep.output() == null ? List.of() : ep.output();
            for (int j = 0; j < outputs.size(); j++) {
                validateOutput(model, outputs.get(j), path + ".output[" + j + "]", sink);
                answerable(model, outputs.get(j), path + ".output[" + j + "]", sink);
            }

            // inputElements: same selector syntax as uitvoer — element-level scope-completeness opt-outs
            List<String> invEl = ep.inputElementsOrEmpty();
            for (int j = 0; j < invEl.size(); j++)
                validateOutput(model, invEl.get(j), path + ".inputElements[" + j + "]", sink);
        }
        return sink;
    }

    /** Adds the violation of the path of entry point {@code i} when it is not one segment of the URL of its own: one
     *  with a slash, one of {@link #RESERVED_PATHS}, or the path of an earlier entry point, which would leave one of the
     *  two without a route. */
    private static void pathTaken(List<ServiceDescriptor.Entrypoint> eps, int i, List<Violation> sink) {
        String p = eps.get(i).path();
        String field = "entrypoints[" + i + "].path";
        if (p.contains("/")) {
            sink.add(Violation.of(Violation.Kind.MALFORMED, field, "het pad '" + p + "' is één segment van de URL,"
                    + " zonder '/'"));
            return;
        }
        if (RESERVED_PATHS.contains(p)) {
            sink.add(Violation.of(Violation.Kind.MALFORMED, field, "het pad '" + p + "' is de route van de dienst zelf;"
                    + " een entrypoint neemt geen " + String.join(" of ", RESERVED_PATHS)));
            return;
        }
        for (int j = 0; j < i; j++)
            if (p.equals(eps.get(j).path())) {
                sink.add(Violation.of(Violation.Kind.MALFORMED, field, "het pad '" + p + "' is al het pad van"
                        + " entrypoints[" + j + "]"));
                return;
            }
    }

    /** Validate one {@code "Objecttype.attribute"} / {@code "Objecttype:kenmerk"} selector. */
    static void validateOutput(RegelSpraakModel model, String sel, String field, List<Violation> sink) {
        OutputSelector u = OutputSelector.parse(sel);
        if (u == null) {
            sink.add(Violation.of(Violation.Kind.MALFORMED, field,
                    "output '" + sel + "' moet 'Objecttype.attribuut' of 'Objecttype:kenmerk' zijn"));
            return;
        }
        ObjectType ot = objectType(model, u.objectType());
        if (ot == null) {
            sink.add(Violation.of(Violation.Kind.UNKNOWN_REFERENCE, field,
                    "onbekend objecttype '" + u.objectType() + "' in output '" + sel + "'"));
            return;
        }
        if (u.characteristic()) {
            if (!hasCharacteristic(ot, u.member()))
                sink.add(Violation.of(Violation.Kind.UNKNOWN_REFERENCE, field,
                        "onbekend kenmerk '" + u.member() + "' op objecttype '" + u.objectType() + "'"));
        } else {
            if (attribute(ot, u.member()) == null)
                sink.add(Violation.of(Violation.Kind.UNKNOWN_REFERENCE, field,
                        "onbekend attribuut '" + u.member() + "' op objecttype '" + u.objectType() + "'"));
        }
    }

    /** Whether the answer can carry the member output selector {@code sel} names: a time-dependent characteristic it
     *  cannot, since the answer gives a characteristic as a boolean and the bean holds its true periods; nor an
     *  attribute of a sum type that is not that of an attribute with absence markers ({@link Markers}), which only a
     *  {@code uchar-ir-v1} document of another producer declares and for which the answer has no form. A selector
     *  {@link #validateOutput} refuses has its violation there. */
    private static void answerable(RegelSpraakModel model, String sel, String field, List<Violation> sink) {
        OutputSelector u = OutputSelector.parse(sel);
        ObjectType ot = u == null ? null : objectType(model, u.objectType());
        Designatable a = ot == null || u.characteristic() ? null : attribute(ot, u.member());
        if (a != null && ot.attribute(a.name()) instanceof Datatype.SumType sum && Markers.of(ot, a.name()) == null)
            sink.add(Violation.of(Violation.Kind.UNSUPPORTED, field, "attribuut '" + u.member() + "' op objecttype '"
                    + u.objectType() + "' heeft het sum type '" + sum.domain() + "', dat geen attribuut met markers is;"
                    + " het antwoord van de dienst heeft er geen vorm voor"));
        Designatable k = ot == null || !u.characteristic() ? null : characteristic(ot, u.member());
        if (k == null) return;
        if (RequestMapper.isTemporalCharacteristic(ot, k.name()))
            sink.add(Violation.of(Violation.Kind.UNSUPPORTED, field, "kenmerk '" + u.member() + "' op objecttype '"
                    + u.objectType() + "' is tijdsafhankelijk; het antwoord van de dienst geeft een kenmerk als"
                    + " boolean en draagt geen perioden"));
    }

    // ---- model lookups, by the name equality of uchar's case reader ----

    /** Whether the written name {@code written} names the declared name {@code declared}: the name equality of uchar's
     *  case reader ({@link Namen#norm}: a run of spaces and tabs as one space, A–Z as a–z, every other character as
     *  written; uchar's IR-SPEC ch. 2.1). */
    static boolean sameName(String declared, String written) {
        return declared != null && written != null && Namen.norm(declared).equals(Namen.norm(written));
    }

    static ObjectType objectType(RegelSpraakModel model, String name) {
        if (name == null) return null;
        ObjectType ot = model.objectTypes.get(name);
        if (ot != null) return ot;
        for (ObjectType o : model.objectTypes.values())
            if (sameName(o.name, name)) return o;
        return null;
    }

    static RegelSpraakModel.Parameter parameter(RegelSpraakModel model, String name) {
        if (name == null) return null;
        RegelSpraakModel.Parameter p = model.parameters.get(name);
        if (p != null) return p;
        for (RegelSpraakModel.Parameter q : model.parameters.values())
            if (sameName(q.name(), name)) return q;
        return null;
    }

    /** Attribute {@code name} of {@code ot} or an ancestor (IR-SPEC ch. 2.2) as a declaration, owned by the type that
     *  declares it, nearest first; null when none does. */
    static Designatable attribute(ObjectType ot, String name) {
        if (ot == null || name == null) return null;
        for (ObjectType t : ot.chain())
            for (String a : t.attributes.keySet())
                if (sameName(a, name)) return Designatable.attribute(t.name, a);
        return null;
    }

    /** The characteristic twin of {@link #attribute}: an inherited characteristic is a characteristic of the subtype —
     *  a rule on the subtype assigns it, the generated bean carries its getter, and a request may set it. */
    static Designatable characteristic(ObjectType ot, String name) {
        if (ot == null || name == null) return null;
        for (ObjectType t : ot.chain())
            for (String k : t.characteristics)
                if (sameName(k, name)) return Designatable.characteristic(t.name, k);
        return null;
    }

    /** Whether {@code ot} or an ancestor declares characteristic {@code name} ({@link #characteristic}). */
    static boolean hasCharacteristic(ObjectType ot, String name) {
        return characteristic(ot, name) != null;
    }

    /** The chain-closed kenmerk NAMES of {@code ot}, nearest declaration first — the list-shaped twin of
     *  {@link #hasCharacteristic}, for the reader that must ENUMERATE the kenmerken rather than ask about one
     *  ({@link OpenApiEmitter}). It lives here so service/ walks the kenmerk chain in exactly one place; the
     *  attribuut side needs no twin because the model already ships {@link Objecttype#attributeMembers} (the
     *  kenmerken are a List, not a member-keyed map, so {@code opKetenKaart} does not cover them). A nearer
     *  declaration wins and a name is listed once, matched as the lookups above match. */
    static List<String> characteristicMembers(ObjectType ot) {
        List<String> sink = new ArrayList<>();
        if (ot == null) return sink;
        for (ObjectType t : ot.chain())
            for (String k : t.characteristics)
                if (sink.stream().noneMatch(g -> sameName(g, k))) sink.add(k);
        return sink;
    }

    /** Whether an instance of {@code ot} is selected by an objecttype named {@code naam} — the ⊑ of IR-SPEC
     *  ch. 2.2, not name equality: a Wrakstuk IS a Strandvondst, so a rule declared on the supertype fires on
     *  it (that is what the transpilers lower) and a descriptor written against the supertype must therefore
     *  reach it too. Used by the uitvoer selectors ({@link AnswerBuilder}) and the invoer contract
     *  ({@link DecisionCore}); on a model without subtypes the chain is one element long and this is exactly
     *  the old case-insensitive name equality. An unresolvable {@code naam} selects nothing (it is already a
     *  descriptor-validation error).
     *
     *  <p>{@link Objecttype#isA} compares by {@code keten().contains}, i.e. by REFERENCE identity, so both
     *  arguments must come from ONE resolved model instance. Every caller today passes the core's own
     *  {@code this.model} and resolves {@code naam} against that same model here, so the condition holds by
     *  construction; a future caller that resolves a second model would have to pass its own, not another's. */
    static boolean objectMatch(RegelSpraakModel model, ObjectType ot, String name) {
        return ot != null && ot.isA(objectType(model, name));
    }
}
