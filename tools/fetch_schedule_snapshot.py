#!/usr/bin/env python3
"""Builds assets/schedules/worldwide.txt, the bundled offline snapshot of NOAA's worldwide marine radiofax
schedules (https://www.weather.gov/media/marine/rfax.pdf).

The app parses this text at runtime with the same code it uses for a fresh download, so refreshing the snapshot
is just re-running this script (needs `pdftotext` from poppler):

    tools/fetch_schedule_snapshot.py [rfax.pdf]      # downloads the PDF if no file is given
"""
import re, subprocess, sys, tempfile, urllib.request, pathlib

URL = "https://www.weather.gov/media/marine/rfax.pdf"
OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/schedules/worldwide.txt"

def main():
    if len(sys.argv) > 1:
        pdf = pathlib.Path(sys.argv[1])
    else:
        pdf = pathlib.Path(tempfile.mkdtemp()) / "rfax.pdf"
        urllib.request.urlretrieve(URL, pdf)
    text = subprocess.run(["pdftotext", "-layout", str(pdf), "-"], check=True, capture_output=True, text=True).stdout
    lines = text.splitlines()
    published = next((m.group(0) for l in lines[:40] if (m := re.search(r"[A-Z][a-z]+ \d{1,2}, \d{4}", l))), "unknown")
    # Skip the cover, introduction and table of contents: start at the first heading that is followed by a table header.
    start = next(i for i in range(len(lines) - 3) if re.search(r"CALL SIGN", "\n".join(lines[i + 1:i + 3]), re.I) and lines[i].strip())
    end = next(i for i, l in enumerate(lines) if l.strip().lower() == "appendix a")
    body = "\n".join(lines[start:end])
    OUT.write_text(f"#snapshot NOAA worldwide marine radiofax schedules, published {published}\n{body}\n", encoding="utf-8")
    print(f"wrote {OUT} ({len(body.splitlines())} lines, published {published})")

if __name__ == "__main__":
    main()
