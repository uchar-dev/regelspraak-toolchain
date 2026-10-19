#!/usr/bin/env python3
"""Replay van uchar-goldens door de JDK-dienst van deze distributie.

Per golden: de dienst start op een vrije poort (bin/regelspraak serve ... --port 0) over de RegelSpraak-bron van de
golden (source.rgs) of, zonder bron, over het uchar-ir-v1-document zelf (ir.json); elk casebestand wordt een verzoek,
het antwoord wordt vergeleken met de gepinde verwachtingen, zoals uchars replay (CaseRunner) vergelijkt: getallen op
waarde, een enumeratie op genormaliseerde naam, een inconsistentie op regel en onderwerp. De descriptor maakt dit
script zelf, of neemt hem aan (--descriptor): één entrypoint 'replay' dat alle vormen van het document draagt en
elk attribuut en kenmerk teruggeeft dat een verwachting leest.

Uitvoer: per golden één regel "<golden>\\t<STATUS>\\t<detail>" in de volgorde van de invoer, met STATUS
  PLAYED   alle cases gaven de gepinde verwachtingen (detail: het aantal cases);
  EXCLUDE  een case-vorm die het verzoek of het antwoord van de dienst niet uitdrukt (detail: de vorm);
  FAIL     een afwijking, een dienst die niet start, of een antwoord dat geen antwoord is (detail: wat).
Het script oordeelt niet over de hele set: de poortscripts (check-service-replay.sh, check-twins.sh) doen dat.
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
STARTUP_SECONDS = 120  # seconden; een dienst die dan nog geen poort noemt, is een FAIL
FORM_LISTS = ("rules", "objectCreations", "allocations", "consistencies", "uniquenesses", "factCreations",
               "prioritizedDefaults")


class NotExpressible(Exception):
    """Een case-vorm die het verzoek of het antwoord van de dienst niet uitdrukt."""


class Deviation(Exception):
    """Een antwoord dat van de gepinde verwachting afwijkt, of geen antwoord is."""


def norm(name):
    """uchars Namen.norm: woorden gescheiden door spaties of tabs, één spatie ertussen, A-Z klein."""
    return " ".join(w.translate(str.maketrans("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "abcdefghijklmnopqrstuvwxyz"))
                    for w in re.split(r"[ \t]+", name) if w)


def without_version(name):
    """Het strip_version van uchars verifier: een achtervoegsel ' #<cijfers>' met '#' op index 2 of later."""
    i = name.rfind("#")
    if i >= 2 and name[i - 1] == " " and i + 1 < len(name) and name[i + 1:].isdigit():
        return name[:i - 1]
    return name


def same_rule(a, b):
    return a is not None and b is not None and norm(without_version(a)) == norm(without_version(b))


# ---------------------------------------------------------------- case -> verzoek

def value_of(node, place):
    """De verzoekwaarde van een casewaarde; getallen als tekst, zodat de dienst ze exact als BigDecimal leest."""
    if node is None:
        return None
    if "number" in node:
        lexeme = node["number"]
        if "/" in lexeme:
            raise NotExpressible(f"breuk als waarde ({place})")
        return lexeme
    for key in ("date", "dateTime", "text", "enumeration"):
        if key in node:
            return node[key]
    if "boolean" in node:
        return node["boolean"]
    if "timeline" in node:
        raise NotExpressible(f"tijdlijn als invoerwaarde ({place})")
    raise NotExpressible(f"waardevorm {sorted(node)} ({place})")


def request_body(case):
    """Het verzoek van een casebestand."""
    v = {}
    if case.get("asOfDate"):
        v["rekendatum"] = case["asOfDate"]
    inp = case["input"]
    params = {}
    for name, w in (inp.get("parameters") or {}).items():
        if w is not None and "timeline" in w:
            raise NotExpressible(f"tijdlijn als parameterwaarde ({name})")
        params[name] = value_of(w, "parameter " + name)
    if params:
        v["parameters"] = params
    instances = []
    for i in inp["instances"]:
        inst = {"type": i["objectType"], "id": i["label"]}
        attr = {a: value_of(w, i["label"] + "." + a) for a, w in (i.get("attributes") or {}).items()}
        if attr:
            inst["attributes"] = attr
        if i.get("characteristics"):
            inst["characteristics"] = {k: True for k in i["characteristics"]}
        if i.get("characteristicTimelines"):
            raise NotExpressible(f"kenmerk met perioden ({i['label']})")
        if i.get("roles"):
            inst["roles"] = {r: list(ls) for r, ls in i["roles"].items()}
        instances.append(inst)
    v["instances"] = instances
    return v


def selectors(case):
    """De uitvoerselectoren die de verwachtingen van een case lezen."""
    types = {i["label"]: i["objectType"] for i in case["input"]["instances"]}
    out = []
    for e in case["expected"]:
        kind = e["kind"]
        if kind in ("attributeValue", "attributeEmpty"):
            out.append(types[e["label"]] + "." + e["attribute"])
        elif kind in ("characteristicPresent", "characteristicAbsent"):
            out.append(types[e["label"]] + ":" + e["characteristic"])
        elif kind == "attributeTimeline":
            raise NotExpressible("tijdlijn als verwachte waarde")
    return out


def forms(document):
    """De namen van alle vormen van het document: wat de scope van 'replay' draagt."""
    out = []
    for items_list in FORM_LISTS:
        for form in document["rules"].get(items_list, []):
            if form.get("name") and form["name"] not in out:
                out.append(form["name"])
    return out


def descriptor(name, document, cases):
    output = []
    for c in cases:
        for s in selectors(c):
            if s not in output:
                output.append(s)
    scope = forms(document)
    if not scope:
        raise NotExpressible("een document zonder vorm heeft geen scope")
    return {"service": {"name": name, "version": "replay"},
            "entrypoints": [{"name": "replay", "path": "replay", "scope": {"ruleNames": scope},
                             "input": [], "output": output, "scopeCheckOptOut": True}],
            "parameters": {}}


# ---------------------------------------------------------------- antwoord -> vergelijking

def equal(expected, given, place):
    """uchars gelijkeWaarde: een getal op waarde, een enumeratie op genormaliseerde naam, de rest exact."""
    if "number" in expected:
        if not isinstance(given, decimal.Decimal):
            return False
        return decimal.Decimal(expected["number"]) == given
    if "enumeration" in expected:
        return isinstance(given, str) and norm(given) == norm(expected["enumeration"])
    if "dateTime" in expected:
        return isinstance(given, str) and \
            datetime.datetime.fromisoformat(given) == datetime.datetime.fromisoformat(expected["dateTime"])
    if "date" in expected:
        return given == expected["date"]
    if "boolean" in expected:
        return given is expected["boolean"]
    if "text" in expected:
        return given == expected["text"]
    raise NotExpressible(f"verwachte waardevorm {sorted(expected)} ({place})")


def compare(case, answer):
    """Zet een Afwijking bij de eerste verwachting die het antwoord niet geeft. Dit leest het
    HTTP-antwoord zelf: uchars DroolsBackend.vergelijk oordeelt over een sessie in het geheugen, en
    die gaat niet over de lijn; deze gate toetst wat de dienst stuurt."""
    inst = {i["id"]: i for i in answer.get("instances", [])}
    inconsistencies = answer.get("inconsistencies")
    details = answer.get("inconsistencyDetails")
    for n, e in enumerate(case["expected"], 1):
        kind = e["kind"]
        place = f"verwachting {n} ({kind} {e.get('label', '')} {e.get('attribute', e.get('characteristic', ''))})"
        if kind in ("attributeValue", "attributeEmpty"):
            attrs = inst.get(e["label"], {}).get("attributes", {})
            if e["attribute"] not in attrs:
                raise Deviation(f"{place}: het antwoord noemt het attribuut niet")
            given = attrs[e["attribute"]]
            if kind == "attributeEmpty":
                if given is not None:
                    raise Deviation(f"{place}: verwacht leeg, kreeg {given}")
            elif e["value"] is None:
                if given is not None:
                    raise Deviation(f"{place}: verwacht leeg, kreeg {given}")
            elif not equal(e["value"], given, place):
                raise Deviation(f"{place}: verwacht {json.dumps(e['value'])}, kreeg {given}")
        elif kind in ("characteristicPresent", "characteristicAbsent"):
            characteristics = inst.get(e["label"], {}).get("characteristics", {})
            if e["characteristic"] not in characteristics:
                raise Deviation(f"{place}: het antwoord noemt het kenmerk niet")
            if characteristics[e["characteristic"]] is not (kind == "characteristicPresent"):
                raise Deviation(f"{place}: kreeg {characteristics[e['characteristic']]}")
        elif kind == "consistent":
            if inconsistencies:
                raise Deviation(f"{place}: kreeg inconsistenties {inconsistencies}")
        elif kind == "inconsistent":
            if not inconsistencies:
                raise Deviation(f"{place}: verwacht inconsistent, het antwoord is consistent")
            rule, label = e.get("rule"), e.get("label")
            if rule is None:
                continue
            if label is not None and details is None:
                raise NotExpressible("een inconsistentie op een onderwerp")
            candidates = details if details is not None else [{"rule": r, "subject": None} for r in inconsistencies]
            if not any(same_rule(rule, d["rule"]) and (label is None or d["subject"] == label)
                       for d in candidates):
                raise Deviation(f"{place}: verwacht {rule}" + (f" op {label}" if label else "")
                                + f", kreeg {candidates}")
        else:
            raise NotExpressible(f"verwachtingssoort {kind}")


# ---------------------------------------------------------------- de dienst

class Service:
    """Eén JDK-dienst op een vrije poort van 127.0.0.1, opgeruimd bij het verlaten van het with-blok én wanneer hij
    niet opkomt. Het kindproces schrijft zijn uitvoer in een eigen bestandsbeschrijving; dit script leest het bestand
    via een andere, zodat lezen de schrijfpositie van het kind niet verzet."""

    def __init__(self, descriptor_path, source_path, workdir):
        self.out_path = os.path.join(workdir, "dienst.out")
        self.error_path = os.path.join(workdir, "dienst.err")
        self.out = open(self.out_path, "w")
        self.error = open(self.error_path, "w")
        self.process = subprocess.Popen([REGELSPRAAK, "serve", descriptor_path, source_path,
                                        "--host", "127.0.0.1", "--port", "0"],
                                       stdout=self.out, stderr=self.error, text=True)
        self.port = None

    def __enter__(self):
        try:
            self._wait_until_healthy()
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def _wait_until_healthy(self):
        end = time.time() + STARTUP_SECONDS
        while time.time() < end:
            with open(self.out_path) as f:
                m = re.search(r"luistert op poort (\d+):", f.read())
            if m:
                self.port = int(m.group(1))
                status, text_ = self.ask("GET", "/info")
                if status != 200:
                    raise Deviation(f"GET /info op poort {self.port} gaf {status}: {text_[:200]}")
                return
            if self.process.poll() is not None:
                with open(self.error_path) as f:
                    message = " ".join(r.strip() for r in f.read().splitlines()
                                       if r.strip() and not r.startswith("SLF4J"))
                raise Deviation(f"de dienst start niet (exit {self.process.returncode}): {message[:600]}")
            time.sleep(0.05)
        raise Deviation(f"de dienst noemt na {STARTUP_SECONDS} s geen poort")

    def __exit__(self, *exc):
        if self.process.poll() is None:
            self.process.kill()
        self.process.wait()
        self.out.close()
        self.error.close()

    def ask(self, method_, path, body=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", data=data, method=method_,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.status, r.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8")


def read_json(text_):
    return json.loads(text_, parse_float=decimal.Decimal, parse_int=decimal.Decimal)


def play(golden, map_, descriptor_path=None):
    """De uitkomstregel van één golden."""
    try:
        document = json.load(open(os.path.join(map_, "ir.json")))
        cases = []
        for path in sorted(glob.glob(os.path.join(map_, "case*.json"))):
            c = json.load(open(path))
            if "expectedFault" not in c:
                cases.append((os.path.basename(path), c))
        if not cases:
            return golden, "EXCLUDE", "geen casebestand"
        requests = [(name, request_body(c), c) for name, c in cases]
        source = os.path.join(map_, "source.rgs")
        if not os.path.exists(source):
            source = os.path.join(map_, "ir.json")
        with tempfile.TemporaryDirectory(prefix="dienst-replay-") as workdir:
            if descriptor_path is None:
                descriptor_path = os.path.join(workdir, "replay.service.json")
                with open(descriptor_path, "w") as f:
                    json.dump(descriptor(golden, document, [c for _, c in cases]), f, indent=1)
                path = "/replay"
            else:
                path = "/" + json.load(open(descriptor_path))["entrypoints"][0]["path"]
            with Service(descriptor_path, source, workdir) as service:
                for name, v, c in requests:
                    status, text_ = service.ask("POST", path, v)
                    if status == 400:
                        # a request form the service does not carry: every violation says UNSUPPORTED
                        violations = json.loads(text_).get("violations", [])
                        if violations and all(s.get("kind") == "UNSUPPORTED" for s in violations):
                            raise NotExpressible(violations[0]["error"])
                        raise Deviation(f"{name}: 400 {text_[:500]}")
                    if status != 200:
                        raise Deviation(f"{name}: {status} {text_[:500]}")
                    try:
                        compare(c, read_json(text_))
                    except Deviation as a:
                        raise Deviation(f"{name}: {a}")
        return golden, "PLAYED", str(len(cases))
    except NotExpressible as n:
        return golden, "EXCLUDE", str(n)
    except Deviation as a:
        return golden, "FAIL", str(a)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--corpus", required=True, help="de map met de goldens (uchar/corpus/golden)")
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--descriptor", action="append", default=[], help="<golden>=<pad>: een eigen descriptor")
    ap.add_argument("goldens", nargs="+", help="goldens, als pad relatief aan --corpus")
    a = ap.parse_args()
    own = dict(d.split("=", 1) for d in a.descriptor)
    with concurrent.futures.ThreadPoolExecutor(max_workers=a.workers) as pool:
        tasks = [pool.submit(play, g, os.path.join(a.corpus, g), own.get(g)) for g in a.goldens]
        for t in tasks:
            golden, status, detail = t.result()
            print(f"{golden}\t{status}\t{' '.join(detail.split())}", flush=True)


if __name__ == "__main__":
    main()
