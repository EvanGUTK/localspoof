#!/usr/bin/env python3
"""Convert a local OSM XML extract into a bounded static offline GeoPack.

No network access. This developer tool never accepts session history or routes.
Regional extracts must be acquired separately with their attribution/license.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

MAX_INPUT = 128 * 1024 * 1024
MAX_PACK = 16 * 1024 * 1024
MAX_NODES = 50_000
MAX_EDGES = 150_000
WALK_ROADS = {"residential", "service", "unclassified", "living_street", "tertiary", "tertiary_link", "secondary", "secondary_link", "primary", "primary_link", "footway", "path", "pedestrian", "steps", "track", "cycleway"}
DRIVE_ROADS = {"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service"}
DENIED = {"no", "private"}


def coordinate(element):
    lat, lon = float(element.attrib["lat"]), float(element.attrib["lon"])
    if not math.isfinite(lat) or not math.isfinite(lon) or abs(lat) > 90 or abs(lon) > 180:
        raise ValueError("Invalid coordinate in source data")
    return {"lat": lat, "lon": lon}


def numeric_speed(text):
    match = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*(mph|km/h|kph)?\s*", text or "", re.IGNORECASE)
    if not match:
        return None
    value = float(match.group(1))
    speed = value * 0.44704 if (match.group(2) or "").lower() == "mph" else value / 3.6
    return speed if math.isfinite(speed) and 0.1 <= speed <= 100.0 else None


def road_speed(tags, direction):
    # Conditional restrictions need context not carried by a static pack; do not call them posted.
    if tags.get("maxspeed:conditional") or tags.get("maxspeed:" + direction + ":conditional"):
        return 13.9, "ESTIMATED"
    value = numeric_speed(tags.get("maxspeed:" + direction, tags.get("maxspeed")))
    return (value, "MAPPED") if value is not None else (13.9, "ESTIMATED")


def convert(input_path, pack_id, name):
    if input_path.stat().st_size > MAX_INPUT:
        raise ValueError("Source exceeds the 128 MiB converter limit; use a smaller regional extract")
    raw = input_path.read_bytes()
    text = raw.decode("utf-8-sig", errors="strict")
    if "<!DOCTYPE" in text.upper() or "<!ENTITY" in text.upper():
        raise ValueError("XML DTD and entity declarations are forbidden")
    root = ET.fromstring(text)
    if root.tag != "osm":
        raise ValueError("Source must be an OSM XML extract")
    coordinates = {}
    places = []
    for node in root.findall("node"):
        node_id = node.attrib["id"]
        if node_id in coordinates:
            raise ValueError("Duplicate source node")
        p = coordinate(node)
        coordinates[node_id] = p
        tags = {tag.attrib.get("k"): tag.attrib.get("v", "") for tag in node.findall("tag")}
        if tags.get("name") and (tags.get("place") or tags.get("amenity") or tags.get("tourism")):
            places.append({"id": node_id, "name": tags["name"][:200], **p})
    edges, used, polygons = [], set(), []
    for way in root.findall("way"):
        tags = {tag.attrib.get("k"): tag.attrib.get("v", "") for tag in way.findall("tag")}
        refs = [nd.attrib["ref"] for nd in way.findall("nd")]
        if tags.get("access") in DENIED:
            continue
        highway = tags.get("highway", "")
        walk = highway in WALK_ROADS and tags.get("foot") not in DENIED
        drive = highway in DRIVE_ROADS and tags.get("motor_vehicle") not in DENIED and tags.get("motorcar") not in DENIED and tags.get("vehicle") not in DENIED
        if (walk or drive) and any(ref not in coordinates for ref in refs):
            raise ValueError("Traversable way references missing nodes; source extract is incomplete")
        if walk or drive:
            for a, b in zip(refs, refs[1:]):
                if a == b or coordinates[a] == coordinates[b]:
                    continue
                used.update((a, b))
                if walk:
                    foot_oneway = tags.get("oneway:foot", "no")
                    edges.append({"from": b if foot_oneway == "-1" else a, "to": a if foot_oneway == "-1" else b, "modes": ["WALK"], "speedLimitMps": 3.0, "speedLimitSource": "ESTIMATED", "bidirectional": foot_oneway not in {"yes", "1", "true", "-1"}})
                if drive:
                    direction = tags.get("oneway", "yes" if tags.get("junction") == "roundabout" or highway == "motorway" else "no")
                    forward = road_speed(tags, "forward")
                    backward = road_speed(tags, "backward")
                    if direction == "-1":
                        edges.append({"from": b, "to": a, "modes": ["DRIVE"], "speedLimitMps": backward[0], "speedLimitSource": backward[1], "bidirectional": False})
                    elif direction in {"yes", "1", "true"}:
                        edges.append({"from": a, "to": b, "modes": ["DRIVE"], "speedLimitMps": forward[0], "speedLimitSource": forward[1], "bidirectional": False})
                    elif forward == backward:
                        edges.append({"from": a, "to": b, "modes": ["DRIVE"], "speedLimitMps": forward[0], "speedLimitSource": forward[1], "bidirectional": True})
                    else:
                        edges.append({"from": a, "to": b, "modes": ["DRIVE"], "speedLimitMps": forward[0], "speedLimitSource": forward[1], "bidirectional": False})
                        edges.append({"from": b, "to": a, "modes": ["DRIVE"], "speedLimitMps": backward[0], "speedLimitSource": backward[1], "bidirectional": False})
        if len(refs) >= 4 and refs[0] == refs[-1] and len(polygons) < 10_000 and (tags.get("building") or tags.get("landuse") or tags.get("natural") in {"water", "wood"}):
            if all(ref in coordinates for ref in refs) and len(refs) <= 20_000:
                polygons.append([coordinates[ref] for ref in refs])
    if not used or not edges:
        raise ValueError("Source has no public traversable roads or paths")
    if len(used) > MAX_NODES or len(edges) > MAX_EDGES or len(places) > 50_000 or sum(map(len, polygons)) > 200_000:
        raise ValueError("Regional extract exceeds GeoPack resource limits")
    nodes = [{"id": key, **coordinates[key]} for key in sorted(used)]
    all_points = [*nodes, *places, *(point for ring in polygons for point in ring)]
    pack = {"schemaVersion": 1, "id": pack_id, "name": name, "bounds": {"south": min(p["lat"] for p in all_points), "west": min(p["lon"] for p in all_points), "north": max(p["lat"] for p in all_points), "east": max(p["lon"] for p in all_points)}, "nodes": nodes, "edges": edges, "places": places, "polygons": polygons, "source": "Local OpenStreetMap XML extract; sourceSha256=" + hashlib.sha256(raw).hexdigest(), "attribution": "© OpenStreetMap contributors; ODbL 1.0; https://www.openstreetmap.org/copyright"}
    return pack


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--id", required=True)
    parser.add_argument("--name", required=True)
    args = parser.parse_args()
    try:
        if not re.fullmatch(r"[a-zA-Z0-9_-]{1,100}", args.id) or not 1 <= len(args.name) <= 200:
            raise ValueError("Invalid pack identifier or name")
        if args.input.resolve() == args.output.resolve() or args.output.exists() or Path(str(args.output) + ".manifest.json").exists():
            raise ValueError("Output must be new; converter does not overwrite files")
        pack = convert(args.input, args.id, args.name)
        encoded = json.dumps(pack, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
        if len(encoded) > MAX_PACK:
            raise ValueError("GeoPack exceeds the 16 MiB phone import limit")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("xb") as handle:
            handle.write(encoded)
        manifest = {"schemaVersion": 1, "id": pack["id"], "sizeBytes": len(encoded), "sha256": hashlib.sha256(encoded).hexdigest(), "bounds": pack["bounds"], "source": pack["source"], "attribution": pack["attribution"]}
        with Path(str(args.output) + ".manifest.json").open("x", encoding="utf-8") as handle:
            json.dump(manifest, handle, ensure_ascii=False, indent=2)
    except (OSError, ValueError, KeyError, ET.ParseError, UnicodeError):
        sys.stderr.write("GeoPack conversion failed: invalid/incomplete source, exceeded resource limit, or output already exists.\n")
        return 1
    sys.stdout.write("Static geographic pack and checksum manifest created.\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
