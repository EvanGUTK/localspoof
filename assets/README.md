# Static geographic assets

`airports.json` is the 2026-10-02 OurAirports global scheduled-service catalog: **3,269 medium/large airports**. [airports.metadata.json](airports.metadata.json) records the source URL, source hash and public-domain status. The identical desktop copy is bundled for Windows planning.

The reference coordinates may lie on runways; they are not validated terminal-access coordinates, live schedules or a navigation database. Online flight road access may be up to 5 km from a selected catalog airport and is disclosed in the preview. User endpoints remain limited to 250 m. Air legs use actual road-access points. Offline access still depends on the imported graph.

To regenerate, acquire [OurAirports airports.csv](https://ourairports.com/data/) and run `python3 tools/convert-airports.py /path/to/airports.csv /path/to/new-airports.json`. Replace both static copies and refresh metadata before rebuilding. No airport data download occurs at application runtime. OurAirports publishes its data without an accuracy guarantee.

Selected airports, searches, routes and phone positions are not written into these static files.
