#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# lang-1: are three common words of each new language suggested after a few letters?
# The .dict files are in the format of the native library (not readable on the build server), so this reads the word
# list each dictionary was built from (wordlists/<name>.combined of codeberg.org/Helium314/aosp-dictionaries, streamed,
# nothing is saved) and finds, for every word, after how many typed letters it is among the three most frequent words
# with that beginning (what the strip shows first). It checks the data, not the suggestion engine of the phone.
# Usage: python3 -I tools/aikb/dict_check.py [language ...]
import sys
import urllib.request

BASE = "https://codeberg.org/Helium314/aosp-dictionaries/raw/branch/main/wordlists/"
WORDS = {
    "uk": ("main_uk__experimental", ["привіт", "дякую", "сьогодні"]),
    "kk": ("main_kk__experimental", ["сәлем", "рақмет", "бүгін"]),
    "ar": ("main_ar__experimental", ["شكرا", "اليوم", "مرحبا"]),
    "id": ("main_id__experimental", ["terima", "kasih", "selamat"]),
    "cs": ("main_cs__experimental", ["děkuji", "dobrý", "dnes"]),
    "sk": ("main_sk__experimental", ["ďakujem", "dobrý", "dnes"]),
    "hr": ("main_hr__experimental", ["hvala", "dobro", "danas"]),
    "fi": ("main_fi__experimental", ["kiitos", "hyvää", "tänään"]),
    "da": ("main_da__experimental", ["tak", "hvordan", "morgen"]),
    "nb": ("main_nb__experimental", ["takk", "hvordan", "morgen"]),
    "he": ("main_he__experimental", ["שלום", "תודה", "היום"]),
    "fa": ("main_fa_IR__experimental", ["سلام", "ممنون", "امروز"]),
    "az": ("main_az", ["salam", "sağ", "bugün"]),
}


def check(lang):
    name, words = WORDS[lang]
    firsts = {w[0] for w in words}
    freq = {}  # only words that start like one of the test words
    with urllib.request.urlopen(BASE + name + ".combined", timeout=120) as r:
        for raw in r:
            line = raw.decode("utf-8", "replace").lstrip()  # some lists have no space before "word="
            if not line.startswith("word="):
                continue
            parts = line.strip().split(",")
            word = parts[0][5:]
            if not word or word[0].lower() not in firsts:
                continue
            f = next((p[2:] for p in parts[1:] if p.startswith("f=")), "0")
            f = 15 if f == "whitelist" else int(f) if f.isdigit() else 0
            if f > 0 and not any(p == "possibly_offensive=true" for p in parts):
                lw = word.lower()
                freq[lw] = max(freq.get(lw, 0), f)
    ok = True
    out = []
    for w in words:
        if w not in freq:
            out.append(f"{w}: НЕТ в словаре")
            ok = False
            continue
        typed = None
        for n in range(1, len(w) + 1):
            p = w[:n]
            top = sorted((x for x in freq if x.startswith(p)), key=lambda x: (-freq[x], x))[:3]
            if w in top or p == w:  # a whole word of the dictionary is shown as typed
                typed = p
                break
        out.append(f"{w} (частота {freq[w]}): в тройке после «{typed}»")
    print(f"{lang} [{name}]: " + "; ".join(out), flush=True)
    return ok


if __name__ == "__main__":
    langs = sys.argv[1:] or list(WORDS)
    bad = [l for l in langs if not check(l)]
    print("Итог: все слова есть" if not bad else f"Итог: не хватает слов в {', '.join(bad)}")
    sys.exit(1 if bad else 0)
