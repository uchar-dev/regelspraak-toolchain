#!/usr/bin/env python3
"""Replay van uchar-goldens door de JDK-dienst van deze distributie.

Per golden: de dienst start op een vrije poort (bin/regelspraak serve ... --poort 0) over de RegelSpraak-bron van de
golden (bron.rgs) of, zonder bron, over het uchar-ir-v1-document zelf (ir.json); elk casebestand wordt een verzoek,
het antwoord wordt vergeleken met de gepinde verwachtingen, zoals uchars replay (CaseRunner) vergelijkt: getallen op
waarde, een enumeratie op genormaliseerde naam, een inconsistentie op regel en onderwerp. De descriptor maakt dit
script zelf, of neemt hem aan (--descriptor): één entrypoint 'replay' dat alle vormen van het document draagt en
elk attribuut en kenmerk teruggeeft dat een verwachting leest.

Uitvoer: per golden één regel "<golden>\\t<STATUS>\\t<detail>" in de volgorde van de invoer, met STATUS
  PLAYED   alle cases gaven de gepinde verwachtingen (detail: het aantal cases);
  EXCLUDE  een case-vorm die het verzoek of het antwoord van de dienst niet uitdrukt (detail: de vorm);
  FAIL     een afwijking, een dienst die niet start, of een antwoord dat geen antwoord is (detail: wat).
Het script oordeelt niet over de hele set: de poortscripts (check-dienst-replay.sh, check-tweelingen.sh) doen dat.
"""
import argparse
import concurrent.futures
import datetime
import decimal
import glob
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

REGELSPRAAK = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "bin", "regelspraak")
OPSTARTTIJD = 120  # seconden; een dienst die dan nog geen poort noemt, is een FAIL
VORMLIJSTEN = ("rules", "objectCreations", "allocations", "consistencies", "uniquenesses", "factCreations",
               "prioritizedDefaults")


class NietUitdrukbaar(Exception):
    """Een case-vorm die het verzoek of het antwoord van de dienst niet uitdrukt."""


class Afwijking(Exception):
    """Een antwoord dat van de gepinde verwachting afwijkt, of geen antwoord is."""


def norm(naam):
    """uchars Namen.norm: woorden gescheiden door spaties of tabs, één spatie ertussen, A-Z klein."""
    return " ".join(w.translate(str.maketrans("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "abcdefghijklmnopqrstuvwxyz"))
                    for w in re.split(r"[ \t]+", naam) if w)


def zonder_versie(naam):
    """Het strip_version van uchars verifier: een achtervoegsel ' #<cijfers>' met '#' op index 2 of later."""
    i = naam.rfind("#")
    if i >= 2 and naam[i - 1] == " " and i + 1 < len(naam) and naam[i + 1:].isdigit():
        return naam[:i - 1]
    return naam


def zelfde_regel(a, b):
    return a is not None and b is not None and norm(zonder_versie(a)) == norm(zonder_versie(b))


# ---------------------------------------------------------------- case -> verzoek

def waarde(node, plek):
    """De verzoekwaarde van een casewaarde; getallen als tekst, zodat de dienst ze exact als BigDecimal leest."""
    if node is None:
        return None
    if "number" in node:
        lexeem = node["number"]
        if "/" in lexeem:
            raise NietUitdrukbaar(f"breuk als waarde ({plek})")
        return lexeem
    for sleutel in ("date", "dateTime", "text", "enumeration"):
        if sleutel in node:
            return node[sleutel]
    if "boolean" in node:
        return node["boolean"]
    if "timeline" in node:
        raise NietUitdrukbaar(f"tijdlijn als invoerwaarde ({plek})")
    raise NietUitdrukbaar(f"waardevorm {sorted(node)} ({plek})")


def verzoek(case):
    """Het verzoek van een casebestand."""
    v = {}
    if case.get("asOfDate"):
        v["rekendatum"] = case["asOfDate"]
    inp = case["input"]
    params = {}
    for naam, w in (inp.get("parameters") or {}).items():
        if w is not None and "timeline" in w:
            raise NietUitdrukbaar(f"tijdlijn als parameterwaarde ({naam})")
        params[naam] = waarde(w, "parameter " + naam)
    if params:
        v["parameters"] = params
    instanties = []
    for i in inp["instances"]:
        inst = {"type": i["objectType"], "id": i["label"]}
        attr = {a: waarde(w, i["label"] + "." + a) for a, w in (i.get("attributes") or {}).items()}
        if attr:
            inst["attributen"] = attr
        if i.get("characteristics"):
            inst["kenmerken"] = {k: True for k in i["characteristics"]}
        if i.get("characteristicTimelines"):
            raise NietUitdrukbaar(f"kenmerk met perioden ({i['label']})")
        if i.get("roles"):
            inst["rollen"] = {r: list(ls) for r, ls in i["roles"].items()}
        instanties.append(inst)
    v["instanties"] = instanties
    return v


def selectoren(case):
    """De uitvoerselectoren die de verwachtingen van een case lezen."""
    typen = {i["label"]: i["objectType"] for i in case["input"]["instances"]}
    uit = []
    for e in case["expected"]:
        soort = e["kind"]
        if soort in ("attributeValue", "attributeEmpty"):
            uit.append(typen[e["label"]] + "." + e["attribute"])
        elif soort in ("characteristicPresent", "characteristicAbsent"):
            uit.append(typen[e["label"]] + ":" + e["characteristic"])
        elif soort == "attributeTimeline":
            raise NietUitdrukbaar("tijdlijn als verwachte waarde")
    return uit


def vormen(document):
    """De namen van alle vormen van het document: wat de scope van 'replay' draagt."""
    uit = []
    for lijst in VORMLIJSTEN:
        for vorm in document["rules"].get(lijst, []):
            if vorm.get("name") and vorm["name"] not in uit:
                uit.append(vorm["name"])
    return uit


def descriptor(naam, document, cases):
    uitvoer = []
    for c in cases:
        for s in selectoren(c):
            if s not in uitvoer:
                uitvoer.append(s)
    scope = vormen(document)
    if not scope:
        raise NietUitdrukbaar("een document zonder vorm heeft geen scope")
    return {"dienst": {"naam": naam, "versie": "replay"},
            "entrypoints": [{"naam": "replay", "pad": "replay", "scope": {"regelnamen": scope},
                             "invoer": [], "uitvoer": uitvoer, "scopeControleUit": True}],
            "parameters": {}}


# ---------------------------------------------------------------- antwoord -> vergelijking

def gelijk(verwacht, gegeven, plek):
    """uchars gelijkeWaarde: een getal op waarde, een enumeratie op genormaliseerde naam, de rest exact."""
    if "number" in verwacht:
        if not isinstance(gegeven, decimal.Decimal):
            return False
        return decimal.Decimal(verwacht["number"]) == gegeven
    if "enumeration" in verwacht:
        return isinstance(gegeven, str) and norm(gegeven) == norm(verwacht["enumeration"])
    if "dateTime" in verwacht:
        return isinstance(gegeven, str) and \
            datetime.datetime.fromisoformat(gegeven) == datetime.datetime.fromisoformat(verwacht["dateTime"])
    if "date" in verwacht:
        return gegeven == verwacht["date"]
    if "boolean" in verwacht:
        return gegeven is verwacht["boolean"]
    if "text" in verwacht:
        return gegeven == verwacht["text"]
    raise NietUitdrukbaar(f"verwachte waardevorm {sorted(verwacht)} ({plek})")


def vergelijk(case, antwoord):
    """Zet een Afwijking bij de eerste verwachting die het antwoord niet geeft."""
    inst = {i["id"]: i for i in antwoord.get("instanties", [])}
    inconsistenties = antwoord.get("inconsistenties")
    details = antwoord.get("inconsistentieDetails")
    for n, e in enumerate(case["expected"], 1):
        soort = e["kind"]
        plek = f"verwachting {n} ({soort} {e.get('label', '')} {e.get('attribute', e.get('characteristic', ''))})"
        if soort in ("attributeValue", "attributeEmpty"):
            attrs = inst.get(e["label"], {}).get("attributen", {})
            if e["attribute"] not in attrs:
                raise Afwijking(f"{plek}: het antwoord noemt het attribuut niet")
            gegeven = attrs[e["attribute"]]
            if soort == "attributeEmpty":
                if gegeven is not None:
                    raise Afwijking(f"{plek}: verwacht leeg, kreeg {gegeven}")
            elif e["value"] is None:
                if gegeven is not None:
                    raise Afwijking(f"{plek}: verwacht leeg, kreeg {gegeven}")
            elif not gelijk(e["value"], gegeven, plek):
                raise Afwijking(f"{plek}: verwacht {json.dumps(e['value'])}, kreeg {gegeven}")
        elif soort in ("characteristicPresent", "characteristicAbsent"):
            kenmerken = inst.get(e["label"], {}).get("kenmerken", {})
            if e["characteristic"] not in kenmerken:
                raise Afwijking(f"{plek}: het antwoord noemt het kenmerk niet")
            if kenmerken[e["characteristic"]] is not (soort == "characteristicPresent"):
                raise Afwijking(f"{plek}: kreeg {kenmerken[e['characteristic']]}")
        elif soort == "consistent":
            if inconsistenties:
                raise Afwijking(f"{plek}: kreeg inconsistenties {inconsistenties}")
        elif soort == "inconsistent":
            if not inconsistenties:
                raise Afwijking(f"{plek}: verwacht inconsistent, het antwoord is consistent")
            regel, label = e.get("rule"), e.get("label")
            if regel is None:
                continue
            if label is not None and details is None:
                raise NietUitdrukbaar("een inconsistentie op een onderwerp")
            kandidaten = details if details is not None else [{"regel": r, "onderwerp": None} for r in inconsistenties]
            if not any(zelfde_regel(regel, d["regel"]) and (label is None or d["onderwerp"] == label)
                       for d in kandidaten):
                raise Afwijking(f"{plek}: verwacht {regel}" + (f" op {label}" if label else "")
                                + f", kreeg {kandidaten}")
        else:
            raise NietUitdrukbaar(f"verwachtingssoort {soort}")


# ---------------------------------------------------------------- de dienst

class Dienst:
    """Eén JDK-dienst op een vrije poort van 127.0.0.1, opgeruimd bij het verlaten van het with-blok én wanneer hij
    niet opkomt. Het kindproces schrijft zijn uitvoer in een eigen bestandsbeschrijving; dit script leest het bestand
    via een andere, zodat lezen de schrijfpositie van het kind niet verzet."""

    def __init__(self, descriptor_pad, bron_pad, werkmap):
        self.uit_pad = os.path.join(werkmap, "dienst.out")
        self.fout_pad = os.path.join(werkmap, "dienst.err")
        self.uit = open(self.uit_pad, "w")
        self.fout = open(self.fout_pad, "w")
        self.proces = subprocess.Popen([REGELSPRAAK, "serve", descriptor_pad, bron_pad,
                                        "--host", "127.0.0.1", "--poort", "0"],
                                       stdout=self.uit, stderr=self.fout, text=True)
        self.poort = None

    def __enter__(self):
        try:
            self._wacht_op_gezond()
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def _wacht_op_gezond(self):
        eind = time.time() + OPSTARTTIJD
        while time.time() < eind:
            with open(self.uit_pad) as f:
                m = re.search(r"luistert op poort (\d+):", f.read())
            if m:
                self.poort = int(m.group(1))
                status, tekst = self.vraag("GET", "/info")
                if status != 200:
                    raise Afwijking(f"GET /info op poort {self.poort} gaf {status}: {tekst[:200]}")
                return
            if self.proces.poll() is not None:
                with open(self.fout_pad) as f:
                    melding = " ".join(r.strip() for r in f.read().splitlines()
                                       if r.strip() and not r.startswith("SLF4J"))
                raise Afwijking(f"de dienst start niet (exit {self.proces.returncode}): {melding[:600]}")
            time.sleep(0.05)
        raise Afwijking(f"de dienst noemt na {OPSTARTTIJD} s geen poort")

    def __exit__(self, *exc):
        if self.proces.poll() is None:
            self.proces.kill()
        self.proces.wait()
        self.uit.close()
        self.fout.close()

    def vraag(self, methode, pad, lichaam=None):
        data = None if lichaam is None else json.dumps(lichaam).encode("utf-8")
        req = urllib.request.Request(f"http://127.0.0.1:{self.poort}{pad}", data=data, method=methode,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.status, r.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8")


def lees_json(tekst):
    return json.loads(tekst, parse_float=decimal.Decimal, parse_int=decimal.Decimal)


def speel(golden, map_, descriptor_pad=None):
    """De uitkomstregel van één golden."""
    try:
        document = json.load(open(os.path.join(map_, "ir.json")))
        cases = []
        for pad in sorted(glob.glob(os.path.join(map_, "case*.json"))):
            c = json.load(open(pad))
            if "expectedFault" not in c:
                cases.append((os.path.basename(pad), c))
        if not cases:
            return golden, "EXCLUDE", "geen casebestand"
        verzoeken = [(naam, verzoek(c), c) for naam, c in cases]
        bron = os.path.join(map_, "bron.rgs")
        if not os.path.exists(bron):
            bron = os.path.join(map_, "ir.json")
        with tempfile.TemporaryDirectory(prefix="dienst-replay-") as werkmap:
            if descriptor_pad is None:
                descriptor_pad = os.path.join(werkmap, "replay.dienst.json")
                with open(descriptor_pad, "w") as f:
                    json.dump(descriptor(golden, document, [c for _, c in cases]), f, indent=1)
                pad = "/replay"
            else:
                pad = "/" + json.load(open(descriptor_pad))["entrypoints"][0]["pad"]
            with Dienst(descriptor_pad, bron, werkmap) as dienst:
                for naam, v, c in verzoeken:
                    status, tekst = dienst.vraag("POST", pad, v)
                    if status == 400:
                        # a request form the service does not carry: every violation says NIET_ONDERSTEUND
                        schendingen = json.loads(tekst).get("schendingen", [])
                        if schendingen and all(s.get("soort") == "NIET_ONDERSTEUND" for s in schendingen):
                            raise NietUitdrukbaar(schendingen[0]["fout"])
                        raise Afwijking(f"{naam}: 400 {tekst[:500]}")
                    if status != 200:
                        raise Afwijking(f"{naam}: {status} {tekst[:500]}")
                    try:
                        vergelijk(c, lees_json(tekst))
                    except Afwijking as a:
                        raise Afwijking(f"{naam}: {a}")
        return golden, "PLAYED", str(len(cases))
    except NietUitdrukbaar as n:
        return golden, "EXCLUDE", str(n)
    except Afwijking as a:
        return golden, "FAIL", str(a)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--corpus", required=True, help="de map met de goldens (uchar/corpus/golden)")
    ap.add_argument("--werkers", type=int, default=4)
    ap.add_argument("--descriptor", action="append", default=[], help="<golden>=<pad>: een eigen descriptor")
    ap.add_argument("goldens", nargs="+", help="goldens, als pad relatief aan --corpus")
    a = ap.parse_args()
    eigen = dict(d.split("=", 1) for d in a.descriptor)
    with concurrent.futures.ThreadPoolExecutor(max_workers=a.werkers) as pool:
        taken = [pool.submit(speel, g, os.path.join(a.corpus, g), eigen.get(g)) for g in a.goldens]
        for t in taken:
            golden, status, detail = t.result()
            print(f"{golden}\t{status}\t{' '.join(detail.split())}", flush=True)


if __name__ == "__main__":
    main()
