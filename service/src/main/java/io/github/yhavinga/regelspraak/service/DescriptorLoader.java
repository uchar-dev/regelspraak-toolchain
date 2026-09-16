package io.github.yhavinga.regelspraak.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads a {@code <naam>.dienst.json} descriptor and validates it against the RESOLVED model — every regel,
 * regelgroep, objecttype, attribuut, kenmerk and parameter it names must exist, or the load fails naming the
 * offending value. ALL violations are collected first, then thrown together as a single {@link DienstFout},
 * so a malformed descriptor surfaces its complete diagnostic set in one round trip (decision 1).
 *
 * <p>The parse is a Jackson bind to {@link DienstDescriptor} (unknown fields ignored, so a descriptor may
 * carry doc-only keys); the numeric values under {@code parameters} are kept as raw {@link JsonNode}s and
 * decoded per-parameter datatype later by the request mapper — never as {@code double}.
 */
public final class DescriptorLoader {

    /** A shared, thread-safe ObjectMapper: BigDecimal-for-floats so no descriptor number is ever a double. */
    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private DescriptorLoader() {}

    /** Parse + validate a descriptor file against {@code model}. */
    public static DienstDescriptor laad(Path descriptorBestand, RegelSpraakModel model) throws IOException {
        return laad(Files.readString(descriptorBestand), model);
    }

    /** Parse + validate a descriptor from its JSON text against {@code model}. */
    public static DienstDescriptor laad(String json, RegelSpraakModel model) {
        DienstDescriptor d;
        try {
            d = MAPPER.readValue(json, DienstDescriptor.class);
        } catch (IOException e) {
            throw new DienstFout("descriptor is geen geldige JSON", List.of(
                    Schending.van(Schending.Soort.MALFORMED, "", e.getMessage())));
        }
        List<Schending> s = valideer(d, model);
        if (!s.isEmpty()) throw new DienstFout("descriptor valideert niet tegen het model", s);
        return d;
    }

    /** Collect every descriptor→model mismatch (no throwing) — the validation core, reused by tests. */
    public static List<Schending> valideer(DienstDescriptor d, RegelSpraakModel model) {
        List<Schending> uit = new ArrayList<>();
        if (d == null) { uit.add(Schending.van(Schending.Soort.MALFORMED, "", "leeg descriptor")); return uit; }
        if (d.dienst() == null || d.dienst().naam() == null || d.dienst().naam().isBlank())
            uit.add(Schending.van(Schending.Soort.MALFORMED, "dienst.naam", "dienst.naam ontbreekt"));

        Set<String> regelnamen = RegelBestandIndex.regelnamen(model);
        Set<String> groepnamen = new java.util.LinkedHashSet<>();
        for (var g : model.regelGroepen) groepnamen.add(g.naam());

        // parameters block
        if (d.parameters() != null)
            for (String p : d.parameters().keySet())
                if (parameter(model, p) == null)
                    uit.add(Schending.van(Schending.Soort.ONBEKENDE_VERWIJZING, "parameters." + p,
                            "onbekende parameter '" + p + "'"));

        List<DienstDescriptor.Entrypoint> eps = d.entrypoints() == null ? List.of() : d.entrypoints();
        for (int i = 0; i < eps.size(); i++) {
            DienstDescriptor.Entrypoint ep = eps.get(i);
            String pad = "entrypoints[" + i + "]";
            if (ep.naam() == null || ep.naam().isBlank())
                uit.add(Schending.van(Schending.Soort.MALFORMED, pad + ".naam", "entrypoint-naam ontbreekt"));
            if (ep.pad() == null || ep.pad().isBlank())
                uit.add(Schending.van(Schending.Soort.MALFORMED, pad + ".pad", "entrypoint-pad ontbreekt"));

            // scope
            if (ep.scope() == null
                    || (ep.scope().regelnamenOfLeeg().isEmpty() && ep.scope().regelgroepenOfLeeg().isEmpty())) {
                uit.add(Schending.van(Schending.Soort.MALFORMED, pad + ".scope",
                        "scope moet minstens één regelnaam of regelgroep noemen"));
            } else {
                List<String> rn = ep.scope().regelnamenOfLeeg();
                for (int j = 0; j < rn.size(); j++)
                    if (!regelnamen.contains(RegelBestandIndex.basisNaam(rn.get(j))))
                        uit.add(Schending.van(Schending.Soort.ONBEKENDE_VERWIJZING,
                                pad + ".scope.regelnamen[" + j + "]", "onbekende regel '" + rn.get(j) + "'"));
                List<String> rg = ep.scope().regelgroepenOfLeeg();
                for (int j = 0; j < rg.size(); j++)
                    if (!groepnamen.contains(rg.get(j)))
                        uit.add(Schending.van(Schending.Soort.ONBEKENDE_VERWIJZING,
                                pad + ".scope.regelgroepen[" + j + "]", "onbekende regelgroep '" + rg.get(j) + "'"));
            }

            // invoer objecttypen
            List<String> inv = ep.invoer() == null ? List.of() : ep.invoer();
            for (int j = 0; j < inv.size(); j++)
                if (objecttype(model, inv.get(j)) == null)
                    uit.add(Schending.van(Schending.Soort.ONBEKENDE_VERWIJZING, pad + ".invoer[" + j + "]",
                            "onbekend objecttype '" + inv.get(j) + "'"));

            // uitvoer: "Objecttype.attribuut" | "Objecttype:kenmerk"
            List<String> uitv = ep.uitvoer() == null ? List.of() : ep.uitvoer();
            for (int j = 0; j < uitv.size(); j++)
                valideerUitvoer(model, uitv.get(j), pad + ".uitvoer[" + j + "]", uit);

            // invoerElementen: same selector syntax as uitvoer — element-level scope-completeness opt-outs
            List<String> invEl = ep.invoerElementenOfLeeg();
            for (int j = 0; j < invEl.size(); j++)
                valideerUitvoer(model, invEl.get(j), pad + ".invoerElementen[" + j + "]", uit);
        }
        return uit;
    }

    /** Validate one {@code "Objecttype.attribuut"} / {@code "Objecttype:kenmerk"} selector. */
    static void valideerUitvoer(RegelSpraakModel model, String sel, String veld, List<Schending> uit) {
        Uitvoerselector u = Uitvoerselector.ontleed(sel);
        if (u == null) {
            uit.add(Schending.van(Schending.Soort.MALFORMED, veld,
                    "uitvoer '" + sel + "' moet 'Objecttype.attribuut' of 'Objecttype:kenmerk' zijn"));
            return;
        }
        Objecttype ot = objecttype(model, u.objecttype());
        if (ot == null) {
            uit.add(Schending.van(Schending.Soort.ONBEKENDE_VERWIJZING, veld,
                    "onbekend objecttype '" + u.objecttype() + "' in uitvoer '" + sel + "'"));
            return;
        }
        if (u.kenmerk()) {
            if (!heeftKenmerk(ot, u.lid()))
                uit.add(Schending.van(Schending.Soort.ONBEKENDE_VERWIJZING, veld,
                        "onbekend kenmerk '" + u.lid() + "' op objecttype '" + u.objecttype() + "'"));
        } else {
            if (ot.attribuut(u.lid()) == null)
                uit.add(Schending.van(Schending.Soort.ONBEKENDE_VERWIJZING, veld,
                        "onbekend attribuut '" + u.lid() + "' op objecttype '" + u.objecttype() + "'"));
        }
    }

    // ---- model lookups (case-insensitive on names, matching the resolver's own tolerance) ----

    static Objecttype objecttype(RegelSpraakModel model, String naam) {
        if (naam == null) return null;
        Objecttype ot = model.objecttypen.get(naam);
        if (ot != null) return ot;
        for (Objecttype o : model.objecttypen.values())
            if (o.naam.equalsIgnoreCase(naam)) return o;
        return null;
    }

    static RegelSpraakModel.Parameter parameter(RegelSpraakModel model, String naam) {
        if (naam == null) return null;
        RegelSpraakModel.Parameter p = model.parameters.get(naam);
        if (p != null) return p;
        for (RegelSpraakModel.Parameter q : model.parameters.values())
            if (q.naam().equalsIgnoreCase(naam)) return q;
        return null;
    }

    /** IR-SPEC ch. 2.2: does {@code ot} OR an ancestor declare kenmerk {@code naam}? An inherited kenmerk is a
     *  kenmerk of the subtype — a rule on the subtype assigns it, the generated bean carries its getter, and a
     *  request may set it — so the own list alone answers "onbekend kenmerk" for a member that exists. The
     *  model's own chain walk ({@link Objecttype#kenmerkNaam}) is the authority; it is case-insensitive exactly
     *  as the old own-list scan was. */
    static boolean heeftKenmerk(Objecttype ot, String naam) {
        return ot != null && ot.kenmerkNaam(naam) != null;
    }

    /** The chain-closed kenmerk NAMES of {@code ot}, nearest declaration first — the list-shaped twin of
     *  {@link #heeftKenmerk}, for the reader that must ENUMERATE the kenmerken rather than ask about one
     *  ({@link OpenApiEmitter}). It lives here so service/ walks the kenmerk chain in exactly one place; the
     *  attribuut side needs no twin because the model already ships {@link Objecttype#attribuutLeden} (the
     *  kenmerken are a List, not a member-keyed map, so {@code opKetenKaart} does not cover them). A nearer
     *  declaration wins and a name is listed once, matched case-insensitively like the lookups above. */
    static List<String> kenmerkLeden(Objecttype ot) {
        List<String> uit = new ArrayList<>();
        if (ot == null) return uit;
        for (Objecttype t : ot.keten())
            for (String k : t.kenmerken)
                if (uit.stream().noneMatch(g -> g.equalsIgnoreCase(k))) uit.add(k);
        return uit;
    }

    /** Whether an instance of {@code ot} is selected by an objecttype named {@code naam} — the ⊑ of IR-SPEC
     *  ch. 2.2, not name equality: a Wrakstuk IS a Strandvondst, so a rule declared on the supertype fires on
     *  it (that is what the transpilers lower) and a descriptor written against the supertype must therefore
     *  reach it too. Used by the uitvoer selectors ({@link AntwoordBouwer}) and the invoer contract
     *  ({@link BeslisserKern}); on a model without subtypes the chain is one element long and this is exactly
     *  the old case-insensitive name equality. An unresolvable {@code naam} selects nothing (it is already a
     *  descriptor-validation error). */
    static boolean objectMatch(RegelSpraakModel model, Objecttype ot, String naam) {
        return ot != null && ot.isEen(objecttype(model, naam));
    }
}
