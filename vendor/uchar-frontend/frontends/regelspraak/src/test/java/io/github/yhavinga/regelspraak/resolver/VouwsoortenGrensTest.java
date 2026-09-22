package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.ir.Expr;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One boundary, two languages: {@code RuleChecker.VOUWSOORTEN} and the uchar verifier's
 * {@code set_source_exempt} MUST admit the same kinds, because they are the same rule — WF-096's
 * exemption, the position where a set reading is legitimate. The frontend's copy decides what reaches
 * the wire; the verifier's decides what the wire accepts. A frontend fence that is WIDER emits
 * documents the verifier rejects; a NARROWER one refuses sentences the wire allows.
 *
 * <p><b>Both failures have now happened, one from each side, which is why this reads the source
 * instead of pinning a list.</b> On 2026-09-19 the frontend was wider by {@code AANTAL} and emitted
 * ill-formed documents with {@code diagnostics: []}
 * ({@link io.github.yhavinga.regelspraak.AantalSetbronTest}). On 2026-09-20 the verifier grew
 * {@code AANTAL_GEVULDE} with the wire and the frontend stayed at six for two cards; nothing measured
 * it, because no frontend could write the kind yet. A comment claiming the two agree is exactly what
 * was there both times.
 *
 * <p><b>What it reads and why that is the right anchor.</b> The verifier hoists the boundary into one
 * predicate, and that predicate delegates its KIND half to {@code value_fold_name}. This test pins
 * both halves: the arm list of that function (the kinds), and the one line of
 * {@code set_source_exempt} that keys on it (the delegation). Pinning only the list would miss a
 * boundary redrawn on some other set; pinning only the line would miss a kind added or removed.
 *
 * <p>The verifier source is found by walking up from the working directory. A missing file FAILS —
 * a test that skips when its subject is absent is the silent green this repository's gates exist to
 * prevent.
 */
class VouwsoortenGrensTest {

    private static final String CHECKS = "spec/verifier/lib/checks.ml";

    /** {@code | <Ctor> -> Some "NAME"} — one arm of the verifier's kind list. */
    private static final Pattern ARM = Pattern.compile("\\|\\s*\\w+\\s*->\\s*Some\\s+\"([A-Z_]+)\"");

    /** The one line of {@code set_source_exempt} that draws the KIND half on {@code value_fold_name}.
     *  Whitespace-tolerant; the rest of the predicate is the ARITY half, which the frontend spells as
     *  {@code argumenten().size() == 1} at the same place. */
    private static final Pattern DELEGATIE = Pattern.compile(
            "List\\.length\\s+f\\.argumenten\\s*=\\s*1\\s*&&\\s*value_fold_name\\s+f\\.kind\\s*<>\\s*None");

    private static Path verifierBron() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path kandidaat = p.resolve(CHECKS);
            if (Files.isRegularFile(kandidaat)) return kandidaat;
        }
        return fail("kon " + CHECKS + " niet vinden vanaf " + Path.of("").toAbsolutePath()
                + " — deze getuige LEEST de verifiergrens en mag niet overslaan als hij hem niet vindt");
    }

    @Test void de_frontendgrens_draagt_precies_de_waardevouwen_van_de_verifier() throws IOException {
        String bron = Files.readString(verifierBron(), StandardCharsets.UTF_8);
        int start = bron.indexOf("let value_fold_name = function");
        assertTrue(start >= 0, () -> "value_fold_name staat niet meer in " + CHECKS
                + " — de grens is verplaatst en deze getuige moet mee verhuizen");

        List<String> soorten = new ArrayList<>();
        for (String regel : bron.substring(start).lines().skip(1).toList()) {
            Matcher m = ARM.matcher(regel);
            if (m.find()) { soorten.add(m.group(1)); continue; }
            if (regel.strip().startsWith("|")) break;   // the `| _ -> None` catch-all closes the list
            if (!regel.isBlank()) break;
        }
        assertFalse(soorten.isEmpty(), "geen enkele arm gelezen — het patroon sluit niet meer aan op de bron");

        List<String> frontend = RuleChecker.VOUWSOORTEN.stream().map(Expr.FunctieKind::name).sorted().toList();
        assertEquals(soorten.stream().sorted().toList(), frontend,
                "VOUWSOORTEN en de verifier's value_fold_name lopen uiteen — dit is WF-096's ene grens in "
                + "twee talen, dus een soort erbij of eraf hoort in dezelfde beweging aan beide kanten");
    }

    @Test void de_verifier_trekt_de_setgrens_nog_steeds_op_die_lijst() throws IOException {
        // The delegation itself, so a boundary silently redrawn on another set cannot pass the check
        // above by leaving value_fold_name untouched.
        String bron = Files.readString(verifierBron(), StandardCharsets.UTF_8);
        assertTrue(DELEGATIE.matcher(bron).find(),
                "set_source_exempt keyt niet meer op `List.length f.argumenten = 1 && value_fold_name "
                + "f.kind <> None` — de grens is elders getrokken en de frontendkopie hierboven bewijst "
                + "sindsdien niets meer");
    }

    @Test void de_grens_telt_zeven_soorten() {
        // A floor-and-ceiling on the frontend half alone, so a change that edits BOTH sides in one
        // sweep still comes past a human: the count is the number the RuleChecker doc, the WF-096
        // diagnostic and RUNTIME-SEMANTICS ch. 3.4c all spell out.
        assertEquals(7, RuleChecker.VOUWSOORTEN.size(),
                () -> "de waardevouwen zijn " + RuleChecker.VOUWSOORTEN + " — was dat de bedoeling, werk dan "
                        + "ook de zeven-tellingen in RuleChecker en in de WF-096-melding bij");
        assertTrue(RuleChecker.VOUWSOORTEN.contains(Expr.FunctieKind.AANTAL_GEVULDE),
                "de gevulde telling leest een waarde per bijdrager en hoort dus in de exemptie (ch. 3.4c)");
        assertFalse(RuleChecker.VOUWSOORTEN.contains(Expr.FunctieKind.AANTAL),
                "de instantietelling vouwt de leden zelf en hoort er NIET in (besluit 2026-09-19)");
    }
}
