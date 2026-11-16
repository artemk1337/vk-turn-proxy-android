#!/usr/bin/env python3
"""Build the Android browser demo using the application's localized strings."""

import argparse
import json
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path


def build(output):
    root = Path(__file__).resolve().parents[2]
    resources = root / "app/src/main/res"
    output.mkdir(parents=True, exist_ok=True)
    for name in ("index.html", "styles.css", "app.js"):
        shutil.copy2(root / "docs/demo" / name, output / name)
    shutil.copy2(resources / "drawable-nodpi/logo.png", output / "logo.png")
    strings = {}
    for language, folder in (("en", "values"), ("ru", "values-ru")):
        strings[language] = {}
        for name in ("strings.xml", "account_strings.xml"):
            for element in ET.parse(resources / folder / name).getroot().findall("string"):
                strings[language][element.attrib["name"]] = "".join(element.itertext()).replace(r"\n", "\n")
    (output / "strings.json").write_text(json.dumps(strings, ensure_ascii=False))
    (output / ".nojekyll").touch()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    build(parser.parse_args().output.resolve())
