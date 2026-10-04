#!/usr/bin/env python3
"""Build a static airport catalog from a separately acquired OurAirports CSV."""
import argparse
import csv
import io
import json
import math
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        if args.input.stat().st_size > 32 * 1024 * 1024 or args.output.exists():
            raise ValueError()
        text = args.input.read_text(encoding="utf-8-sig")
        airports, ids = [], set()
        for row in csv.DictReader(io.StringIO(text)):
            if row.get("type") not in {"large_airport", "medium_airport"} or row.get("scheduled_service") != "yes":
                continue
            key = row.get("iata_code") or row.get("ident") or ""
            lat, lon = float(row["latitude_deg"]), float(row["longitude_deg"])
            if not key or len(key) > 128 or key in ids or not math.isfinite(lat) or not math.isfinite(lon) or abs(lat) > 90 or abs(lon) > 180:
                continue
            ids.add(key)
            airports.append({"id": key, "name": row["name"][:512], "lat": lat, "lon": lon, "type": row["type"], "scheduledService": True, "closed": False})
            if len(airports) > 100_000:
                raise ValueError()
        if not airports:
            raise ValueError()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as handle:
            json.dump(airports, handle, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    except (OSError, UnicodeError, ValueError, KeyError, csv.Error):
        sys.stderr.write("Airport conversion failed: source format/limits invalid or output already exists.\n")
        return 1
    sys.stdout.write("Static scheduled-service airport catalog created.\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
