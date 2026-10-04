# Offline GeoPack resources

`synthetic-grid.geopack.json` is **synthetic test data at coordinates near 0,0**. Its edges are not actual roads. Import it only to test controls and routing; it is deliberately labeled as a synthetic fixture. No real street coverage is bundled in this build.

For actual road routes, acquire a small regional OpenStreetMap XML extract separately and convert it locally:

```bash
python3 tools/build-geopack.py /path/to/region.osm /path/to/region.geopack.json --id region --name "Region offline map"
```

The tool preserves directed driving and unrestricted walking according to supported tags, validates references, excludes private/no-access ways, creates local place records, and writes a SHA-256 manifest. It accepts strict UTF-8 OSM XML, rejects DTD/entities, caps input at 128 MiB, and refuses to overwrite existing outputs. GeoPack size is capped at 16 MiB, 50,000 graph nodes, 150,000 edges, 50,000 places and 200,000 polygon vertices. Use a smaller extract when a limit is reached.

The app imports the pack into memory; it makes no download or routing request. Static files that the user already has remain their own files. Within imported coverage, WALK and DRIVE use graph routes. Outside it, an explicit custom path is labeled as having no road information. A missing pack never silently becomes a road route.

Preserve [OpenStreetMap attribution and ODbL terms](https://www.openstreetmap.org/copyright) when distributing derived packs. Do not scrape the public OSM tile service for offline packs. The SHA-256 sidecar checks integrity; it does not establish publisher authenticity unless the manifest is independently trusted or signed.
