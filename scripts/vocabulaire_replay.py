#!/usr/bin/env python3
"""Replay van een uchar-golden met vocabulaires door de JDK-dienst: elke spelling in, elk vocabulaire uit.

Per golden (--golden <golden>=<descriptor>): de dienst start over de bron.rgs van de golden met de descriptor. Het
eerste entrypoint van de descriptor draagt geen standaardvocabulaire, het tweede wel. Elk casebestand wordt een
verzoek in de canonieke spelling, in de spelling van elk vocabulaire van het document en in een gemengde spelling
(per naam om en om canoniek en elk vocabulaire), en elk van die verzoeken vraagt zijn antwoord canoniek en in elk
vocabulaire (?vocabulary=). Het antwoord moet de gepinde verwachtingen van de case geven, in de namen van het
gevraagde vocabulaire, met het objecttype en elke enumeratiewaarde die het verzoek meegaf in die namen. Daarnaast per
spelling het vastgezette invoervocabulaire (?inputVocabulary=), de weigering van een andere spelling onder een
vastgezet vocabulaire, het standaardvocabulaire van het tweede entrypoint, en /info: per vocabulaire de declaraties
zonder benaming. De vertaaltafel is het ir.json van de golden (uchars emitter), dus niet de code van de dienst.

Per getuige (--getuige <naam>=<bron.rgs>,<descriptor>,<getuige.json>): een eigen bron en een lijst getuigenissen,
elk een verzoek met een query en het verwachte antwoord (status, soort en veld van de schending en fragmenten van
haar zin, of attribuutwaarden).

Uitvoer: per golden "<golden>\\tPLAYED\\t<cases>\\t<verzoeken>", per getuige "<naam>\\tPLAYED\\t<getuigenissen>", of
"<naam>\\tFAIL\\t<wat>" / "<naam>\\tEXCLUDE\\t<vorm>". Het oordeel over de set is aan check-tweelingen.sh.
"""
import argparse
import copy
import decimal
import glob
import json
import os
import sys
import tempfile
import urllib.parse

sys.dont_write_bytecode = True  # the import below leaves no __pycache__ in the checkout
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dienst_replay as dr  # noqa: E402

CANONIEK = "canoniek"
VORMLIJSTEN = dr.VORMLIJSTEN


def benamingen(node):
    return {b["vocabulary"]: b["designation"] for b in node.get("designations", [])}


def domein(datatype):
    return datatype.get("domain") if datatype.get("node") == "Enumeration" else None


class Tafel:
    """De namen van een uchar-ir-v1-document per vocabulaire: de benaming daar, anders de canonieke naam. Een canonieke
    keuze laat elke naam zoals het verzoek of de case hem schrijft."""

    def __init__(self, doc):
        self.vocabulaires = [v["name"] for v in doc.get("vocabularies", [])]
        if not self.vocabulaires:
            raise dr.NietUitdrukbaar("het document declareert geen vocabulaire")
        m = doc["model"]
        self.declaraties = []  # (soort, benamingen): elke benoembare declaratie, voor /info
        self.typen, self.supertype, self.attributen, self.kenmerken = {}, {}, {}, {}
        for ot in m["objectTypes"]:
            t = dr.norm(ot["name"])
            self.typen[t] = (ot["name"], benamingen(ot))
            self.supertype[t] = dr.norm(ot["supertype"]) if ot.get("supertype") else None
            self.declaraties.append(("objecttype", benamingen(ot)))
            for a in ot.get("attributes", []):
                self.attributen[(t, dr.norm(a["name"]))] = (a["name"], benamingen(a), domein(a["datatype"]))
                self.declaraties.append(("attribuut", benamingen(a)))
            for k in ot.get("characteristics", []):
                self.kenmerken[(t, dr.norm(k["name"]))] = (k["name"], benamingen(k))
                self.declaraties.append(("kenmerk", benamingen(k)))
        self.rollen = []
        for ft in m["factTypes"]:
            self.declaraties.append(("feittype", benamingen(ft)))
            for r in ft["roles"]:
                self.rollen.append((r["name"], r.get("plural"), benamingen(r)))
                self.declaraties.append(("rol", benamingen(r)))
        self.parameters = {}
        for p in m["parameters"]:
            self.parameters[dr.norm(p["name"])] = (p["name"], benamingen(p), domein(p["datatype"]))
            self.declaraties.append(("parameter", benamingen(p)))
        self.waarden = {}
        for d in m["domains"]:
            dt = d["datatype"]
            if dt.get("node") != "Enumeration":
                continue
            per = {w: {} for w in dt["values"]}
            for e in dt.get("valueDesignations", []):
                per[e["value"]][e["vocabulary"]] = e["designation"]
            for w, b in per.items():
                self.waarden[(dr.norm(d["name"]), dr.norm(w))] = (w, b)
                self.declaraties.append(("waarde", b))
        self.vormen = {}
        for lijst in VORMLIJSTEN:
            for vorm in doc["rules"].get(lijst, []):
                naam = dr.zonder_versie(vorm.get("name") or "")
                if naam and dr.norm(naam) not in self.vormen:
                    self.vormen[dr.norm(naam)] = (naam, benamingen(vorm))
                    self.declaraties.append(("vorm", benamingen(vorm)))
        for g in doc["rules"].get("ruleGroups", []):
            self.declaraties.append(("regelgroep", benamingen(g)))

    @staticmethod
    def _in(geschreven, invoer, keuze):
        if keuze == CANONIEK:
            return geschreven
        return invoer[1].get(keuze, invoer[0])

    def keten(self, t):
        t = dr.norm(t)
        while t is not None:
            yield t
            t = self.supertype.get(t)

    def type(self, t, keuze):
        return self._in(t, self.typen[dr.norm(t)], keuze)

    def attribuut_invoer(self, t, a):
        for s in self.keten(t):
            if (s, dr.norm(a)) in self.attributen:
                return self.attributen[(s, dr.norm(a))]
        raise dr.NietUitdrukbaar(f"attribuut '{a}' van '{t}' staat niet in het document")

    def attribuut(self, t, a, keuze):
        return self._in(a, self.attribuut_invoer(t, a), keuze)

    def kenmerk(self, t, k, keuze):
        for s in self.keten(t):
            if (s, dr.norm(k)) in self.kenmerken:
                return self._in(k, self.kenmerken[(s, dr.norm(k))], keuze)
        raise dr.NietUitdrukbaar(f"kenmerk '{k}' van '{t}' staat niet in het document")

    def rol(self, geschreven, keuze):
        treffers = [r for r in self.rollen
                    if dr.norm(r[0]) == dr.norm(geschreven) or (r[1] and dr.norm(r[1]) == dr.norm(geschreven))]
        if len(treffers) != 1:
            raise dr.NietUitdrukbaar(f"rol '{geschreven}' wijst niet naar één rol van het document")
        return self._in(geschreven, (treffers[0][0], treffers[0][2]), keuze)

    def parameter_invoer(self, p):
        return self.parameters[dr.norm(p)]

    def waarde(self, dom, w, keuze):
        return self._in(w, self.waarden[(dr.norm(dom), dr.norm(w))], keuze)

    def vorm(self, naam, keuze):
        invoer = self.vormen.get(dr.norm(dr.zonder_versie(naam)))
        return naam if invoer is None else self._in(naam, invoer, keuze)

    def zonder_benaming(self, vocabulaire):
        return sum(1 for _, b in self.declaraties if vocabulaire not in b)


def spel(v, tafel, kies):
    """Verzoek v (in de namen van de case) in de spelling die kies(i) voor de i-de naam van het verzoek kiest."""
    teller = [0]

    def k():
        teller[0] += 1
        return kies(teller[0] - 1)

    uit = {s: v[s] for s in ("rekendatum", "peildatum") if s in v}
    if "parameters" in v:
        uit["parameters"] = {}
        for p, w in v["parameters"].items():
            invoer = tafel.parameter_invoer(p)
            naam = tafel._in(p, invoer, k())
            uit["parameters"][naam] = tafel.waarde(invoer[2], w, k()) if invoer[2] and isinstance(w, str) else w
    instanties = []
    for i in v["instanties"]:
        t = i["type"]
        n = {"type": tafel.type(t, k()), "id": i["id"]}
        if "attributen" in i:
            n["attributen"] = {}
            for a, w in i["attributen"].items():
                dom = tafel.attribuut_invoer(t, a)[2]
                n["attributen"][tafel.attribuut(t, a, k())] = \
                    tafel.waarde(dom, w, k()) if dom and isinstance(w, str) else w
        if "kenmerken" in i:
            n["kenmerken"] = {tafel.kenmerk(t, kk, k()): w for kk, w in i["kenmerken"].items()}
        if "rollen" in i:
            n["rollen"] = {tafel.rol(r, k()): ids for r, ids in i["rollen"].items()}
        instanties.append(n)
    uit["instanties"] = instanties
    return uit


def verwacht_in(case, tafel, keuze):
    """De case met de namen van zijn verwachtingen in vocabulaire keuze."""
    c = copy.deepcopy(case)
    typen = {i["label"]: i["objectType"] for i in case["input"]["instances"]}
    for e in c["expected"]:
        soort = e["kind"]
        if soort in ("attributeValue", "attributeEmpty"):
            t = typen[e["label"]]
            dom = tafel.attribuut_invoer(t, e["attribute"])[2]
            if dom and e.get("value") and "enumeration" in e["value"]:
                e["value"] = {"enumeration": tafel.waarde(dom, e["value"]["enumeration"], keuze)}
            e["attribute"] = tafel.attribuut(t, e["attribute"], keuze)
        elif soort in ("characteristicPresent", "characteristicAbsent"):
            e["characteristic"] = tafel.kenmerk(typen[e["label"]], e["characteristic"], keuze)
        elif soort == "inconsistent" and e.get("rule"):
            e["rule"] = tafel.vorm(e["rule"], keuze)
    return c


def namen_in(case, antwoord, tafel, keuze, plek):
    """Het objecttype van elke instantie van het antwoord, en elke enumeratiewaarde die de case invoert en het antwoord
    teruggeeft, in de namen van keuze. Canoniek houdt de dienst de spelling van het verzoek aan, zoals uchars replay die
    van een case: daar geldt de vergelijking op genormaliseerde naam. Geeft het aantal teruggegeven waarden."""
    invoer = {i["label"]: i for i in case["input"]["instances"]}
    echo = 0
    for inst in antwoord.get("instanties", []):
        i = invoer[inst["id"]]
        verwacht = tafel.type(i["objectType"], keuze)
        if inst["type"] != verwacht:
            raise dr.Afwijking(f"{plek}: {inst['id']} heeft type '{inst['type']}', verwacht '{verwacht}'")
        for a, w in (i.get("attributes") or {}).items():
            dom = tafel.attribuut_invoer(i["objectType"], a)[2]
            naam = tafel.attribuut(i["objectType"], a, keuze)
            if dom and w and "enumeration" in w and naam in inst.get("attributen", {}):
                verwacht = tafel.waarde(dom, w["enumeration"], keuze)
                gegeven = inst["attributen"][naam]
                if (dr.norm(gegeven) != dr.norm(verwacht)) if keuze == CANONIEK else (gegeven != verwacht):
                    raise dr.Afwijking(f"{plek}: {inst['id']}.{naam} is '{inst['attributen'][naam]}', "
                                       f"verwacht '{verwacht}'")
                echo += 1
    return echo


def vergelijk(case, antwoord, plek):
    try:
        dr.vergelijk(case, antwoord)
    except dr.Afwijking as a:
        raise dr.Afwijking(f"{plek}: {a}")


def speel_golden(golden, map_, descriptor_pad):
    doc = json.load(open(os.path.join(map_, "ir.json")))
    tafel = Tafel(doc)
    cases = []
    for pad in sorted(glob.glob(os.path.join(map_, "case*.json"))):
        c = json.load(open(pad))
        if "expectedFault" not in c:
            cases.append((os.path.basename(pad), c))
    if not cases:
        raise dr.NietUitdrukbaar("geen casebestand")
    descriptor = json.load(open(descriptor_pad))
    eps = descriptor["entrypoints"]
    if len(eps) < 2 or "vocabulaire" in eps[0] or not eps[1].get("vocabulaire"):
        raise dr.Afwijking("de descriptor heeft een eerste entrypoint zonder en een tweede met standaardvocabulaire nodig")
    pad, standaard_pad, standaard = "/" + eps[0]["pad"], "/" + eps[1]["pad"], eps[1]["vocabulaire"]
    keuzes = [CANONIEK] + tafel.vocabulaires
    spellingen = [(k, (lambda k: lambda i: k)(k)) for k in keuzes] + [("gemengd", lambda i: keuzes[i % len(keuzes)])]
    verzoeken = echo = 0
    bron = os.path.join(map_, "bron.rgs")
    with tempfile.TemporaryDirectory(prefix="vocabulaire-replay-") as werkmap:
        with dr.Dienst(descriptor_pad, bron, werkmap) as dienst:
            def vraag(pad_, query, lichaam, plek):
                nonlocal verzoeken
                verzoeken += 1
                status, tekst = dienst.vraag("POST", pad_ + query, lichaam)
                if status != 200:
                    raise dr.Afwijking(f"{plek}: {status} {tekst[:500]}")
                return dr.lees_json(tekst)

            for naam, c in cases:
                canoniek = dr.verzoek(c)
                for spelling, kies in spellingen:
                    lichaam = spel(canoniek, tafel, kies)
                    for uit in keuzes:
                        plek = f"{naam}, spelling {spelling}, vocabulaire {uit}"
                        antwoord = vraag(pad, "?vocabulary=" + urllib.parse.quote(uit), lichaam, plek)
                        vergelijk(verwacht_in(c, tafel, uit), antwoord, plek)
                        echo += namen_in(c, antwoord, tafel, uit, plek)
                    if spelling != "gemengd":
                        plek = f"{naam}, spelling {spelling}, invoervocabulaire {spelling}"
                        antwoord = vraag(pad, "?inputVocabulary=" + urllib.parse.quote(spelling), lichaam, plek)
                        vergelijk(c, antwoord, plek)
                plek = f"{naam}, standaardvocabulaire {standaard} van {standaard_pad}"
                antwoord = vraag(standaard_pad, "", canoniek, plek)
                vergelijk(verwacht_in(c, tafel, standaard), antwoord, plek)
                echo += namen_in(c, antwoord, tafel, standaard, plek)
                # a pinned vocabulary reads only its own effective names: another spelling is refused, by name
                ander, vast = tafel.vocabulaires[0], tafel.vocabulaires[-1]
                verzoeken += 1
                status, tekst = dienst.vraag("POST", pad + "?inputVocabulary=" + urllib.parse.quote(vast),
                                             spel(canoniek, tafel, lambda i: ander))
                schendingen = json.loads(tekst).get("schendingen", []) if status == 400 else []
                if not schendingen or not all(f"invoervocabulaire '{vast}'" in s["fout"] for s in schendingen):
                    raise dr.Afwijking(f"{naam}: spelling {ander} onder invoervocabulaire {vast} gaf {status} "
                                       f"{tekst[:500]}, verwacht 400 met elke schending in '{vast}' gelezen")
            status, tekst = dienst.vraag("GET", "/info")
            info = {v["naam"]: v for v in json.loads(tekst).get("vocabulaires", [])}
            if list(info) != tafel.vocabulaires:
                raise dr.Afwijking(f"/info noemt de vocabulaires {list(info)}, het document {tafel.vocabulaires}")
            for v in tafel.vocabulaires:
                n = tafel.zonder_benaming(v)
                if len(info[v]["zonderBenaming"]) != n or info[v]["volledig"] != (n == 0):
                    raise dr.Afwijking(f"/info: vocabulaire '{v}' noemt {len(info[v]['zonderBenaming'])} declaraties "
                                       f"zonder benaming (volledig={info[v]['volledig']}), het document {n}")
    if echo == 0:
        raise dr.Afwijking("geen antwoord gaf een ingevoerde enumeratiewaarde terug: de namen van waarden zijn niet getoetst")
    return golden, "PLAYED", f"{len(cases)}\t{verzoeken}"


def speel_getuige(naam, bron, descriptor_pad, getuige_pad):
    getuige = json.load(open(getuige_pad))
    with tempfile.TemporaryDirectory(prefix="vocabulaire-getuige-") as werkmap:
        with dr.Dienst(descriptor_pad, bron, werkmap) as dienst:
            for n, g in enumerate(getuige["getuigenissen"], 1):
                status, tekst = dienst.vraag("POST", "/" + getuige["pad"] + g["query"], getuige["verzoek"])
                plek = f"getuigenis {n} ({g['query'] or 'zonder query'})"
                if status != g["status"]:
                    raise dr.Afwijking(f"{plek}: status {status}, verwacht {g['status']}: {tekst[:500]}")
                if status == 200:
                    inst = {i["id"]: i for i in dr.lees_json(tekst)["instanties"]}
                    for a in g["attributen"]:
                        gegeven = inst.get(a["id"], {}).get("attributen", {}).get(a["attribuut"])
                        if gegeven is None or gegeven != decimal.Decimal(a["waarde"]):
                            raise dr.Afwijking(f"{plek}: {a['id']}.{a['attribuut']} is {gegeven}, verwacht {a['waarde']}")
                    continue
                schendingen = json.loads(tekst).get("schendingen", [])
                treffers = [s for s in schendingen if s["soort"] == g["soort"] and s["veld"] == g["veld"]]
                if len(treffers) != 1 or not all(f in treffers[0]["fout"] for f in g["bevat"]):
                    raise dr.Afwijking(f"{plek}: verwacht één {g['soort']} op {g['veld']} met {g['bevat']}, "
                                       f"kreeg {schendingen}")
    return naam, "PLAYED", str(len(getuige["getuigenissen"]))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--corpus", required=True, help="de map met de goldens (uchar/corpus/golden)")
    ap.add_argument("--golden", action="append", default=[], help="<golden>=<descriptor>")
    ap.add_argument("--getuige", action="append", default=[], help="<naam>=<bron.rgs>,<descriptor>,<getuige.json>")
    a = ap.parse_args()
    for g in a.golden:
        golden, descriptor = g.split("=", 1)
        try:
            regel = speel_golden(golden, os.path.join(a.corpus, golden), descriptor)
        except dr.NietUitdrukbaar as n:
            regel = golden, "EXCLUDE", str(n)
        except dr.Afwijking as f:
            regel = golden, "FAIL", str(f)
        print("\t".join([regel[0], regel[1], " ".join(regel[2].split(" ")) if regel[1] == "PLAYED"
                         else " ".join(regel[2].split())]), flush=True)
    for g in a.getuige:
        naam, paden = g.split("=", 1)
        try:
            regel = speel_getuige(naam, *paden.split(","))
        except dr.Afwijking as f:
            regel = naam, "FAIL", str(f)
        print("\t".join([regel[0], regel[1], " ".join(regel[2].split())]), flush=True)


if __name__ == "__main__":
    main()
