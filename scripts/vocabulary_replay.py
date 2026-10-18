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

Per getuige (--witness <naam>=<bron.rgs>,<descriptor>,<getuige.json>): een eigen bron en een lijst getuigenissen,
elk een verzoek met een query en het verwachte antwoord (status, soort en veld van de schending en fragmenten van
haar zin, of attribuutwaarden).

Uitvoer: per golden "<golden>\\tPLAYED\\t<cases>\\t<verzoeken>", per getuige "<naam>\\tPLAYED\\t<getuigenissen>", of
"<naam>\\tFAIL\\t<wat>" / "<naam>\\tEXCLUDE\\t<vorm>". Het oordeel over de set is aan check-twins.sh.
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
import service_replay as dr  # noqa: E402

CANONICAL = "canonical"
FORM_LISTS = dr.FORM_LISTS


def designations(node):
    return {b["vocabulary"]: b["designation"] for b in node.get("designations", [])}


def domain(datatype):
    return datatype.get("domain") if datatype.get("node") == "Enumeration" else None


class Table:
    """De namen van een uchar-ir-v1-document per vocabulaire: de benaming daar, anders de canonieke naam. Een canonieke
    keuze laat elke naam zoals het verzoek of de case hem schrijft."""

    def __init__(self, doc):
        self.vocabularies = [v["name"] for v in doc.get("vocabularies", [])]
        if not self.vocabularies:
            raise dr.NotExpressible("het document declareert geen vocabulaire")
        m = doc["model"]
        self.declarations = []  # (soort, benamingen): elke benoembare declaratie, voor /info
        self.types, self.supertype, self.attributes, self.characteristics = {}, {}, {}, {}
        for ot in m["objectTypes"]:
            t = dr.norm(ot["name"])
            self.types[t] = (ot["name"], designations(ot))
            self.supertype[t] = dr.norm(ot["supertype"]) if ot.get("supertype") else None
            self.declarations.append(("objectType", designations(ot)))
            for a in ot.get("attributes", []):
                self.attributes[(t, dr.norm(a["name"]))] = (a["name"], designations(a), domain(a["datatype"]))
                self.declarations.append(("attribute", designations(a)))
            for k in ot.get("characteristics", []):
                self.characteristics[(t, dr.norm(k["name"]))] = (k["name"], designations(k))
                self.declarations.append(("characteristic", designations(k)))
        self.roles = []
        for ft in m["factTypes"]:
            self.declarations.append(("factType", designations(ft)))
            for r in ft["roles"]:
                self.roles.append((r["name"], r.get("plural"), designations(r)))
                self.declarations.append(("role", designations(r)))
        self.parameters = {}
        for p in m["parameters"]:
            self.parameters[dr.norm(p["name"])] = (p["name"], designations(p), domain(p["datatype"]))
            self.declarations.append(("parameter", designations(p)))
        self.values_ = {}
        for d in m["domains"]:
            dt = d["datatype"]
            if dt.get("node") != "Enumeration":
                continue
            per = {w: {} for w in dt["values"]}
            for e in dt.get("valueDesignations", []):
                per[e["value"]][e["vocabulary"]] = e["designation"]
            for w, b in per.items():
                self.values_[(dr.norm(d["name"]), dr.norm(w))] = (w, b)
                self.declarations.append(("value", b))
        self.forms = {}
        for items_list in FORM_LISTS:
            for form in doc["rules"].get(items_list, []):
                name = dr.without_version(form.get("name") or "")
                if name and dr.norm(name) not in self.forms:
                    self.forms[dr.norm(name)] = (name, designations(form))
                    self.declarations.append(("form", designations(form)))
        for g in doc["rules"].get("ruleGroups", []):
            self.declarations.append(("regelgroep", designations(g)))

    @staticmethod
    def _in(written, input_, choice):
        if choice == CANONICAL:
            return written
        return input_[1].get(choice, input_[0])

    def chain(self, t):
        t = dr.norm(t)
        while t is not None:
            yield t
            t = self.supertype.get(t)

    def type(self, t, choice):
        return self._in(t, self.types[dr.norm(t)], choice)

    def attribute_input(self, t, a):
        for s in self.chain(t):
            if (s, dr.norm(a)) in self.attributes:
                return self.attributes[(s, dr.norm(a))]
        raise dr.NotExpressible(f"attribuut '{a}' van '{t}' staat niet in het document")

    def attribute(self, t, a, choice):
        return self._in(a, self.attribute_input(t, a), choice)

    def characteristic(self, t, k, choice):
        for s in self.chain(t):
            if (s, dr.norm(k)) in self.characteristics:
                return self._in(k, self.characteristics[(s, dr.norm(k))], choice)
        raise dr.NotExpressible(f"kenmerk '{k}' van '{t}' staat niet in het document")

    def role(self, written, choice):
        hits = [r for r in self.roles
                    if dr.norm(r[0]) == dr.norm(written) or (r[1] and dr.norm(r[1]) == dr.norm(written))]
        if len(hits) != 1:
            raise dr.NotExpressible(f"rol '{written}' wijst niet naar één rol van het document")
        return self._in(written, (hits[0][0], hits[0][2]), choice)

    def parameter_input(self, p):
        return self.parameters[dr.norm(p)]

    def value_of(self, dom, w, choice):
        return self._in(w, self.values_[(dr.norm(dom), dr.norm(w))], choice)

    def form(self, name, choice):
        input_ = self.forms.get(dr.norm(dr.without_version(name)))
        return name if input_ is None else self._in(name, input_, choice)

    def without_designation(self, vocabulary):
        return sum(1 for _, b in self.declarations if vocabulary not in b)


def play_(v, table, choose):
    """Verzoek v (in de namen van de case) in de spelling die kies(i) voor de i-de naam van het verzoek kiest."""
    counter = [0]

    def k():
        counter[0] += 1
        return choose(counter[0] - 1)

    out = {s: v[s] for s in ("rekendatum", "asOfDate") if s in v}
    if "parameters" in v:
        out["parameters"] = {}
        for p, w in v["parameters"].items():
            input_ = table.parameter_input(p)
            name = table._in(p, input_, k())
            out["parameters"][name] = table.value_of(input_[2], w, k()) if input_[2] and isinstance(w, str) else w
    instances = []
    for i in v["instances"]:
        t = i["type"]
        n = {"type": table.type(t, k()), "id": i["id"]}
        if "attributes" in i:
            n["attributes"] = {}
            for a, w in i["attributes"].items():
                dom = table.attribute_input(t, a)[2]
                n["attributes"][table.attribute(t, a, k())] = \
                    table.value_of(dom, w, k()) if dom and isinstance(w, str) else w
        if "characteristics" in i:
            n["characteristics"] = {table.characteristic(t, kk, k()): w for kk, w in i["characteristics"].items()}
        if "roles" in i:
            n["roles"] = {table.role(r, k()): ids for r, ids in i["roles"].items()}
        instances.append(n)
    out["instances"] = instances
    return out


def expected_in(case, table, choice):
    """De case met de namen van zijn verwachtingen in vocabulaire keuze."""
    c = copy.deepcopy(case)
    types = {i["label"]: i["objectType"] for i in case["input"]["instances"]}
    for e in c["expected"]:
        kind = e["kind"]
        if kind in ("attributeValue", "attributeEmpty"):
            t = types[e["label"]]
            dom = table.attribute_input(t, e["attribute"])[2]
            if dom and e.get("value") and "enumeration" in e["value"]:
                e["value"] = {"enumeration": table.value_of(dom, e["value"]["enumeration"], choice)}
            e["attribute"] = table.attribute(t, e["attribute"], choice)
        elif kind in ("characteristicPresent", "characteristicAbsent"):
            e["characteristic"] = table.characteristic(types[e["label"]], e["characteristic"], choice)
        elif kind == "inconsistent" and e.get("rule"):
            e["rule"] = table.form(e["rule"], choice)
    return c


def names_in(case, answer, table, choice, place):
    """Het objecttype van elke instantie van het antwoord, en elke enumeratiewaarde die de case invoert en het antwoord
    teruggeeft, in de namen van keuze. Canoniek houdt de dienst de spelling van het verzoek aan, zoals uchars replay die
    van een case: daar geldt de vergelijking op genormaliseerde naam. Geeft het aantal teruggegeven waarden."""
    input_ = {i["label"]: i for i in case["input"]["instances"]}
    echo = 0
    for inst in answer.get("instances", []):
        i = input_[inst["id"]]
        expected = table.type(i["objectType"], choice)
        if inst["type"] != expected:
            raise dr.Deviation(f"{place}: {inst['id']} heeft type '{inst['type']}', verwacht '{expected}'")
        for a, w in (i.get("attributes") or {}).items():
            dom = table.attribute_input(i["objectType"], a)[2]
            name = table.attribute(i["objectType"], a, choice)
            if dom and w and "enumeration" in w and name in inst.get("attributes", {}):
                expected = table.value_of(dom, w["enumeration"], choice)
                given = inst["attributes"][name]
                if (dr.norm(given) != dr.norm(expected)) if choice == CANONICAL else (given != expected):
                    raise dr.Deviation(f"{place}: {inst['id']}.{name} is '{inst['attributes'][name]}', "
                                       f"verwacht '{expected}'")
                echo += 1
    return echo


def compare(case, answer, place):
    try:
        dr.compare(case, answer)
    except dr.Deviation as a:
        raise dr.Deviation(f"{place}: {a}")


def play_golden(golden, map_, descriptor_path):
    doc = json.load(open(os.path.join(map_, "ir.json")))
    table = Table(doc)
    cases = []
    for path in sorted(glob.glob(os.path.join(map_, "case*.json"))):
        c = json.load(open(path))
        if "expectedFault" not in c:
            cases.append((os.path.basename(path), c))
    if not cases:
        raise dr.NotExpressible("geen casebestand")
    descriptor = json.load(open(descriptor_path))
    eps = descriptor["entrypoints"]
    if len(eps) < 2 or "vocabulary" in eps[0] or not eps[1].get("vocabulary"):
        raise dr.Deviation("de descriptor heeft een eerste entrypoint zonder en een tweede met standaardvocabulaire nodig")
    path, default_path, default_ = "/" + eps[0]["path"], "/" + eps[1]["path"], eps[1]["vocabulary"]
    choices = [CANONICAL] + table.vocabularies
    spellings = [(k, (lambda k: lambda i: k)(k)) for k in choices] + [("gemengd", lambda i: choices[i % len(choices)])]
    requests = echo = 0
    source = os.path.join(map_, "bron.rgs")
    with tempfile.TemporaryDirectory(prefix="vocabulaire-replay-") as workdir:
        with dr.Service(descriptor_path, source, workdir) as service:
            def ask(pad_, query, body, place):
                nonlocal requests
                requests += 1
                status, text_ = service.ask("POST", pad_ + query, body)
                if status != 200:
                    raise dr.Deviation(f"{place}: {status} {text_[:500]}")
                return dr.read_json(text_)

            for name, c in cases:
                canonical = dr.request_body(c)
                for spelling, choose in spellings:
                    body = play_(canonical, table, choose)
                    for out in choices:
                        place = f"{name}, spelling {spelling}, vocabulaire {out}"
                        answer = ask(path, "?vocabulary=" + urllib.parse.quote(out), body, place)
                        compare(expected_in(c, table, out), answer, place)
                        echo += names_in(c, answer, table, out, place)
                    if spelling != "gemengd":
                        place = f"{name}, spelling {spelling}, invoervocabulaire {spelling}"
                        answer = ask(path, "?inputVocabulary=" + urllib.parse.quote(spelling), body, place)
                        compare(c, answer, place)
                place = f"{name}, standaardvocabulaire {default_} van {default_path}"
                answer = ask(default_path, "", canonical, place)
                compare(expected_in(c, table, default_), answer, place)
                echo += names_in(c, answer, table, default_, place)
                # a pinned vocabulary reads only its own effective names: another spelling is refused, by name
                other, fixed = table.vocabularies[0], table.vocabularies[-1]
                requests += 1
                status, text_ = service.ask("POST", path + "?inputVocabulary=" + urllib.parse.quote(fixed),
                                             play_(canonical, table, lambda i: other))
                violations = json.loads(text_).get("violations", []) if status == 400 else []
                if not violations or not all(f"invoervocabulaire '{fixed}'" in s["error"] for s in violations):
                    raise dr.Deviation(f"{name}: spelling {other} onder invoervocabulaire {fixed} gaf {status} "
                                       f"{text_[:500]}, verwacht 400 met elke schending in '{fixed}' gelezen")
            status, text_ = service.ask("GET", "/info")
            info = {v["name"]: v for v in json.loads(text_).get("vocabularies", [])}
            if list(info) != table.vocabularies:
                raise dr.Deviation(f"/info noemt de vocabulaires {list(info)}, het document {table.vocabularies}")
            for v in table.vocabularies:
                n = table.without_designation(v)
                if len(info[v]["withoutDesignation"]) != n or info[v]["complete"] != (n == 0):
                    raise dr.Deviation(f"/info: vocabulaire '{v}' noemt {len(info[v]['withoutDesignation'])} declaraties "
                                       f"zonder benaming (volledig={info[v]['complete']}), het document {n}")
    if echo == 0:
        raise dr.Deviation("geen antwoord gaf een ingevoerde enumeratiewaarde terug: de namen van waarden zijn niet getoetst")
    return golden, "PLAYED", f"{len(cases)}\t{requests}"


def play_witness(name, source, descriptor_path, witness_path):
    witness = json.load(open(witness_path))
    with tempfile.TemporaryDirectory(prefix="vocabulaire-getuige-") as workdir:
        with dr.Service(descriptor_path, source, workdir) as service:
            for n, g in enumerate(witness["witnesses"], 1):
                status, text_ = service.ask("POST", "/" + witness["path"] + g["query"], witness["request"])
                place = f"getuigenis {n} ({g['query'] or 'zonder query'})"
                if status != g["status"]:
                    raise dr.Deviation(f"{place}: status {status}, verwacht {g['status']}: {text_[:500]}")
                if status == 200:
                    inst = {i["id"]: i for i in dr.read_json(text_)["instances"]}
                    for a in g["attributes"]:
                        given = inst.get(a["id"], {}).get("attributes", {}).get(a["attribute"])
                        if given is None or given != decimal.Decimal(a["value"]):
                            raise dr.Deviation(f"{place}: {a['id']}.{a['attribute']} is {given}, verwacht {a['value']}")
                    continue
                violations = json.loads(text_).get("violations", [])
                hits = [s for s in violations if s["kind"] == g["kind"] and s["field"] == g["field"]]
                if len(hits) != 1 or not all(f in hits[0]["error"] for f in g["contains"]):
                    raise dr.Deviation(f"{place}: verwacht één {g['kind']} op {g['field']} met {g['contains']}, "
                                       f"kreeg {violations}")
    return name, "PLAYED", str(len(witness["witnesses"]))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--corpus", required=True, help="de map met de goldens (uchar/corpus/golden)")
    ap.add_argument("--golden", action="append", default=[], help="<golden>=<descriptor>")
    ap.add_argument("--witness", action="append", default=[], help="<naam>=<bron.rgs>,<descriptor>,<getuige.json>")
    a = ap.parse_args()
    for g in a.golden:
        golden, descriptor = g.split("=", 1)
        try:
            rule = play_golden(golden, os.path.join(a.corpus, golden), descriptor)
        except dr.NotExpressible as n:
            rule = golden, "EXCLUDE", str(n)
        except dr.Deviation as f:
            rule = golden, "FAIL", str(f)
        print("\t".join([rule[0], rule[1], " ".join(rule[2].split(" ")) if rule[1] == "PLAYED"
                         else " ".join(rule[2].split())]), flush=True)
    for g in a.witness:
        name, paths = g.split("=", 1)
        try:
            rule = play_witness(name, *paths.split(","))
        except dr.Deviation as f:
            rule = name, "FAIL", str(f)
        print("\t".join([rule[0], rule[1], " ".join(rule[2].split())]), flush=True)


if __name__ == "__main__":
    main()
