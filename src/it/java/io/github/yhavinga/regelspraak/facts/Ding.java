package io.github.yhavinga.regelspraak.facts;

import java.time.LocalDate;

/** Fact POJO for the PHASE_VW_EQ differential fixtures (exec-model condition-lowering lifts):
 *  a Boolean ATTRIBUTE (nullable wrapper, so a leegwaarde is a real third state per §5.5.5 and Tabel 44)
 *  compared vs a boolean literal (toka-39 "belaste reis" shape), and a datum attribute compared vs a
 *  datum-typed builtin EXPRESSION "de eerste paasdag van (het jaar uit (...))" (toka-27 "paaskorting" shape). */
public class Ding {
    private Boolean bereikbaarPerTrein;   // §5.6 a Boolean ATTRIBUTE — the nullable wrapper (leeg is a state)
    private boolean belasteReis;          // the kenmerk the boolean-equality rule sets
    private LocalDate vluchtdatum;        // §13.4 the datum attribute the eerste-paasdag rule compares
    private boolean reisMetPaaskorting;   // the kenmerk the datum-expr-equality rule sets

    public Boolean getBereikbaarPerTrein() { return bereikbaarPerTrein; }
    public void setBereikbaarPerTrein(Boolean v) { this.bereikbaarPerTrein = v; }

    public boolean isBelasteReis() { return belasteReis; }
    public void setBelasteReis(boolean v) { this.belasteReis = v; }

    public LocalDate getVluchtdatum() { return vluchtdatum; }
    public void setVluchtdatum(LocalDate v) { this.vluchtdatum = v; }

    public boolean isReisMetPaaskorting() { return reisMetPaaskorting; }
    public void setReisMetPaaskorting(boolean v) { this.reisMetPaaskorting = v; }
}
