package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Feittype;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.model.RegelSpraakModel.Rol;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.toolchain.engine.RolSchrijver;
import dev.uchar.regelspraak.transpiler.kern.Namen;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a {@link Verzoek} JSON tree onto a {@link Wereld} of fact beans + globals + rekendatum, validating
 * every field against the resolved model and collecting typed {@link Schending}s (each carrying its FIELD
 * PATH). It REUSES the existing bridge — {@link Class#forName} over {@code dev.uchar.regelspraak.facts.*} and
 * {@link RegelSpraakRuntime#set} — as uchar's case replay ({@code CaseRunner}) sets a case's inputs; no new
 * reflection layer. On any violation it does not throw: it returns the world it managed to build alongside the
 * violation list, so a caller (fase 3) can turn the full set into one 400 naming every bad field.
 *
 * <p>Unsupported by the service core (a clear violation, never a silent accept): a dimensie- or
 * tijdlijn-valued attribute (no golden situatie uses one). An unknown objecttype/attribuut/kenmerk/parameter/
 * rol, an ill-typed value, an eenheid mismatch, and an unknown enumeratie-waarde are all violations.
 */
public final class VerzoekMapper {

    private final RegelSpraakModel model;
    private final ClassLoader loader;
    private final RolSchrijver rollenSchrijver;

    /** @param loader the classloader the fact beans live on — the entry point's generated bean classloader,
     *                which uchar's build compiled at startup; reflection over it is
     *                classloader-agnostic, only {@link Class#forName} must go through it. */
    public VerzoekMapper(RegelSpraakModel model, ClassLoader loader) {
        this.model = model;
        this.loader = loader;
        this.rollenSchrijver = new RolSchrijver(model);
    }

    /** The mapped world, the id→objecttype map (for the response builder), and the violations produced while
     *  mapping (empty on success). */
    public record Resultaat(Wereld wereld, Map<String, Objecttype> typePerLabel, List<Schending> schendingen) {}

    public Resultaat map(Verzoek verzoek) {
        List<Schending> s = new ArrayList<>();
        Map<String, Object> beans = new LinkedHashMap<>();
        Map<String, Object> globals = new LinkedHashMap<>();
        Map<String, Objecttype> typePerLabel = new LinkedHashMap<>();

        // 1. peildatum / rekendatum — ONE date authority (fase 5). 'peildatum' is the preferred name, 'rekendatum'
        //    the existing alias: the two are the SAME notion. Both present and UNEQUAL is a violation naming BOTH
        //    fields and BOTH values (a caller must never give two conflicting dates); otherwise the date is the one
        //    present (peildatum wins when both are given and equal). The unified date drives Wereld.rekendatum —
        //    which the vintage-selection ALSO reads, so a rule reading Rekendatum and the vintage follow ONE date.
        LocalDate peildatum = leesDatum(verzoek.peildatum(), "peildatum", s);
        LocalDate rekendatum = leesDatum(verzoek.rekendatum(), "rekendatum", s);
        LocalDate datum;
        if (peildatum != null && rekendatum != null && !peildatum.equals(rekendatum)) {
            s.add(Schending.van(Schending.Soort.VERKEERD_TYPE, "peildatum",
                    "peildatum " + peildatum + " en rekendatum " + rekendatum + " zijn dezelfde datumautoriteit"
                            + " maar verschillen — geef er één (of gelijke waarden)"));
            datum = peildatum;
        } else {
            datum = peildatum != null ? peildatum : rekendatum;   // peildatum wins when both equal
        }

        // 2. parameters
        JsonNode params = verzoek.parameters();
        if (params != null && !params.isNull()) {
            if (!params.isObject())
                s.add(Schending.van(Schending.Soort.VERKEERD_TYPE, "parameters", "parameters moet een object zijn"));
            else params.fields().forEachRemaining(e -> {
                String naam = e.getKey();
                RegelSpraakModel.Parameter p = DescriptorLoader.parameter(model, naam);
                if (p == null) {
                    s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, "parameters." + naam,
                            "onbekende parameter '" + naam + "'"));
                    return;
                }
                WaardeCoder.Resultaat r = WaardeCoder.decodeer(e.getValue(), p.datatype(), "parameters." + naam);
                s.addAll(r.schendingen());
                if (r.schendingen().isEmpty())
                    globals.put(globalNaam(p.naam()), r.waarde());
            });
        }

        // 3. instanties (beans + attributen + kenmerken)
        JsonNode insts = verzoek.instanties();
        if (insts == null || insts.isNull()) {
            s.add(Schending.van(Schending.Soort.MALFORMED, "instanties", "instanties ontbreekt"));
        } else if (!insts.isArray()) {
            s.add(Schending.van(Schending.Soort.MALFORMED, "instanties", "instanties moet een array zijn"));
        } else {
            for (int i = 0; i < insts.size(); i++) {
                JsonNode in = insts.get(i);
                String pad = "instanties[" + i + "]";
                JsonNode typeN = in.get("type");
                JsonNode idN = in.get("id");
                if (typeN == null || !typeN.isTextual()) {
                    s.add(Schending.van(Schending.Soort.MALFORMED, pad + ".type", "type ontbreekt of is geen tekst"));
                    continue;
                }
                if (idN == null || !idN.isTextual()) {
                    s.add(Schending.van(Schending.Soort.MALFORMED, pad + ".id", "id ontbreekt of is geen tekst"));
                    continue;
                }
                String id = idN.asText();
                Objecttype ot = DescriptorLoader.objecttype(model, typeN.asText());
                if (ot == null) {
                    s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, pad + ".type",
                            "onbekend objecttype '" + typeN.asText() + "'"));
                    continue;
                }
                if (beans.containsKey(id)) {
                    s.add(Schending.van(Schending.Soort.MALFORMED, pad + ".id",
                            "dubbel id '" + id + "'"));
                    continue;
                }
                Object bean = maakBean(ot, pad, s);
                if (bean == null) continue;
                beans.put(id, bean);
                typePerLabel.put(id, ot);
                zetAttributen(ot, bean, in.get("attributen"), pad + ".attributen", s);
                zetKenmerken(ot, bean, in.get("kenmerken"), pad + ".kenmerken", s);
            }
        }

        // 4. rollen — inline per instance, then top-level relaties (both after all beans exist)
        if (insts != null && insts.isArray())
            for (int i = 0; i < insts.size(); i++) {
                JsonNode in = insts.get(i);
                JsonNode idN = in.get("id");
                if (idN == null || !idN.isTextual()) continue;
                String id = idN.asText();
                Objecttype ot = typePerLabel.get(id);
                if (ot == null) continue;
                zetRollen(ot, id, beans, typePerLabel, in.get("rollen"), "instanties[" + i + "].rollen", s);
            }
        zetRelaties(verzoek.relaties(), beans, typePerLabel, s);
        // a reciprocal self-relation holds both ways: its reverse leg, as uchar's replay writes it
        try {
            rollenSchrijver.spiegelWederkerig(beans, typePerLabel);
        } catch (RolSchrijver.Geweigerd g) {
            s.add(Schending.van(Schending.Soort.VERKEERD_TYPE, "instanties", g.getMessage()));
        }

        return new Resultaat(new Wereld(beans, globals, datum), typePerLabel, s);
    }

    /** Parse an ISO-datum node (yyyy-MM-dd) into a {@link LocalDate}, or {@code null} when absent/null. A
     *  non-textual or unparseable value adds a {@link Schending.Soort#VERKEERD_TYPE} on {@code veld}. Shared by
     *  the {@code peildatum} and {@code rekendatum} legs of the one date authority (fase 5). */
    private static LocalDate leesDatum(JsonNode node, String veld, List<Schending> s) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) {
            s.add(Schending.van(Schending.Soort.VERKEERD_TYPE, veld, veld + " moet een ISO-datum (yyyy-MM-dd) zijn"));
            return null;
        }
        try { return LocalDate.parse(node.asText()); }
        catch (DateTimeParseException e) {
            s.add(Schending.van(Schending.Soort.VERKEERD_TYPE, veld,
                    "'" + node.asText() + "' is geen geldige ISO-datum (yyyy-MM-dd)"));
            return null;
        }
    }

    // ---- bean construction ----

    private Object maakBean(Objecttype ot, String pad, List<Schending> s) {
        String fqcn = FactModelGenerator.PAKKET + "." + Namen.ident(ot.naam, true);
        try {
            return Class.forName(fqcn, true, loader).getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, pad + ".type",
                    "kon geen instantie van objecttype '" + ot.naam + "' (" + fqcn + ") maken: " + e.getMessage()));
            return null;
        }
    }

    private void zetAttributen(Objecttype ot, Object bean, JsonNode attrs, String pad, List<Schending> s) {
        if (attrs == null || attrs.isNull()) return;
        if (!attrs.isObject()) { s.add(Schending.van(Schending.Soort.MALFORMED, pad, "attributen moet een object zijn")); return; }
        attrs.fields().forEachRemaining(e -> {
            String naam = e.getKey();
            String veld = pad + "." + naam;
            Datatype dt = ot.attribuut(naam);
            if (dt == null) {
                s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, veld,
                        "onbekend attribuut '" + naam + "' op objecttype '" + ot.naam + "'"));
                return;
            }
            if (!ot.dimensiesVan(naam).isEmpty()) {
                s.add(Schending.van(Schending.Soort.NIET_ONDERSTEUND, veld,
                        "dimensie-gewaardeerd attribuut '" + naam + "' wordt niet ondersteund door de dienst-kern"));
                return;
            }
            if (isTemporeel(ot, naam)) {
                s.add(Schending.van(Schending.Soort.NIET_ONDERSTEUND, veld,
                        "tijdlijn-gewaardeerd attribuut '" + naam + "' wordt niet ondersteund door de dienst-kern"));
                return;
            }
            WaardeCoder.Resultaat r = WaardeCoder.decodeer(e.getValue(), dt, veld);
            s.addAll(r.schendingen());
            if (r.schendingen().isEmpty()) RegelSpraakRuntime.set(bean, prop(naam), r.waarde());
        });
    }

    private void zetKenmerken(Objecttype ot, Object bean, JsonNode kenm, String pad, List<Schending> s) {
        if (kenm == null || kenm.isNull()) return;
        if (!kenm.isObject()) { s.add(Schending.van(Schending.Soort.MALFORMED, pad, "kenmerken moet een object zijn")); return; }
        kenm.fields().forEachRemaining(e -> {
            String naam = e.getKey();
            String veld = pad + "." + naam;
            if (!DescriptorLoader.heeftKenmerk(ot, naam)) {
                s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, veld,
                        "onbekend kenmerk '" + naam + "' op objecttype '" + ot.naam + "'"));
                return;
            }
            if (isTemporeelKenmerk(ot, naam)) {
                s.add(Schending.van(Schending.Soort.NIET_ONDERSTEUND, veld,
                        "tijdlijn-gewaardeerd kenmerk '" + naam + "' wordt niet ondersteund door de dienst-kern"));
                return;
            }
            JsonNode v = e.getValue();
            if (!v.isBoolean()) {
                s.add(Schending.van(Schending.Soort.VERKEERD_TYPE, veld, "kenmerk '" + naam + "' moet een boolean zijn"));
                return;
            }
            RegelSpraakRuntime.set(bean, prop(naam), v.booleanValue());
        });
    }

    // ---- rollen (inline) ----

    /** The inline roles of one instance, written as uchar's replay writes a case's roles ({@link RolSchrijver}): a
     *  request instance with {@code rollen} is the case instance with {@code roles}, so the service sets the facts
     *  the replay sets. A role name the model does not know is refused before anything is written. */
    private void zetRollen(Objecttype ot, String id, Map<String, Object> beans, Map<String, Objecttype> typePerLabel,
                           JsonNode rollen, String pad, List<Schending> s) {
        if (rollen == null || rollen.isNull()) return;
        if (!rollen.isObject()) { s.add(Schending.van(Schending.Soort.MALFORMED, pad, "rollen moet een object zijn")); return; }
        Object bean = beans.get(id);
        rollen.fields().forEachRemaining(e -> {
            String rol = e.getKey();
            String veld = pad + "." + rol;
            if (model.rolDoeltypeNaarSubject(ot, rol) == null) {
                s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, veld,
                        "onbekende rol '" + rol + "' vanaf objecttype '" + ot.naam + "'"));
                return;
            }
            List<String> doelen = new ArrayList<>();
            JsonNode v = e.getValue();
            if (v.isArray()) v.forEach(n -> doelen.add(n.asText()));
            else doelen.add(v.asText());
            for (String label : doelen) {
                Object doel = beans.get(label);
                if (doel == null) {
                    s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, veld,
                            "rol '" + rol + "' verwijst naar onbekend id '" + label + "'"));
                    continue;
                }
                try {
                    rollenSchrijver.schrijf(bean, ot, rol, doel, typePerLabel.get(label));
                } catch (RolSchrijver.Geweigerd g) {
                    s.add(Schending.van(g.tweedeDoel() ? Schending.Soort.VERKEERD_TYPE : Schending.Soort.NIET_ONDERSTEUND,
                            veld, g.getMessage()));
                }
            }
        });
    }

    // ---- relaties (top-level) — desugar to bean-field writes ----

    private void zetRelaties(JsonNode relaties, Map<String, Object> beans, Map<String, Objecttype> typePerLabel,
                             List<Schending> s) {
        if (relaties == null || relaties.isNull()) return;
        if (!relaties.isArray()) { s.add(Schending.van(Schending.Soort.MALFORMED, "relaties", "relaties moet een array zijn")); return; }
        for (int i = 0; i < relaties.size(); i++) {
            JsonNode rel = relaties.get(i);
            String pad = "relaties[" + i + "]";
            JsonNode ftN = rel.get("feittype");
            JsonNode rollenN = rel.get("rollen");
            if (ftN == null || !ftN.isTextual()) {
                s.add(Schending.van(Schending.Soort.MALFORMED, pad + ".feittype", "feittype ontbreekt of is geen tekst"));
                continue;
            }
            Feittype ft = feittype(ftN.asText());
            if (ft == null) {
                s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, pad + ".feittype",
                        "onbekend feittype '" + ftN.asText() + "'"));
                continue;
            }
            if (rollenN == null || !rollenN.isObject()) {
                s.add(Schending.van(Schending.Soort.MALFORMED, pad + ".rollen", "rollen ontbreekt of is geen object"));
                continue;
            }
            // resolve each named role to its id. A binary feittype has two roles; a §13.3.9.2 WEDERKERIG feittype
            // relates an objecttype to itself through ONE role entry — the request then names that one role TWICE
            // (two ids under the same role name in a JSON array), or gives an array value.
            List<Rol> rollen = ft.rollen;
            if (rollen.size() != 2 && !(ft.wederkerig && rollen.size() == 1)) {
                s.add(Schending.van(Schending.Soort.NIET_ONDERSTEUND, pad,
                        "alleen binaire (of wederkerige) feittypen worden ondersteund door de dienst-kern (feittype '"
                                + ft.naam + "')"));
                continue;
            }
            // collect the (roleName, id) endpoints: two entries even for a wederkerig 1-role feittype (its role
            // appears once but with an array of two ids, or is repeated).
            List<String[]> eindpunten = eindpunten(rollenN, rollen, ft.wederkerig);
            if (eindpunten.size() != 2) {
                s.add(Schending.van(Schending.Soort.MALFORMED, pad + ".rollen",
                        "relatie moet precies twee rol-eindpunten noemen (feittype '" + ft.naam + "')"));
                continue;
            }
            String rolA = eindpunten.get(0)[0], idA = eindpunten.get(0)[1];
            String rolB = eindpunten.get(1)[0], idB = eindpunten.get(1)[1];
            Object beanA = beans.get(idA), beanB = beans.get(idB);
            if (beanA == null) { s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, pad + ".rollen." + rolA, "onbekend id '" + idA + "'")); continue; }
            if (beanB == null) { s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, pad + ".rollen." + rolB, "onbekend id '" + idB + "'")); continue; }
            Objecttype otA = typePerLabel.get(idA), otB = typePerLabel.get(idB);
            // A to-ONE reference field exists on a holder iff navigating holder->doel is NOT to-many; for a 1:1
            // feittype that is BOTH directions (two fields), for a 1:N only the many->one direction. Write every
            // to-one direction so navigation never reads a spuriously-null leg (the §4.20 leegwaarde bug the
            // symmetric relaties path otherwise introduced). The role NAME (rolB / rolA) disambiguates when the
            // same objecttype pair is joined by more than one feittype.
            writeRefAls1op1(beanA, otA, rolB, otB, beanB, ft, pad, s);   // A -> B named after B's role
            writeRefAls1op1(beanB, otB, rolA, otA, beanA, ft, pad, s);   // B -> A named after A's role
        }
    }

    /** Collect exactly the two (roleName, id) endpoints of a relation. For a normal binary feittype that is one
     *  id per role name; for a WEDERKERIG 1-role feittype the single role name carries an array of two ids (or
     *  is listed twice via a JSON array), both under the same role name. */
    private static List<String[]> eindpunten(JsonNode rollenN, List<Rol> rollen, boolean wederkerig) {
        List<String[]> uit = new ArrayList<>();
        if (wederkerig && rollen.size() == 1) {
            String rol = rollen.get(0).naam();
            JsonNode v = rollenN.get(rol);
            if (v == null) v = rollenN.elements().hasNext() ? rollenN.elements().next() : null;
            if (v != null && v.isArray())
                v.forEach(n -> uit.add(new String[]{rol, n.asText()}));
            else if (v != null) uit.add(new String[]{rol, v.asText()});
        } else {
            for (Rol r : rollen) {
                JsonNode v = rollenN.get(r.naam());
                if (v != null && !v.isNull()) uit.add(new String[]{r.naam(), v.asText()});
            }
        }
        return uit;
    }

    /** Set the to-one reference on {@code houderBean} pointing at {@code doelBean}, IFF navigating houder→doel
     *  through the role {@code doelRol} is to-one (a to-many direction has no scalar reference field — its field
     *  lives on the other side, written by the reciprocal call). The role NAME disambiguates a pair joined by
     *  more than one feittype ({@link RegelSpraakModel#verbindendeRolViaNaam}). A direction that resolves to a
     *  real to-one role but whose name cannot be pinned falls back to the by-pair role. */
    private void writeRefAls1op1(Object houderBean, Objecttype houderOt, String doelRol, Objecttype doelOt,
                                 Object doelBean, Feittype ft, String pad, List<Schending> s) {
        if (model.naarVeelRol(houderOt, doelOt, doelRol)) return;   // to-many: no scalar field on this side
        String rol = model.verbindendeRolViaNaam(houderOt, doelOt, doelRol);
        if (rol == null) rol = model.verbindendeRol(houderOt, doelOt);
        if (rol == null) {
            s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, pad,
                    "kon de verbindende rol van '" + houderOt.naam + "' naar '" + doelOt.naam
                            + "' (rol '" + doelRol + "') in feittype '" + ft.naam + "' niet bepalen"));
            return;
        }
        // The reference field only exists on the bean when the fact model actually materialises this relation
        // (a relation no rule navigates has no generated field). A missing setter is a clean "not supported"
        // violation, never a thrown reflective error.
        if (!heeftSetter(houderBean, rol)) {
            s.add(Schending.van(Schending.Soort.NIET_ONDERSTEUND, pad,
                    "feittype '" + ft.naam + "' heeft geen referentieveld '" + rol + "' op objecttype '"
                            + houderOt.naam + "' — deze relatie wordt niet door het feitmodel gematerialiseerd"));
            return;
        }
        RegelSpraakRuntime.set(houderBean, prop(rol), doelBean);
    }

    /** Whether {@code bean} has a single-arg {@code set<Prop>} for the rol/attr {@code naam} — so a
     *  {@link RegelSpraakRuntime#set} will not throw {@link NoSuchMethodException}. */
    private static boolean heeftSetter(Object bean, String naam) {
        String setter = Namen.setter(naam);
        for (var m : bean.getClass().getMethods())
            if (m.getName().equals(setter) && m.getParameterCount() == 1) return true;
        return false;
    }

    private Feittype feittype(String naam) {
        Feittype ft = model.feittypen.get(naam);
        if (ft != null) return ft;
        for (Feittype f : model.feittypen.values()) if (f.naam.equalsIgnoreCase(naam)) return f;
        return null;
    }

    /** Whether attribute {@code naam} on {@code ot} is a §13.3.6 tijdlijn (temporal, {@code List<Segment>}) —
     *  asked over the WHOLE chain (IR-SPEC ch. 2.2), not the own maps. An inherited timeline read as a scalar
     *  is not a missing binding but a wrong judgement: the mapper would accept the value and
     *  {@link RegelSpraakRuntime#set} would then push a {@code BigDecimal} into the {@code List<Segment>} field
     *  the bean inherits — a ClassCastException inside the service instead of a violation naming the field.
     *  The model's chain walk is case-INSENSITIVE, exactly as this scan was. */
    private static boolean isTemporeel(Objecttype ot, String naam) {
        return ot.granulariteitVan(naam) != null || ot.tijdlijnAantalVan(naam) != null;
    }

    /** The kenmerk analogue of {@link #isTemporeel} — a §13.3.6.7 tijdlijn kenmerk (a {@code List<Segment>}
     *  field), which the service core does not support; chain-closed for the same reason. */
    private static boolean isTemporeelKenmerk(Objecttype ot, String naam) {
        return ot.kenmerkGranulariteitVan(naam) != null || ot.kenmerkTijdlijnAantalVan(naam) != null;
    }

    /** The parameter GLOBAL name the exec-model back-end registers — {@link Namen#ident}(naam,false), the
     *  camelCase form (the same formula {@code ExecModelTranspiler.globalNaam} uses; recomputed here because
     *  that helper is package-private in the engine and the engine is consumed as-is). */
    static String globalNaam(String naam) { return Namen.ident(naam, false); }

    /** The bean property name for a RegelSpraak name — {@link Namen#ident}(naam,false), the SAME
     *  camelCase form {@link FactModelGenerator} names the field/getter/setter with (non-letter/digit runs
     *  collapsed, so a hyphenated "datum-tijd …" maps to "datumTijd…"). Handing this to
     *  {@link RegelSpraakRuntime#set}/{@link RegelSpraakRuntime#get} (which prepend set/get + PascalCase over
     *  whitespace-free input) resolves to exactly the generated accessor — reusing the bridge without a naming
     *  divergence. */
    static String prop(String naam) { return Namen.ident(naam, false); }
}
