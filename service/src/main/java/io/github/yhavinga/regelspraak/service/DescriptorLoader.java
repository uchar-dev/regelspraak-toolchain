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

    static boolean heeftKenmerk(Objecttype ot, String naam) {
        return ot.kenmerken.stream().anyMatch(k -> k.equalsIgnoreCase(naam));
    }

    /** Whether {@code ot}'s name equals {@code naam} (case-insensitive) — the objecttype identity a uitvoer
     *  selector uses to pick the instances it applies to. */
    static boolean objectMatch(Objecttype ot, String naam) {
        return ot != null && naam != null && ot.naam.equalsIgnoreCase(naam);
    }
}
