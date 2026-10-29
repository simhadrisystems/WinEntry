#!/usr/bin/env python3
"""Summarise how much of a release build R8 renamed, from mapping.txt.

Usage: python3 scripts/dex_kept_report.py [mapping.txt] [baseline mapping.txt]

Play Console's Obfuscation score tracks the class-renamed share. Calibrated
with this script on vc21 (class renamed 37.4% -> Play Obfuscation 24%), so
Play ~= local - 13. Earlier ad-hoc counts (vc10 36.6 -> 21, vc11 38.8 -> 23)
used a different count and are not comparable.
"""
import re
import sys
from collections import defaultdict

GROUPS = [
    ("Apache POI group", ("org.apache.poi.", "org.apache.xmlbeans.", "org.openxmlformats.",
                          "com.microsoft.schemas.", "schemasMicrosoft", "org.etsi.", "org.w3.",
                          "org.apache.commons.", "org.apache.logging.", "com.zaxxer.")),
    ("GMS internal", ("com.google.android.gms.internal.",)),
    ("reCAPTCHA", ("com.google.android.recaptcha.",)),
    ("GMS FIDO", ("com.google.android.gms.fido.",)),
    ("GMS other", ("com.google.android.gms.",)),
    ("Firestore/grpc/protobuf", ("com.google.firebase.firestore.", "io.grpc.", "com.google.protobuf.",
                                 "com.google.firestore.")),
    ("Firebase other", ("com.google.firebase.",)),
    ("androidx", ("androidx.",)),
    ("Material", ("com.google.android.material.",)),
    ("Kotlin", ("kotlin.", "kotlinx.")),
    ("App", ("com.simhadri.",)),
]

CLASS_RE = re.compile(r"^(\S+) -> (\S+):$")
MEMBER_RE = re.compile(r"^\s+(?:\d+:\d+:)?\S+ (?:[\w$.]+\.)?([\w$<>-]+)\(.*\)(?::\d+(?::\d+)?)? -> (\S+)$")


def group_of(name):
    for label, prefixes in GROUPS:
        if name.startswith(prefixes):
            return label
    return "Other"


def analyse(path):
    stats = defaultdict(lambda: [0, 0, 0, 0])  # classes, renamed classes, methods, renamed methods
    current = None
    seen_methods = set()
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            m = CLASS_RE.match(line)
            if m:
                orig, new = m.groups()
                if new.startswith("R8$$REMOVED"):
                    current = None
                    continue
                current = orig
                seen_methods = set()
                s = stats[group_of(orig)]
                s[0] += 1
                if new != orig:
                    s[1] += 1
                continue
            if current is None or "(" not in line:
                continue
            mm = MEMBER_RE.match(line)
            if not mm:
                continue
            name, new = mm.groups()
            key = (line.split(" -> ")[0].split(":")[-1].strip(), new)
            if key in seen_methods:
                continue
            seen_methods.add(key)
            s = stats[group_of(current)]
            s[2] += 1
            if new != name:
                s[3] += 1
    return stats


def totals(stats):
    t = [sum(v[i] for v in stats.values()) for i in range(4)]
    return t


def pct(a, b):
    return 100.0 * a / b if b else 0.0


def report(path):
    stats = analyse(path)
    t = totals(stats)
    unren_methods = t[2] - t[3]
    print(f"\n{path}")
    print(f"{'Group':26} {'Classes':>8} {'Renamed':>8} {'Methods':>9} {'Renamed':>8} {'Share of unrenamed':>19}")
    for label, v in sorted(stats.items(), key=lambda kv: -(kv[1][2] - kv[1][3])):
        print(f"{label:26} {v[0]:8} {pct(v[1], v[0]):7.1f}% {v[2]:9} {pct(v[3], v[2]):7.1f}% "
              f"{pct(v[2] - v[3], unren_methods):18.1f}%")
    cls_pct = pct(t[1], t[0])
    print(f"{'TOTAL':26} {t[0]:8} {cls_pct:7.1f}% {t[2]:9} {pct(t[3], t[2]):7.1f}%")
    print(f"Predicted Play Obfuscation ~ {cls_pct - 13:.0f}% (upload gate: class renamed >= 45%)")
    return cls_pct


if __name__ == "__main__":
    args = sys.argv[1:] or ["app/build/outputs/mapping/release/mapping.txt"]
    for p in args:
        report(p)
