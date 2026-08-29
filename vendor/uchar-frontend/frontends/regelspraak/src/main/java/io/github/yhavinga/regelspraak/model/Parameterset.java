package io.github.yhavinga.regelspraak.model;

import io.github.yhavinga.regelspraak.ir.Geldigheid;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Een <b>parameterset</b>: gedeployde, geldigheids-geversioneerde parameterwaarden als DATA (geen RegelSpraak-syntax).
 * De wet is datum-afhankelijk — tarieven wijzigen elk jaar op 1 januari — dus een parameter draagt niet één waarde
 * maar een lijst {@link WaardeInterval}s, elk met een {@link Geldigheid}-venster. Per verzoek (session) wordt tegen
 * ÉÉN datumautoriteit (de peildatum, die tevens de rekendatum-global is) de vintage geselecteerd: het unieke interval
 * dat de peildatum bevat levert de waarde. Zie de card
 * {@code backlog/new/20260705-15:18-geldigheid-parametersets-peildatum.md}.
 *
 * <p>Sets zijn <b>organisatorisch</b> (bijv. één set per belastingjaar); de waarheid per parameter is de UNIE van alle
 * intervallen over alle sets heen. De hulpfuncties hieronder werken daarom over een {@code List<Parameterset>} en
 * poolen per parameternaam. De semantiek is <b>fail-fast</b>:
 * <ul>
 *   <li><b>overlap</b> — twee intervallen van één parameter die minstens één datum delen is een LAAD-fout
 *       ({@link #overlapFouten});</li>
 *   <li><b>gap</b> — geen interval dat de peildatum bevat is een SESSION-fout die parameter + datum noemt, nooit een
 *       stille default ({@link #waardenOp}).</li>
 * </ul>
 *
 * <p>Bounds zijn inclusief; een {@code null}-bound is open (zie {@link Geldigheid#bevat}). {@link Geldigheid#ALTIJD}
 * (beide null) overlapt elk ander interval en bevat elke datum — het degenererende single-value-geval.
 *
 * @param naam    de naam van de set (organisatorisch; verschijnt op /info)
 * @param waarden per parameternaam de lijst intervallen; de keys zijn de namen zoals in de set gebruikt
 *                (de lader normaliseert naar de canonieke {@code model}-Parameter.naam())
 */
public record Parameterset(String naam, Map<String, List<WaardeInterval>> waarden) {

    /**
     * Eén geversioneerde parameterwaarde: de gedecodeerde {@code waarde} (Java-type per {@link Datatype}, {@code null}
     * = expliciete leegwaarde) geldig binnen {@code geldigheid}.
     */
    public record WaardeInterval(Object waarde, Geldigheid geldigheid) {}

    /**
     * De UNIE van alle intervallen per parameternaam over alle sets heen (de waarheid per parameter). Publiek zodat een
     * compile-time consistentiecheck exact dezelfde
     * pool-semantiek deelt: dekking-per-parameter is de vereniging over alle sets, precies zoals {@link #waardenOp} en
     * {@link #overlapFouten} die zien. De keys zijn de parameternamen zoals ze in de sets staan (canoniek genormaliseerd
     * door de lader). Een lege/afwezige set draagt niets bij.
     */
    public static Map<String, List<WaardeInterval>> pool(List<Parameterset> sets) {
        Map<String, List<WaardeInterval>> pool = new LinkedHashMap<>();
        for (Parameterset s : sets)
            if (s != null && s.waarden() != null)
                for (var e : s.waarden().entrySet())
                    pool.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
        return pool;
    }

    /**
     * Elke overlap-fout over alle sets heen: twee intervallen van ÉÉN parameter (gepoold) die minstens één datum
     * delen. Bounds inclusief, {@code null} = open, dus een open kant overlapt alles aan die kant. Aangrenzend
     * ({@code tot=2025-12-31} naast {@code vanaf=2026-01-01}) overlapt NIET. Elke fout noemt de parameter en beide
     * periodes; leeg = geen overlap.
     */
    public static List<String> overlapFouten(List<Parameterset> sets) {
        List<String> fouten = new ArrayList<>();
        for (var e : pool(sets).entrySet()) {
            List<WaardeInterval> lijst = e.getValue();
            for (int i = 0; i < lijst.size(); i++)
                for (int j = i + 1; j < lijst.size(); j++) {
                    Geldigheid a = lijst.get(i).geldigheid(), b = lijst.get(j).geldigheid();
                    if (overlapt(a, b))
                        fouten.add("parameter '" + e.getKey() + "' heeft overlappende geldigheidsperiodes "
                                + toon(a) + " en " + toon(b));
                }
        }
        return fouten;
    }

    /** Twee inclusieve, open-toegestane intervallen delen minstens één datum. */
    private static boolean overlapt(Geldigheid a, Geldigheid b) {
        // a start na b's einde -> disjunct; b start na a's einde -> disjunct; anders overlap.
        if (a.vanaf() != null && b.tot() != null && a.vanaf().isAfter(b.tot())) return false;
        if (b.vanaf() != null && a.tot() != null && b.vanaf().isAfter(a.tot())) return false;
        return true;
    }

    /**
     * Per parameter (gepoold over alle sets) de waarde van het unieke interval dat {@code peildatum} bevat. Ontbreekt
     * zo'n interval, dan wordt een fout die parameter + peildatum noemt aan {@code fouten} toegevoegd (loud, nooit een
     * default) en de parameter niet in het resultaat opgenomen. De keys van het resultaat zijn de parameternamen zoals
     * ze in de sets staan. Een expliciete leegwaarde ({@code null}) is een geldige uitkomst en wordt wél opgenomen.
     */
    public static Map<String, Object> waardenOp(List<Parameterset> sets, LocalDate peildatum, List<String> fouten) {
        Map<String, Object> uit = new LinkedHashMap<>();
        for (var e : pool(sets).entrySet()) {
            WaardeInterval gekozen = null;
            for (WaardeInterval wi : e.getValue())
                if (wi.geldigheid().bevat(peildatum)) { gekozen = wi; break; }
            if (gekozen == null)
                fouten.add("parameter '" + e.getKey() + "' heeft geen geldige waarde op peildatum " + peildatum);
            else
                uit.put(e.getKey(), gekozen.waarde());
        }
        return uit;
    }

    /**
     * Of de sets meer dan één vintage bevatten: {@code true} zodra enige parameter &gt;1 interval heeft OF enig
     * interval begrensd is (niet {@link Geldigheid#ALTIJD}). Consumers gebruiken dit om een ontbrekende peildatum te
     * eisen (multi-vintage = de peildatum is verplicht; anders is het degenererende single-value-pad toegestaan).
     */
    public static boolean meerVintage(List<Parameterset> sets) {
        for (var e : pool(sets).entrySet()) {
            List<WaardeInterval> lijst = e.getValue();
            if (lijst.size() > 1) return true;
            for (WaardeInterval wi : lijst)
                if (!wi.geldigheid().altijd()) return true;
        }
        return false;
    }

    private static String toon(Geldigheid g) {
        String v = g.vanaf() == null ? "(open)" : g.vanaf().toString();
        String t = g.tot() == null ? "(open)" : g.tot().toString();
        return "[" + v + " t/m " + t + "]";
    }
}
