import * as maplibregl from './maplibre-gl.mjs';
import * as pmtiles from './pmtiles.mjs';
import * as protomaps from './basemaps.mjs';
window.maplibregl=maplibregl;window.pmtiles=pmtiles;window.protomaps=protomaps;
maplibregl.setWorkerUrl(new URL('./maplibre-gl-worker.mjs',import.meta.url).href);
maplibregl.setWorkerCount(2);
await import('../src/app.js');
