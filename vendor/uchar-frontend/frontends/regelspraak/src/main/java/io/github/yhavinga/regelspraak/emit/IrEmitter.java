package io.github.yhavinga.regelspraak.emit;

import io.github.yhavinga.regelspraak.ir.Bron;
import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Default;
import io.github.yhavinga.regelspraak.ir.ExceptionGroup;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.Geldigheid;
import io.github.yhavinga.regelspraak.ir.PrioritizedDefaults;
import io.github.yhavinga.regelspraak.ir.ResolvedConsistentie;
import io.github.yhavinga.regelspraak.ir.ResolvedDagsoort;
import io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie;
import io.github.yhavinga.regelspraak.ir.ResolvedObjectCreatie;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.ir.ResolvedRegelGroep;
import io.github.yhavinga.regelspraak.ir.ResolvedStartpunt;
import io.github.yhavinga.regelspraak.ir.ResolvedUniciteit;
import io.github.yhavinga.regelspraak.ir.ResolvedUniciteitUnie;
import io.github.yhavinga.regelspraak.ir.ResolvedVerdeling;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.Eenheid;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Dimensie;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Eenheidstelsel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Feittype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Granulariteit;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Parameter;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Rol;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Tijdlijn;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The mirror emitter: a faithful, engine-neutral JSON dump of the complete resolved
 * {@link RegelSpraakModel} — every declaration, every resolved IR list, and the diagnostics.
 * It writes {@code uchar-ir-v1} in its M1 shape: the container is the v1 one
 * ({@code {format, model, rules, diagnostics}}, the declarations as lists, an object type's
 * members as {@code Attribute}/{@code Characteristic} nodes, a domain as a {@code Domain}
 * wrapper), and every other node keeps the Dutch v0 field names EXACTLY as the Java records
 * name them until its own migration step. No filtering: it is the uchar-programme mirror
 * artifact (mirror first, diverge later), warts included. For the M1 containers and nodes (the
 * root, {@code model}, {@code rules}, {@code ObjectType}, {@code Attribute}, {@code Characteristic},
 * {@code TimelineFacet}, {@code Domain}) the key order is the {@code properties} order of the schema
 * ({@code spec/schema/uchar-ir-v1.schema.json}), so a re-emission is byte-identical to a migrated
 * document. A node carried over from v0 keeps the order this emitter has always written it in, which
 * is not always the schema's — {@code PrioritizedDefaults} writes {@code defaults} before
 * {@code ongeordend} (golden {@code jutterswet-wachtgeld}, pinned by gate 10) — until its own M step,
 * as {@code spec/schema/README.md} records.
 *
 * <p>Completeness is compiler-enforced where it can be: the {@link Datatype}, {@link Expr}
 * and {@link Conditie} sealed hierarchies are walked with EXHAUSTIVE switches (no default
 * branch), so adding a subtype fails compilation until this emitter handles it. Every node
 * carries a {@code "node"} discriminator holding the Java record's simple name.
 *
 * <p>Determinism: maps and lists are iterated in their natural (insertion/declaration)
 * order — no sorting — so the output is byte-stable across runs on the same input. Numbers
 * are written via {@link BigDecimal#toPlainString()}. References to declared model objects
 * (an objecttype, a parameter) are emitted by their NAME — the field holds a reference into
 * the top-level declaration maps, so its identity, not a duplicated inline copy, is the
 * faithful serialisation.
 */
public final class IrEmitter {

    /** What the model carries that the wire has no field for, as ERROR diagnostics — today only the
     *  off-calendar fixed startpunt (§13.3.6.3–.7, {@link RegelSpraakModel#vasteStartpunten}). The model
     *  resolves it correctly; dropping it here would lay the tijdlijn on the calendar grid the author did
     *  not declare. {@code Resolver --ir} adds these to the diagnostics so it writes nothing, and
     *  {@link #emit} refuses the same models, so no emission route loses the start point in silence. */
    public static List<Diagnostic> wireWeigeringen(RegelSpraakModel m) {
        return m.vasteStartpunten.stream().map(v -> new Diagnostic(RegelSpraakModel.Severity.ERROR,
                "tijdlijn: het vaste startpunt '" + v.bron() + "' (" + v.sectie() + ") wijkt af van de "
                        + "kalenderperiode en de wire heeft er geen veld voor — de zin wordt geweigerd in "
                        + "plaats van het startpunt stil te laten vallen", v.line())).toList();
    }

    /** Serialise the resolved model to the IR JSON text (two-space indented, trailing newline). */
    public static String emit(RegelSpraakModel m) {
        List<Diagnostic> weigeringen = wireWeigeringen(m);
        if (!weigeringen.isEmpty())
            throw new IllegalStateException("de wire kan dit model niet dragen: " + weigeringen);
        // WF-113 (a): an ERROR means the producer refused the document, so a model that carries one has no
        // document to write; checked here and not only in irModus, so no emission route bypasses it. The
        // message and line clauses are the verifier's too: a document carrying either would be refused there.
        List<Diagnostic> nietWire = m.diagnostics.stream()
                .filter(d -> d.severity() != RegelSpraakModel.Severity.WARNING
                        || d.message() == null || d.message().isBlank() || d.line() < 0).toList();
        if (!nietWire.isEmpty())
            throw new IllegalStateException("IrEmitter: WF-113 (a) — de wire draagt alleen een WARNING met een "
                    + "melding die niet leeg of blanco is en een regel ≥ 0; dit model draagt " + nietWire);
        IrEmitter e = new IrEmitter();
        e.model(m);
        e.w.sb.append('\n');
        return e.w.sb.toString();
    }

    private final Json w = new Json();

    private IrEmitter() {}

    /** The resolved model, kept for the role-binding derivations of the uchar extension
     *  fields (emitted only when uniquely derivable — absent otherwise, never null). */
    private RegelSpraakModel m;
    /** The subject object type of the form being emitted; fold-source role bindings
     *  resolve against it (null outside a subject-bearing form). */
    private Objecttype huidigSubject;
    /** The one AttribuutRef currently being emitted as a fold's SOURCE argument: its relation is the
     *  fold's {@code bronRol}, so it never carries {@code navigatieBindingen} (IR-SPEC ch. 4.1a emitter
     *  discipline; the uchar verifier rejects nav bindings in a fold slot, WF-053). Null otherwise. */
    private Expr foldBron;

    // --- top-level model ---------------------------------------------------

    private void model(RegelSpraakModel m) {
        this.m = m;
        w.beginObject();
        w.key("format").value("uchar-ir-v1");

        w.key("model").beginObject();

        w.key("objectTypes").beginArray();
        for (var en : m.objecttypen.entrySet()) objectType(en.getKey(), en.getValue());
        w.endArray();

        w.key("factTypes").beginArray();
        for (var en : m.feittypen.entrySet()) { eenNaam("feittype", en.getKey(), en.getValue().naam); feittype(en.getValue()); }
        w.endArray();

        w.key("parameters").beginArray();
        for (var en : m.parameters.entrySet()) { eenNaam("parameter", en.getKey(), en.getValue().naam()); parameter(en.getValue()); }
        w.endArray();

        w.key("domains").beginArray();
        for (var en : m.domeinen.entrySet()) domain(en.getKey(), en.getValue());
        w.endArray();

        w.key("unitSystems").beginArray();
        for (var en : m.eenheidstelsels.entrySet()) { eenNaam("eenheidstelsel", en.getKey(), en.getValue().naam); eenheidstelsel(en.getValue()); }
        w.endArray();

        w.key("dimensions").beginArray();
        for (var en : m.dimensies.entrySet()) { eenNaam("dimensie", en.getKey(), en.getValue().naam()); dimensie(en.getValue()); }
        w.endArray();

        w.key("timelines").beginArray();
        for (Tijdlijn t : m.tijdlijnen) tijdlijn(t);
        w.endArray();

        w.key("dayKinds"); stringList(m.dagsoorten);
        w.key("dagsoortPerEenheid"); stringMap(m.dagsoortPerEenheid);

        w.endObject();

        w.key("rules").beginObject();

        w.key("resolvedRegels").beginArray();
        for (ResolvedRegel r : m.resolvedRegels) resolvedRegel(r);
        w.endArray();

        w.key("resolvedDagsoorten").beginArray();
        for (ResolvedDagsoort d : m.resolvedDagsoorten) resolvedDagsoort(d);
        w.endArray();

        w.key("objectCreaties").beginArray();
        for (ResolvedObjectCreatie oc : m.objectCreaties) objectCreatie(oc);
        w.endArray();

        w.key("verdelingen").beginArray();
        for (ResolvedVerdeling v : m.verdelingen) verdeling(v);
        w.endArray();

        w.key("consistenties").beginArray();
        for (ResolvedConsistentie c : m.consistenties) consistentie(c);
        w.endArray();

        w.key("uniciteiten").beginArray();
        for (ResolvedUniciteit u : m.uniciteiten) uniciteit(u);
        w.endArray();

        w.key("uniciteitUnies").beginArray();
        for (ResolvedUniciteitUnie u : m.uniciteitUnies) uniciteitUnie(u);
        w.endArray();

        w.key("feitCreaties").beginArray();
        for (ResolvedFeitCreatie fc : m.feitCreaties) feitCreatie(fc);
        w.endArray();

        w.key("resolvedStartpunten").beginArray();
        for (ResolvedStartpunt s : m.resolvedStartpunten) startpunt(s);
        w.endArray();

        w.key("regelGroepen").beginArray();
        for (ResolvedRegelGroep g : m.regelGroepen) regelGroep(g);
        w.endArray();

        // §4 (PRIORITIZED-DEFAULTS-EVOLUTIE) the defaults extension: an OPTIONAL list, emitted ONLY when the
        // 'Uitzondering op' / 'Uitzonderingen ongeordend' forms produced defaults groups. A flat document
        // produces none, so the key is absent there — the mirror byte-identity of every flat golden holds.
        if (!m.prioritizedDefaults.isEmpty()) {
            w.key("prioritizedDefaults").beginArray();
            for (PrioritizedDefaults p : m.prioritizedDefaults) prioritizedDefaults(p);
            w.endArray();
        }

        w.endObject();

        w.key("diagnostics").beginArray();
        for (Diagnostic d : m.diagnostics) diagnostic(d);
        w.endArray();

        w.endObject();
    }

    /** A declaration list writes its name once, on the node: a map key that disagrees with the node's own
     *  name would lose one of the two spellings without a rule for choosing (delta B2), so it is refused. */
    private static void eenNaam(String soort, String sleutel, String naam) {
        if (!sleutel.equals(naam))
            throw new IllegalStateException("IrEmitter: " + soort + " '" + sleutel + "' draagt de eigen naam '"
                    + naam + "'; de lijstvorm heeft voor beide maar één plek");
    }

    // --- §4 prioritized defaults (extension) -------------------------------

    private void prioritizedDefaults(PrioritizedDefaults p) {
        // navigatieBindingen on a default's own-attribute/characteristic reads resolve against the group subject.
        Objecttype vorigSubject = huidigSubject;
        huidigSubject = m == null ? null : m.objecttypen.get(p.onderwerp());
        w.beginObject();
        w.key("node").value("PrioritizedDefaults");
        w.key("naam").value(p.naam());
        w.key("onderwerp").value(p.onderwerp());
        w.key("kenmerk").value(p.kenmerk());                        // always emitted (null on attribute defaults)
        if (p.attribuut() != null) w.key("attribuut").value(p.attribuut());  // only on attribute defaults (WF-050)
        w.key("defaults").beginArray();
        for (Default d : p.defaults()) dflt(d);
        w.endArray();
        if (p.ongeordend()) w.key("ongeordend").value(true);        // only on a declared-unordered group member
        w.key("bron").value(p.bron());
        w.key("geldigheid"); geldigheid(p.geldigheid());
        w.endObject();
        huidigSubject = vorigSubject;
    }

    // `default` is a Java keyword, so the emit method for a Default node is spelled dflt.
    private void dflt(Default d) {
        w.beginObject();
        w.key("node").value("Default");
        w.key("besluit");                                           // null on an attribute default
        if (d.besluit() == null) w.valueNull(); else w.value(d.besluit().booleanValue());
        w.key("voorwaarde"); conditie(d.voorwaarde());              // null only on the initialisation default
        if (d.consequentie() != null) { w.key("consequentie"); expr(d.consequentie()); }  // attribute defaults only
        if (d.initialisatie()) w.key("initialisatie").value(true);
        if (d.excepties() != null) {
            w.key("excepties").beginObject();
            w.key("ongeordend").value(d.excepties().ongeordend());
            w.key("leden").beginArray();
            for (ExceptionGroup g : d.excepties().leden()) exceptionGroup(g);
            w.endArray();
            w.endObject();
        }
        w.key("bron").value(d.bron());
        w.endObject();
    }

    private void exceptionGroup(ExceptionGroup g) {
        w.beginObject();
        w.key("node").value("ExceptionGroup");
        w.key("defaults").beginArray();
        for (Default d : g.defaults()) dflt(d);
        w.endArray();
        w.key("bron").value(g.bron());
        w.endObject();
    }

    // --- declaration side --------------------------------------------------

    /** IR-SPEC ch. 2.2: the model keeps its eight member-keyed side-maps; the wire carries each member's facets
     *  on the member's own node. A side-map entry the member nodes cannot carry — a key naming no member, a count
     *  or timeline name without a granularity, a null value — is a frontend defect, refused rather than dropped. */
    private void objectType(String sleutel, Objecttype ot) {
        eenNaam("objecttype", sleutel, ot.naam);
        zijkaartenPassen(ot);
        w.beginObject();
        w.key("node").value("ObjectType");
        w.key("name").value(ot.naam);
        // an extension field under the § Method's discipline — optional, never null, written WHOLE or not at
        // all, so a root type emits no key
        if (ot.supertype != null) w.key("supertype").value(ot.supertype.naam);
        w.key("animate").value(ot.bezield);
        if (ot.meervoud != null) w.key("plural").value(ot.meervoud);
        w.key("attributes").beginArray();
        for (var en : ot.attributen.entrySet()) {
            String a = en.getKey();
            w.beginObject();
            w.key("node").value("Attribute");
            w.key("name").value(a);
            w.key("datatype"); datatype(en.getValue());
            String meervoud = ot.attribuutMeervoud.get(a);
            if (meervoud != null) w.key("plural").value(meervoud);
            List<String> dimensies = ot.attribuutDimensies.get(a);
            if (dimensies != null && !dimensies.isEmpty()) { w.key("dimensions"); stringList(dimensies); }
            if (ot.attribuutGranulariteit.containsKey(a))
                timelineFacet(ot.attribuutGranulariteit.get(a), ot.attribuutTijdlijnAantal.get(a),
                        ot.attribuutTijdlijnNaam.get(a));
            w.endObject();
        }
        w.endArray();
        w.key("characteristics").beginArray();
        for (String k : ot.kenmerken) {
            w.beginObject();
            w.key("node").value("Characteristic");
            w.key("name").value(k);
            // ch. 2.2: an untagged characteristic is nominal
            Objecttype.KenmerkSoort soort = ot.kenmerkSoorten.get(k);
            w.key("kind").value(soort == null ? Objecttype.KenmerkSoort.ZELFSTANDIG.name() : soort.name());
            if (ot.kenmerkGranulariteit.containsKey(k))
                timelineFacet(ot.kenmerkGranulariteit.get(k), ot.kenmerkTijdlijnAantal.get(k), null);
            w.endObject();
        }
        w.endArray();
        w.endObject();
    }

    /** A facet exists exactly when the member has a granularity; a member without a count is single-period. */
    private void timelineFacet(Granulariteit granulariteit, Integer aantal, String tijdlijnNaam) {
        w.key("timeline").beginObject();
        w.key("node").value("TimelineFacet");
        w.key("granularity").value(granulariteit.name());
        w.key("count").value(aantal == null ? 1 : aantal);
        if (tijdlijnNaam != null) w.key("timelineName").value(tijdlijnNaam);
        w.endObject();
    }

    private static void zijkaartenPassen(Objecttype ot) {
        java.util.Set<String> attributen = ot.attributen.keySet();
        java.util.Set<String> kenmerken = new java.util.HashSet<>(ot.kenmerken);
        zijkaartPast(ot, "attribuutMeervoud", ot.attribuutMeervoud, attributen, false);
        zijkaartPast(ot, "attribuutDimensies", ot.attribuutDimensies, attributen, true);
        zijkaartPast(ot, "attribuutGranulariteit", ot.attribuutGranulariteit, attributen, true);
        zijkaartPast(ot, "attribuutTijdlijnAantal", ot.attribuutTijdlijnAantal, ot.attribuutGranulariteit.keySet(), true);
        zijkaartPast(ot, "attribuutTijdlijnNaam", ot.attribuutTijdlijnNaam, ot.attribuutGranulariteit.keySet(), true);
        zijkaartPast(ot, "kenmerkSoorten", ot.kenmerkSoorten, kenmerken, false);
        zijkaartPast(ot, "kenmerkGranulariteit", ot.kenmerkGranulariteit, kenmerken, true);
        zijkaartPast(ot, "kenmerkTijdlijnAantal", ot.kenmerkTijdlijnAantal, ot.kenmerkGranulariteit.keySet(), true);
    }

    private static void zijkaartPast(Objecttype ot, String kaart, Map<String, ?> zijkaart,
                                     java.util.Set<String> dragers, boolean geenNull) {
        for (var en : zijkaart.entrySet()) {
            if (!dragers.contains(en.getKey()))
                throw new IllegalStateException("IrEmitter: " + ot.naam + "." + kaart + " heeft een sleutel '"
                        + en.getKey() + "' zonder lid (of zonder granulariteit) om hem te dragen");
            if (geenNull && en.getValue() == null)
                throw new IllegalStateException("IrEmitter: " + ot.naam + "." + kaart + "['" + en.getKey()
                        + "'] is null");
        }
    }

    /** IR-SPEC ch. 2.3: the wrapper carries the name, so the datatype at this declaration position carries no
     *  self-name; at every reference position {@code domein} stays (it is the domain reference there). */
    private void domain(String naam, Datatype dt) {
        if (dt == null)
            throw new IllegalStateException("IrEmitter: domein '" + naam + "' heeft geen datatype");
        w.beginObject();
        w.key("node").value("Domain");
        w.key("name").value(naam);
        w.key("datatype");
        if (dt instanceof Datatype.Enumeratie e) {
            eenNaam("domein", naam, e.domein());
            w.beginObject();
            w.key("node").value("Enumeratie");
            w.key("waarden"); stringList(e.waarden());
            w.endObject();
        } else {
            datatype(dt);
        }
        w.endObject();
    }

    private void parameter(Parameter p) {
        w.beginObject();
        w.key("node").value("Parameter");
        w.key("naam").value(p.naam());
        w.key("datatype"); datatype(p.datatype());
        w.key("granulariteit").value(p.granulariteit() == null ? null : p.granulariteit().name());
        w.endObject();
    }

    private void eenheidstelsel(Eenheidstelsel es) {
        w.beginObject();
        w.key("node").value("Eenheidstelsel");
        w.key("naam").value(es.naam);
        w.key("eenheden").beginObject();
        for (var en : es.eenheden.entrySet()) { w.key(en.getKey()); eenheid(en.getValue()); }
        w.endObject();
        w.key("aliassen").beginObject();
        for (var en : es.aliassen.entrySet()) { w.key(en.getKey()); eenheid(en.getValue()); }
        w.endObject();
        w.endObject();
    }

    private void eenheid(Eenheid e) {
        if (e == null) { w.valueNull(); return; }
        w.beginObject();
        w.key("node").value("Eenheid");
        w.key("afkorting").value(e.afkorting());
        w.key("stelsel").value(e.stelsel());
        w.key("canoniek").value(e.canoniek());
        w.key("factorNaarCanoniek").value(e.factorNaarCanoniek());
        w.key("noemer"); eenheid(e.noemer());
        w.endObject();
    }

    private void feittype(Feittype ft) {
        w.beginObject();
        w.key("node").value("Feittype");
        w.key("naam").value(ft.naam);
        w.key("wederkerig").value(ft.wederkerig);
        w.key("rollen").beginArray();
        for (Rol r : ft.rollen) rol(r);
        w.endArray();
        w.endObject();
    }

    private void rol(Rol r) {
        w.beginObject();
        w.key("node").value("Rol");
        w.key("naam").value(r.naam());
        w.key("objecttypeNaam").value(r.objecttypeNaam());
        w.key("meervoud").value(r.meervoud());
        w.key("meervoudig").value(r.meervoudig());
        w.endObject();
    }

    private void dimensie(Dimensie d) {
        w.beginObject();
        w.key("node").value("Dimensie");
        w.key("naam").value(d.naam());
        w.key("naamMeervoud").value(d.naamMeervoud());
        w.key("voorzetsel").value(d.voorzetsel());
        w.key("waarden"); stringList(d.waarden());
        w.endObject();
    }

    private void tijdlijn(Tijdlijn t) {
        w.beginObject();
        w.key("node").value("Tijdlijn");
        w.key("naam").value(t.naam());
        w.key("variabelStartpunt").value(t.variabelStartpunt());
        w.key("granulariteit").value(t.granulariteit() == null ? null : t.granulariteit().name());
        w.key("aantal").value(t.aantal());
        w.endObject();
    }

    // --- Datatype (exhaustive) --------------------------------------------

    private void datatype(Datatype dt) {
        if (dt == null) { w.valueNull(); return; }
        w.beginObject();
        switch (dt) {
            case Datatype.Numeriek n -> {
                w.key("node").value("Numeriek");
                w.key("geheel").value(n.geheel());
                w.key("decimalen").value(n.decimalen());
                w.key("teken").value(n.teken() == null ? null : n.teken().name());
                w.key("eenheid"); eenheid(n.eenheid());
            }
            case Datatype.Percentage p -> {
                w.key("node").value("Percentage");
                w.key("geheel").value(p.geheel());
                w.key("decimalen").value(p.decimalen());
                w.key("eenheid"); eenheid(p.eenheid());
            }
            case Datatype.Tekst t -> w.key("node").value("Tekst");
            case Datatype.Booleaan b -> w.key("node").value("Booleaan");
            case Datatype.DatumInDagen d -> w.key("node").value("DatumInDagen");
            case Datatype.DatumTijd d -> w.key("node").value("DatumTijd");
            case Datatype.Enumeratie e -> {
                w.key("node").value("Enumeratie");
                w.key("domein").value(e.domein());
                w.key("waarden"); stringList(e.waarden());
            }
            case Datatype.Onbekend o -> {
                w.key("node").value("Onbekend");
                w.key("reden").value(o.reden());
            }
        }
        w.endObject();
    }

    // --- Expr (exhaustive) -------------------------------------------------

    private void expr(Expr e) {
        if (e == null) { w.valueNull(); return; }
        w.beginObject();
        switch (e) {
            case Expr.Literal l -> {
                w.key("node").value("Literal");
                w.key("waarde").value(l.waarde());
                w.key("type"); datatype(l.type());
                w.key("bron").value(l.bron().snippet());
            }
            case Expr.TekstReeks t -> {
                w.key("node").value("TekstReeks");
                w.key("delen").beginArray();
                for (Expr.TekstReeks.Deel d : t.delen()) tekstReeksDeel(d);
                w.endArray();
                w.key("type"); datatype(t.type());
                w.key("bron").value(t.bron().snippet());
            }
            case Expr.Binair b -> {
                w.key("node").value("Binair");
                w.key("op").value(b.op().name());
                w.key("links"); expr(b.links());
                w.key("rechts"); expr(b.rechts());
                w.key("type"); datatype(b.type());
                w.key("bron").value(b.bron().snippet());
            }
            case Expr.Functie f -> {
                w.key("node").value("Functie");
                w.key("kind").value(f.kind().name());
                w.key("argumenten").beginArray();
                // an instance fold's source argument routes through bronRol, never navigatieBindingen
                Expr vorigeFoldBron = foldBron;
                if (f.elementObjecttype() != null && !f.argumenten().isEmpty()
                        && f.argumenten().get(0) instanceof Expr.AttribuutRef)
                    foldBron = f.argumenten().get(0);
                for (Expr a : f.argumenten()) expr(a);
                foldBron = vorigeFoldBron;
                w.endArray();
                w.key("elementObjecttype").value(naamVan(f.elementObjecttype()));
                w.key("leegwaarde"); expr(f.leegwaarde());
                // the filter is a subselectie over the fold ELEMENT: an element-own read there is an own
                // read, not a navigation from the outer subject — emit the filter (and only the filter)
                // with the element as subject so such reads take the own-attribute branch and carry no
                // spurious by-pair navigatieBindingen. Restore the outer subject afterwards.
                w.key("filter");
                Objecttype vorigSubject = huidigSubject;
                if (f.elementObjecttype() != null) huidigSubject = f.elementObjecttype();
                conditie(f.filter());
                huidigSubject = vorigSubject;
                w.key("dimensieSelectie"); dimensieSelectie(f.dimensieSelectie());
                w.key("type"); datatype(f.type());
                w.key("bron").value(f.bron().snippet());
                w.key("granulariteit").value(f.granulariteit() == null ? null : f.granulariteit().name());
                // IR-SPEC ch. 4.3 / WF-102: the range is ONE field with two spellings. A resolved
                // multi-step range goes out as bronPad; the one-step range stays bronRol, and the two are
                // mutually exclusive on the wire — so the path is written INSTEAD OF the binding, never
                // beside it.
                List<RegelSpraakModel.RolBindingRef> pad = bronPad(f);
                if (!pad.isEmpty()) {
                    w.key("bronPad").beginArray();
                    for (RegelSpraakModel.RolBindingRef stap : pad) rolBinding(stap);
                    w.endArray();
                } else {
                    RegelSpraakModel.RolBindingRef rb = bronRolBinding(f);
                    if (rb != null) { w.key("bronRol"); rolBinding(rb); }
                }
            }
            case Expr.Afgerond a -> {
                w.key("node").value("Afgerond");
                w.key("basis"); expr(a.basis());
                w.key("modus").value(a.modus().name());
                w.key("decimalen").value(a.decimalen());
                w.key("type"); datatype(a.type());
                w.key("bron").value(a.bron().snippet());
            }
            case Expr.Begrensd b -> {
                w.key("node").value("Begrensd");
                w.key("basis"); expr(b.basis());
                w.key("minimum"); expr(b.minimum());
                w.key("maximum"); expr(b.maximum());
                w.key("type"); datatype(b.type());
                w.key("bron").value(b.bron().snippet());
            }
            case Expr.AttribuutRef r -> {
                w.key("node").value("AttribuutRef");
                w.key("attribuut").value(r.attribuut());
                w.key("objecttype").value(naamVan(r.objecttype()));
                w.key("onderwerpBron").value(r.onderwerpBron());
                w.key("navigatieRol").value(r.navigatieRol());
                w.key("type"); datatype(r.type());
                w.key("bron").value(r.bron().snippet());
                w.key("set").value(r.set());
                w.key("navigatiePad"); stringList(r.navigatiePad());
                if (r != foldBron) {
                    List<RegelSpraakModel.RolBindingRef> nb = navigatieBindingen(r);
                    if (!nb.isEmpty()) {
                        w.key("navigatieBindingen").beginArray();
                        for (RegelSpraakModel.RolBindingRef b : nb) rolBinding(b);
                        w.endArray();
                    }
                }
            }
            case Expr.DimensieCel c -> {
                w.key("node").value("DimensieCel");
                w.key("attribuut"); expr(c.attribuut());
                w.key("labels"); stringList(c.labels());
                w.key("type"); datatype(c.type());
                w.key("bron").value(c.bron().snippet());
            }
            case Expr.ParameterRef p -> {
                w.key("node").value("ParameterRef");
                w.key("parameter").value(p.parameter() == null ? null : p.parameter().naam());
                w.key("type"); datatype(p.type());
                w.key("bron").value(p.bron().snippet());
            }
            case Expr.VariabeleRef v -> {
                w.key("node").value("VariabeleRef");
                w.key("naam").value(v.naam());
                w.key("type"); datatype(v.type());
                w.key("bron").value(v.bron().snippet());
            }
            // ch. 4.10 the Let cluster. Key ORDER is the schema's required order (node, type, binder,
            // value, body, bron / node, name, type, bron) — gate 2 round-trips these bytes.
            case Expr.Let l -> {
                w.key("node").value("Let");
                w.key("type"); datatype(l.type());
                w.key("binder").value(l.binder());
                w.key("value"); expr(l.value());
                w.key("body"); expr(l.body());
                w.key("bron").value(l.bron().snippet());
            }
            case Expr.BinderRef b -> {
                w.key("node").value("BinderRef");
                w.key("name").value(b.naam());     // the wire field is "name" (not "naam") — ch. 4.10
                w.key("type"); datatype(b.type());
                w.key("bron").value(b.bron().snippet());
            }
            case Expr.Constante c -> {
                w.key("node").value("Constante");
                w.key("naam").value(c.naam());
                w.key("type"); datatype(c.type());
                w.key("bron").value(c.bron().snippet());
            }
            // ch. 4.10's third cluster node is NEVER EMITTER-PRODUCED: no RegelSpraak construct lowers to an
            // IfThenElse, so the resolver builds none and this arm is unreachable from every emitter path.
            // It exists because the union is sealed and exhaustive here — and it REFUSES rather than
            // serialising: an emit path nothing exercises could carry a wrong key order or a wrong field name
            // (the wire spells them condition/then/else) and no gate would ever read those bytes. A loud
            // refusal cannot be silently wrong; a dead serialiser can.
            case Expr.IfThenElse ignored -> throw new UnsupportedOperationException(
                    "IfThenElse is never emitter-produced (IR-SPEC ch. 4.10): the resolver builds none, "
                            + "so this emitter has no verified wire shape for it");
            // the §5.7 concatenation as an instance set (IR-SPEC ch. 4.3, WF-110). No "type" key:
            // the node denotes INSTANCES and carries no datatype, which is why the verifier's type
            // prologue exempts it the way it exempts a Lambda. Each selection's filter is emitted
            // with the SELECTION's element type as the subject, exactly as a Functie's filter is
            // emitted under its elementObjecttype — the condition evaluates per contributor.
            case Expr.InstanceSetUnion u -> {
                w.key("node").value("InstanceSetUnion");
                w.key("branches").beginArray();
                for (Expr.InstanceSetUnion.Tak t : u.takken()) {
                    w.beginObject();
                    w.key("node").value("InstanceSetBranch");
                    w.key("elementType").value(naamVan(t.elementType()));
                    w.key("sourceRole");
                    if (t.bronRol() == null) w.valueNull(); else rolBinding(t.bronRol());
                    w.key("filter");
                    Objecttype vorigSubject = huidigSubject;
                    if (t.elementType() != null) huidigSubject = t.elementType();
                    conditie(t.filter());
                    huidigSubject = vorigSubject;
                    w.key("bron").value(t.bron().snippet());
                    w.endObject();
                }
                w.endArray();
                w.key("bron").value(u.bron().snippet());
            }
            case Expr.Onopgelost o -> {
                w.key("node").value("Onopgelost");
                w.key("type"); datatype(o.type());
                w.key("bron").value(markerBron(o.bron()));
            }
        }
        w.endObject();
    }

    private void tekstReeksDeel(Expr.TekstReeks.Deel d) {
        w.beginObject();
        switch (d) {
            case Expr.TekstReeks.Letterlijk l -> {
                w.key("node").value("Letterlijk");
                w.key("tekst").value(l.tekst());
            }
            case Expr.TekstReeks.Ingevoegd i -> {
                w.key("node").value("Ingevoegd");
                w.key("expr"); expr(i.expr());
            }
        }
        w.endObject();
    }

    private void dimensieSelectie(Expr.DimensieSelectie ds) {
        if (ds == null) { w.valueNull(); return; }
        w.beginObject();
        w.key("node").value("DimensieSelectie");
        w.key("dimensie").value(ds.dimensie());
        w.key("labels"); stringList(ds.labels());
        w.key("bron").value(ds.bron().snippet());
        w.endObject();
    }

    // --- Conditie (exhaustive) --------------------------------------------

    private void conditie(Conditie c) {
        if (c == null) { w.valueNull(); return; }
        w.beginObject();
        switch (c) {
            case Conditie.Vergelijking v -> {
                w.key("node").value("Vergelijking");
                w.key("op").value(v.op().name());
                w.key("links"); expr(v.links());
                w.key("rechts"); expr(v.rechts());
                w.key("periode"); geheelPeriode(v.periode());
                w.key("bron").value(v.bron().snippet());
            }
            case Conditie.Predicaat p -> {
                w.key("node").value("Predicaat");
                w.key("soort").value(p.soort().name());
                w.key("operand"); expr(p.operand());
                w.key("dagsoort").value(p.dagsoort());
                w.key("negatie").value(p.negatie());
                w.key("cijfers").value(p.cijfers());
                w.key("periode"); geheelPeriode(p.periode());
                w.key("bron").value(p.bron().snippet());
            }
            case Conditie.Kenmerk k -> {
                w.key("node").value("Kenmerk");
                w.key("kenmerk").value(k.kenmerk());
                w.key("navigatie").value(k.navigatie());
                w.key("negatie").value(k.negatie());
                w.key("eigenaar").value(naamVan(k.eigenaar()));
                w.key("periode"); geheelPeriode(k.periode());
                w.key("bron").value(k.bron().snippet());
                RegelSpraakModel.RolBindingRef nb = navigatieBinding(k);
                if (nb != null) { w.key("navigatieBinding"); rolBinding(nb); }
            }
            case Conditie.Rollidmaatschap r -> {
                w.key("node").value("Rollidmaatschap");
                w.key("rol").value(r.rol());
                w.key("anderTeFiller").value(naamVan(r.anderTeFiller()));
                w.key("negatie").value(r.negatie());
                w.key("bron").value(r.bron().snippet());
            }
            case Conditie.VerreRollidmaatschap r -> {
                w.key("node").value("VerreRollidmaatschap");
                w.key("filler").value(naamVan(r.filler()));
                w.key("backref").value(r.backref());
                w.key("negatie").value(r.negatie());
                w.key("bron").value(r.bron().snippet());
            }
            case Conditie.Periode p -> {
                w.key("node").value("Periode");
                w.key("vanaf"); expr(p.vanaf());
                w.key("tot"); expr(p.tot());
                w.key("totInclusief").value(p.totInclusief());
                w.key("bron").value(p.bron().snippet());
            }
            case Conditie.Samengesteld s -> {
                w.key("node").value("Samengesteld");
                w.key("kwantor").value(s.kwantor().name());
                w.key("drempel").value(s.drempel());
                w.key("onderdelen").beginArray();
                for (Conditie o : s.onderdelen()) conditie(o);
                w.endArray();
                w.key("bron").value(s.bron().snippet());
            }
            // IR-SPEC ch. 4.5 / WF-108. Key ORDER is the schema's property order (node, kwantor, the
            // counting drempel, elementObjecttype, filter, toets, bron, then the range) — gate 2
            // round-trips these bytes.
            case Conditie.Kwantorvouw k -> {
                w.key("node").value("Kwantorvouw");
                w.key("kwantor").value(k.kwantor().name());
                // WF-108 (e): the threshold is written EXACTLY under a counting quantifier and omitted
                // under ALLE / GEEN — unlike Samengesteld, which always writes it and writes a 0 nobody
                // reads. Emitting it here too would put a number on the wire the kwantor contradicts.
                if (k.kwantor() == Conditie.Kwantor.TEN_MINSTE || k.kwantor() == Conditie.Kwantor.TEN_HOOGSTE
                        || k.kwantor() == Conditie.Kwantor.PRECIES)
                    w.key("drempel").value(k.drempel());
                w.key("elementObjecttype").value(naamVan(k.elementObjecttype()));
                // filter and toets are evaluated PER CONTRIBUTOR with the element as subject, exactly as
                // Functie.filter is: an element-own read there is an own read, not a navigation from the
                // outer subject, so emit both with the element as subject and they take the own-attribute
                // branch and carry no spurious by-pair navigatieBindingen. Restore the outer subject after.
                Objecttype vorigSubject = huidigSubject;
                if (k.elementObjecttype() != null) huidigSubject = k.elementObjecttype();
                w.key("filter"); conditie(k.filter());
                w.key("toets"); conditie(k.toets());
                huidigSubject = vorigSubject;
                w.key("bron").value(k.bron().snippet());
                // the range is ONE field with two spellings, mutually exclusive and omitted when absent —
                // the same discipline the instance fold's bronPad/bronRol follow (WF-102, WF-108 (c))
                if (k.bronPad() != null && !k.bronPad().isEmpty()) {
                    w.key("bronPad").beginArray();
                    for (RegelSpraakModel.RolBindingRef stap : k.bronPad()) rolBinding(stap);
                    w.endArray();
                } else if (k.bronRol() != null) {
                    w.key("bronRol"); rolBinding(k.bronRol());
                }
            }
            case Conditie.Regelversie r -> {
                w.key("node").value("Regelversie");
                w.key("regel").value(r.regel());
                w.key("inconsistent").value(r.inconsistent());
                w.key("bron").value(r.bron().snippet());
            }
            case Conditie.Onopgelost o -> {
                w.key("node").value("Onopgelost");
                w.key("bron").value(markerBron(o.bron()));
            }
        }
        w.endObject();
    }

    /** WF-113 (b): a marker is accounted for by a WARNING that quotes its bron verbatim. A null bron is quoted by
     *  no message and an empty or blank one by every message, so neither accounts for anything and the verifier
     *  refuses the document; the frontend can build one, since {@code DefinitionResolver.bron} gives an empty
     *  snippet for a missing context or an empty match. */
    private static String markerBron(Bron b) {
        String s = b == null ? null : b.snippet();
        if (s == null || s.isBlank())
            throw new IllegalStateException("IrEmitter: WF-113 (b) — een Onopgelost-marker zonder bron ('" + s
                    + "') kan door geen WARNING verantwoord worden");
        return s;
    }

    private void geheelPeriode(Conditie.GeheelPeriode gp) {
        if (gp == null) { w.valueNull(); return; }
        w.beginObject();
        w.key("node").value("GeheelPeriode");
        w.key("jaar").value(gp.jaar());
        w.key("niet").value(gp.niet());
        w.endObject();
    }

    // --- resolved IR lists -------------------------------------------------

    private void resolvedRegel(ResolvedRegel r) {
        huidigSubject = r.doel() instanceof Expr.AttribuutRef a ? a.objecttype() : null;
        w.beginObject();
        w.key("node").value("ResolvedRegel");
        w.key("naam").value(r.naam());
        w.key("soort").value(r.soort() == null ? null : r.soort().name());
        w.key("doel"); expr(r.doel());
        w.key("waarde"); expr(r.waarde());
        w.key("voorwaarde"); conditie(r.voorwaarde());
        w.key("geldigheid"); geldigheid(r.geldigheid());
        // uchar extension field (WF-082): the // Bron: statuutannotaties, written whole or
        // not at all — a blank annotation carries no citation and is dropped, and a rule
        // without annotations leaves no trace on the wire.
        List<String> bronnen = r.bronAnnotaties().stream().filter(s -> !s.isEmpty()).toList();
        if (!bronnen.isEmpty()) { w.key("bronAnnotaties"); stringList(bronnen); }
        w.endObject();
        huidigSubject = null;
    }

    private void resolvedDagsoort(ResolvedDagsoort d) {
        w.beginObject();
        w.key("node").value("ResolvedDagsoort");
        w.key("naam").value(d.naam());
        w.key("voorwaarde"); conditie(d.voorwaarde());
        w.key("geldigheid"); geldigheid(d.geldigheid());
        w.endObject();
    }

    private void objectCreatie(ResolvedObjectCreatie oc) {
        huidigSubject = oc.subject();   // member values and the guard navigate from the creation subject
        w.beginObject();
        w.key("node").value("ResolvedObjectCreatie");
        w.key("naam").value(oc.naam());
        w.key("nieuwType").value(naamVan(oc.nieuwType()));
        w.key("subject").value(naamVan(oc.subject()));
        w.key("toekenningen").beginArray();
        for (ResolvedObjectCreatie.Toekenning t : oc.toekenningen()) {
            w.beginObject();
            w.key("node").value("Toekenning");
            w.key("attribuut").value(t.attribuut());
            w.key("waarde"); expr(t.waarde());
            w.key("kenmerk").value(t.kenmerk());
            w.endObject();
        }
        w.endArray();
        w.key("voorwaarde"); conditie(oc.voorwaarde());
        w.key("geldigheid"); geldigheid(oc.geldigheid());
        w.endObject();
        huidigSubject = null;
    }

    private void verdeling(ResolvedVerdeling v) {
        huidigSubject = v.bronType();
        w.beginObject();
        w.key("node").value("ResolvedVerdeling");
        w.key("naam").value(v.naam());
        w.key("bronType").value(naamVan(v.bronType()));
        w.key("bronAttribuut").value(v.bronAttribuut());
        w.key("doelType").value(naamVan(v.doelType()));
        w.key("doelAttribuut").value(v.doelAttribuut());
        w.key("methode").value(v.methode() == null ? null : v.methode().name());
        w.key("ratoAttribuut").value(v.ratoAttribuut());
        w.key("maximumAttribuut").value(v.maximumAttribuut());
        w.key("afrondingDecimalen").value(v.afrondingDecimalen() == null ? null : v.afrondingDecimalen().intValue());
        w.key("restantAttribuut").value(v.restantAttribuut());
        w.key("groepeerAttribuut").value(v.groepeerAttribuut());
        w.key("afnemend").value(v.afnemend());
        w.key("voorwaarde"); conditie(v.voorwaarde());
        w.key("geldigheid"); geldigheid(v.geldigheid());
        RegelSpraakModel.RolBindingRef rb = m.verbindendeRolBinding(v.bronType(), v.doelType());
        if (rb != null) { w.key("verbindendeRol"); rolBinding(rb); }
        w.endObject();
        huidigSubject = null;
    }

    private void consistentie(ResolvedConsistentie c) {
        huidigSubject = c.subject();
        w.beginObject();
        w.key("node").value("ResolvedConsistentie");
        w.key("naam").value(c.naam());
        w.key("subject").value(naamVan(c.subject()));
        w.key("assertie"); conditie(c.assertie());
        w.key("bron").value(c.bron().snippet());
        w.key("line").value(c.line());
        w.key("geldigheid"); geldigheid(c.geldigheid());
        w.endObject();
        huidigSubject = null;
    }

    private void uniciteit(ResolvedUniciteit u) {
        w.beginObject();
        w.key("node").value("ResolvedUniciteit");
        w.key("naam").value(u.naam());
        w.key("subject").value(naamVan(u.subject()));
        w.key("sleutels"); stringList(u.sleutels());
        w.key("scopeVeld").value(u.scopeVeld());
        w.key("bron").value(u.bron().snippet());
        w.key("geldigheid"); geldigheid(u.geldigheid());
        if (u.scopeRol() != null) { w.key("scopeRol"); rolBinding(u.scopeRol()); }
        w.endObject();
    }

    private void uniciteitUnie(ResolvedUniciteitUnie u) {
        w.beginObject();
        w.key("node").value("ResolvedUniciteitUnie");
        w.key("naam").value(u.naam());
        w.key("bronnen").beginArray();
        for (ResolvedUniciteitUnie.Bron b : u.bronnen()) {
            w.beginObject();
            w.key("node").value("Bron");
            w.key("type").value(naamVan(b.type()));
            w.key("sleutels"); stringList(b.sleutels());
            w.endObject();
        }
        w.endArray();
        w.key("bron").value(u.bron().snippet());
        w.key("geldigheid"); geldigheid(u.geldigheid());
        w.endObject();
    }

    /** The fold-source role binding of an instance fold (the uchar {@code bronRol}
     *  extension): for a member fold ({@code set}) the by-pair search; for the
     *  count-names-a-role wart (an Onbekend-typed source) the declared-name search.
     *  Null — and thus absent from the wire — unless uniquely derivable and
     *  navigation-free; a binding is never emitted as null. */
    private RegelSpraakModel.RolBindingRef bronRolBinding(Expr.Functie f) {
        if (m == null || huidigSubject == null || f.elementObjecttype() == null) return null;
        if (f.argumenten().size() != 1 || !(f.argumenten().get(0) instanceof Expr.AttribuutRef arg)) return null;
        if (arg.navigatieRol() != null || (arg.navigatiePad() != null && !arg.navigatiePad().isEmpty())) return null;
        // classification order mirrors the uchar verifier's fold_source
        // (Onbekend-typed name wart first, member fold second), so the two
        // sides can never classify one source differently
        if (arg.type() instanceof Datatype.Onbekend) return m.elementRolBinding(huidigSubject, arg.attribuut());
        // the by-pair-then-written-name search itself lives in the model: the §8.2 quantifier fold's
        // range is derived at RESOLVE time and must classify a written range exactly as this does
        if (arg.set()) return m.vouwbronBinding(huidigSubject, f.elementObjecttype(), arg.onderwerpBron());
        return null;
    }

    /** The fold-source RANGE of an instance fold, when the resolver bound a multi-step one (the uchar
     *  {@code bronPad} extension, IR-SPEC ch. 4.3 / WF-102). The resolution itself happens once, in the
     *  resolver, off the WRITTEN chain; this only lifts it from the operand — where the written chain is —
     *  onto the fold, where the wire carries it. Empty for every one-step range, which keeps its
     *  {@code bronRol} spelling. Scoped exactly as {@link #bronRolBinding}: a single-argument member fold,
     *  navigation-free. */
    private List<RegelSpraakModel.RolBindingRef> bronPad(Expr.Functie f) {
        if (f.elementObjecttype() == null || f.argumenten().size() != 1) return List.of();
        if (!(f.argumenten().get(0) instanceof Expr.AttribuutRef arg)) return List.of();
        if (!arg.set() || arg.navigatieRol() != null
                || (arg.navigatiePad() != null && !arg.navigatiePad().isEmpty())) return List.of();
        return arg.bereikPad() == null ? List.of() : arg.bereikPad();
    }

    private void rolBinding(RegelSpraakModel.RolBindingRef rb) {
        w.beginObject();
        w.key("node").value("RolBinding");
        w.key("feittype").value(rb.feittype());
        w.key("rol").value(rb.rol());
        w.endObject();
    }

    /** The uchar {@code navigatieBindingen} of a value/condition-position AttribuutRef: one RolBinding
     *  per navigation hop, subject-outward, each naming the reached (far) role — the emit-time
     *  materialisation of the executors' navReader / navKetenReader route. Three shapes: a declared
     *  CHAIN (one binding per pad step, whole-or-not — all hops derivable, or none), a single by-NAME
     *  hop, and a by-PAIR read of an off-subject type. Empty (absent from the wire) when there is no
     *  navigation, no subject anchors the walk, or any hop is not uniquely derivable. */
    private List<RegelSpraakModel.RolBindingRef> navigatieBindingen(Expr.AttribuutRef a) {
        if (m == null || huidigSubject == null || a.objecttype() == null) return List.of();
        if (a.navigatiePad() != null && !a.navigatiePad().isEmpty()) {        // §13.4.1 declared chain
            List<Objecttype> bereikt = m.volgKeten(huidigSubject, a.navigatiePad());
            if (bereikt == null) return List.of();
            List<RegelSpraakModel.RolBindingRef> uit = new java.util.ArrayList<>();
            Objecttype cur = huidigSubject;
            for (int i = 0; i < a.navigatiePad().size(); i++) {
                RegelSpraakModel.RolBindingRef b =
                        m.verbindendeRolViaNaamBinding(cur, bereikt.get(i), a.navigatiePad().get(i));
                if (b == null) return List.of();                             // heel-of-niet over the chain
                uit.add(b);
                cur = bereikt.get(i);
            }
            return uit;
        }
        if (a.navigatieRol() != null) {                                      // single-hop by-name
            RegelSpraakModel.RolBindingRef b =
                    m.verbindendeRolViaNaamBinding(huidigSubject, a.objecttype(), a.navigatieRol());
            return b == null ? List.of() : List.of(b);
        }
        if (a.objecttype() != huidigSubject) {                               // by-pair (off-subject type)
            RegelSpraakModel.RolBindingRef b = m.verbindendeRolBinding(huidigSubject, a.objecttype());
            return b == null ? List.of() : List.of(b);
        }
        return List.of();                                                    // own attribute — not navigated
    }

    /** The uchar {@code navigatieBinding} of a navigated §8.1.8 kenmerk check: the single reached role,
     *  found exactly as {@code RuleChecker.navigatieKenmerk} finds it (the target type via the written
     *  role, then the by-name binding). Null — absent — when the check does not navigate or the hop is
     *  not uniquely derivable. */
    private RegelSpraakModel.RolBindingRef navigatieBinding(Conditie.Kenmerk k) {
        if (m == null || huidigSubject == null || k.navigatie() == null) return null;
        Objecttype doel = m.rolDoeltypeNaarSubject(huidigSubject, k.navigatie());
        return doel == null ? null : m.verbindendeRolViaNaamBinding(huidigSubject, doel, k.navigatie());
    }

    private void feitCreatie(ResolvedFeitCreatie fc) {
        huidigSubject = fc.anker();   // the §13.4.7 guard navigates from the anker
        w.beginObject();
        w.key("node").value("ResolvedFeitCreatie");
        w.key("naam").value(fc.naam());
        w.key("anker").value(naamVan(fc.anker()));
        w.key("doel").value(naamVan(fc.doel()));
        w.key("zetVeld").value(fc.zetVeld());
        w.key("joinVeld").value(fc.joinVeld());
        w.key("tussenstappen").beginArray();
        for (ResolvedFeitCreatie.Tussenstap t : fc.tussenstappen()) {
            w.beginObject();
            w.key("node").value("Tussenstap");
            w.key("type").value(naamVan(t.type()));
            w.key("veld").value(t.veld());
            w.key("viaOuderVeld").value(t.viaOuderVeld());
            if (t.rolBinding() != null) { w.key("rolBinding"); rolBinding(t.rolBinding()); }
            w.endObject();
        }
        w.endArray();
        w.key("voorwaarde"); conditie(fc.voorwaarde());
        w.key("geldigheid"); geldigheid(fc.geldigheid());
        if (fc.feittype() != null) {   // the symbolic form is whole-or-not (WF-028)
            w.key("feittype").value(fc.feittype());
            w.key("ankerRol").value(fc.ankerRol());
            w.key("doelRol").value(fc.doelRol());
            w.key("joinRol"); rolBinding(fc.joinRol());
        }
        w.endObject();
        huidigSubject = null;
    }

    private void startpunt(ResolvedStartpunt s) {
        w.beginObject();
        w.key("node").value("ResolvedStartpunt");
        w.key("tijdlijn").value(s.tijdlijn());
        w.key("objecttype").value(s.objecttype());
        w.key("lid").value(s.lid());
        w.endObject();
    }

    private void regelGroep(ResolvedRegelGroep g) {
        w.beginObject();
        w.key("node").value("ResolvedRegelGroep");
        w.key("naam").value(g.naam());
        w.key("recursief").value(g.recursief());
        w.key("leden"); stringList(g.leden());
        w.key("bron").value(g.bron().snippet());
        w.key("line").value(g.line());
        w.endObject();
    }

    private void diagnostic(Diagnostic d) {
        w.beginObject();
        w.key("node").value("Diagnostic");
        w.key("severity").value(d.severity() == null ? null : d.severity().name());
        w.key("message").value(d.message());
        w.key("line").value(d.line());
        w.endObject();
    }

    private void geldigheid(Geldigheid g) {
        if (g == null) { w.valueNull(); return; }
        w.beginObject();
        w.key("node").value("Geldigheid");
        w.key("vanaf").value(g.vanaf() == null ? null : g.vanaf().toString());
        w.key("tot").value(g.tot() == null ? null : g.tot().toString());
        w.endObject();
    }

    // --- shared helpers ----------------------------------------------------

    private static String naamVan(Objecttype ot) { return ot == null ? null : ot.naam; }

    private void stringList(List<String> xs) {
        w.beginArray();
        if (xs != null) for (String x : xs) w.value(x);
        w.endArray();
    }

    private void stringMap(Map<String, String> map) {
        w.beginObject();
        for (var en : map.entrySet()) w.key(en.getKey()).value(en.getValue());
        w.endObject();
    }

    /**
     * A minimal streaming JSON writer: two-space indentation, correct escaping (quotes,
     * backslash, control chars, non-ASCII as {@code \\uXXXX}), numbers via
     * {@link BigDecimal#toPlainString()}, explicit {@code null}. Containers track their own
     * child count so commas and newlines are placed deterministically; a key emits its own
     * separator and the following value is written inline.
     */
    private static final class Json {
        final StringBuilder sb = new StringBuilder();
        private final java.util.ArrayDeque<int[]> counts = new java.util.ArrayDeque<>();
        private boolean afterKey = false;
        private int depth = 0;

        private void nl() {
            sb.append('\n');
            for (int i = 0; i < depth; i++) sb.append("  ");
        }

        /** Emit the separator+newline before a value that is a direct array element or the root. */
        private void preValue() {
            if (afterKey) { afterKey = false; return; }
            if (!counts.isEmpty()) {
                int[] c = counts.peek();
                if (c[0] > 0) sb.append(',');
                nl();
                c[0]++;
            }
        }

        Json beginObject() { preValue(); sb.append('{'); counts.push(new int[]{0}); depth++; return this; }
        Json endObject() { depth--; int[] c = counts.pop(); if (c[0] > 0) nl(); sb.append('}'); return this; }
        Json beginArray() { preValue(); sb.append('['); counts.push(new int[]{0}); depth++; return this; }
        Json endArray() { depth--; int[] c = counts.pop(); if (c[0] > 0) nl(); sb.append(']'); return this; }

        Json key(String k) {
            int[] c = counts.peek();
            if (c[0] > 0) sb.append(',');
            nl();
            c[0]++;
            escape(k);
            sb.append(": ");
            afterKey = true;
            return this;
        }

        Json value(String s) { preValue(); if (s == null) sb.append("null"); else escape(s); return this; }
        Json value(BigDecimal d) { preValue(); sb.append(d == null ? "null" : d.toPlainString()); return this; }
        Json value(Integer i) { preValue(); sb.append(i == null ? "null" : Integer.toString(i)); return this; }
        Json value(int i) { preValue(); sb.append(Integer.toString(i)); return this; }
        Json value(boolean b) { preValue(); sb.append(b ? "true" : "false"); return this; }
        Json valueNull() { preValue(); sb.append("null"); return this; }

        private void escape(String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                switch (ch) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    default -> {
                        if (ch < 0x20 || ch > 0x7e) sb.append(String.format("\\u%04x", (int) ch));
                        else sb.append(ch);
                    }
                }
            }
            sb.append('"');
        }
    }
}
