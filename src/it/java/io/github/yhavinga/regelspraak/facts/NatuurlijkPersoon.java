package io.github.yhavinga.regelspraak.facts;

import java.math.BigDecimal;

/** Fact POJO for the §8.1.6 uniciteit example: a natural person whose burgerservicenummer must be
 *  unique across instances. The generated uniqueness rule binds the key on one instance and fires
 *  when another instance shares it (this != $subject, burgerservicenummer == $k0). */
public class NatuurlijkPersoon {
    private BigDecimal burgerservicenummer;
    public BigDecimal getBurgerservicenummer() { return burgerservicenummer; }
    public void setBurgerservicenummer(BigDecimal v) { this.burgerservicenummer = v; }
}
