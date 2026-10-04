import {decodeSegments} from './journey.ts';
import type {TripStop,Vacation} from './vacation.ts';
import type {JourneySegment} from './journey.ts';
export type Point = { lat: number; lon: number };
export type Mode = 'INSTANT' | 'WALK' | 'DRIVE' | 'FLIGHT';
export type Device = { serial: string; state: string; model?: string };
export type GroundMode='DRIVE'|'INSTANT';
export type FlightTransitMode='TIMED'|'JUMP';
export type PlanInput = { mode: string; originLat: string; originLon: string; destinationLat: string; destinationLon: string; speed: string; dwell: string; flightDuration: string; radioPolicy: string;departureGroundMode?:string;arrivalGroundMode?:string;flightTransitMode?:string };
export type Plan = { mode: Mode; origin: Point; destination: Point; speedMps: number; waypoints: Point[]; stops?:TripStop[];vacation?:Vacation;flightSpeedMps?:number;drivingSpeedMode?:'FIXED'|'VARIABLE';driveMinMps?:number;driveMaxMps?:number;followRoadLimits?:boolean; dwellSeconds: number; flightDurationSeconds: number; radioPolicy: 'MANUAL' | 'SIMULATION' | 'USB';departureGroundMode?:GroundMode;arrivalGroundMode?:GroundMode;flightTransitMode?:FlightTransitMode };
export type GeoPack = { schemaVersion: 1; id: string; name: string; bounds: { south: number; west: number; north: number; east: number }; nodes: {id: string;lat: number;lon: number}[]; edges: {from:string;to:string;modes: ('WALK'|'DRIVE')[]; speedLimitMps: number; speedLimitSource?:'MAPPED'|'ESTIMATED'|'UNKNOWN'; bidirectional: boolean}[]; places: {id:string;name:string;lat:number;lon:number}[]; polygons: Point[][]; source:string;attribution:string };
const fail = (message: string): never => { throw new Error(message); };
export function validateAdbPath(value:string):string{
  const path=value.trim();
  if(path.length===0||path.length>4096||/[\u0000-\u001f]/.test(path)||!(/^[A-Za-z]:[\\/]/.test(path)||path.startsWith('/'))||!/(?:^|[\\/])adb(?:\.exe)?$/.test(path))fail('Enter an absolute path to adb or adb.exe.');
  return path;
}
const finite = (value: unknown): number => typeof value === 'number' && Number.isFinite(value) ? value : fail('Enter a finite number.');
const numberInput = (value: string): number => value.trim() !== '' ? finite(Number(value)) : fail('Complete all coordinate and duration fields.');
export function point(lat: string | number, lon: string | number): Point {
  const a = typeof lat === 'string' ? numberInput(lat) : finite(lat);
  const b = typeof lon === 'string' ? numberInput(lon) : finite(lon);
  if (Math.abs(a)>90 || Math.abs(b)>180) fail('Latitude must be −90 to 90; longitude −180 to 180.');
  return {lat:a,lon:b};
}
export function planFromInputs(input: PlanInput): Plan {
  if (!['INSTANT','WALK','DRIVE','FLIGHT'].includes(input.mode)) fail('Choose a supported mode.');
  if (!['MANUAL','SIMULATION','USB'].includes(input.radioPolicy)) fail('Choose a supported airplane control.');
  const speed = numberInput(input.speed), dwell = input.mode==='FLIGHT'?numberInput(input.dwell):30, flight = input.mode==='FLIGHT'&&input.flightTransitMode!=='JUMP'?numberInput(input.flightDuration):3600;
  const max = input.mode === 'WALK' ? 3 : 56;
  if(speed<0.1 || speed>max) fail(`Speed must be 0.1–${max} m/s.`);
  if(dwell<0 || dwell>3600) fail('Boarding hold must be 0–3600 seconds.');
  if(flight<0 || flight>86400 || (input.mode==='FLIGHT' && flight<1)) fail('Flight duration must be 1–86400 seconds.');
  const departureGroundMode=input.departureGroundMode??'DRIVE',arrivalGroundMode=input.arrivalGroundMode??'DRIVE',flightTransitMode=input.flightTransitMode??'TIMED';
  if(!['DRIVE','INSTANT'].includes(departureGroundMode)||!['DRIVE','INSTANT'].includes(arrivalGroundMode)||!['TIMED','JUMP'].includes(flightTransitMode))fail('Choose a supported airport leg and flight interval.');
  return {...(input.mode==='FLIGHT'?{departureGroundMode:departureGroundMode as GroundMode,arrivalGroundMode:arrivalGroundMode as GroundMode,flightTransitMode:flightTransitMode as FlightTransitMode}:{}),mode:input.mode as Mode, origin:point(input.originLat,input.originLon),destination:point(input.destinationLat,input.destinationLon),speedMps:speed,waypoints:[],dwellSeconds:dwell,flightDurationSeconds:flight,radioPolicy:input.radioPolicy as Plan['radioPolicy']};
}
export const eligibleDevices = (devices: Device[]): Device[] => devices.filter(device => device.state==='device' && !!device.serial && !device.serial.includes(':'));
export const longitudeDelta = (lon: number, reference: number): number => ((lon-reference+540)%360)-180;
const wrap = (lon: number): number => ((lon+540)%360)-180;
export const project = (p: Point, center: Point, zoom: number, width: number, height: number): {x:number;y:number} => ({x:width/2+longitudeDelta(p.lon,center.lon)*width*zoom/360,y:height/2-(p.lat-center.lat)*width*zoom/360});
export const unproject = (x:number,y:number,center:Point,zoom:number,width:number,height:number): Point => ({lat:Math.max(-90,Math.min(90,center.lat-(y-height/2)*360/(width*zoom))),lon:wrap(center.lon+(x-width/2)*360/(width*zoom))});
export function distance(a:Point,b:Point):number { const rad=Math.PI/180, dLat=(b.lat-a.lat)*rad,dLon=longitudeDelta(b.lon,a.lon)*rad;const h=Math.sin(dLat/2)**2+Math.cos(a.lat*rad)*Math.cos(b.lat*rad)*Math.sin(dLon/2)**2;return 6371008.8*2*Math.asin(Math.min(1,Math.sqrt(h))); }
export const flightSuggested = (meters:number):boolean => meters>402336;
export type Preview={points:Point[];distanceM:number;durationSeconds:number;departureAirportName?:string;arrivalAirportName?:string;originAirportAccessPoint?:Point;destinationAirportAccessPoint?:Point;segments:JourneySegment[];departureGroundMode?:GroundMode;arrivalGroundMode?:GroundMode;flightTransitMode?:FlightTransitMode;simplified:boolean};
export function decodePreview(value:unknown,remaining=false):Preview{
  const preview=object(value),geometry=list(preview.geometry,8192);if(geometry.length===0)fail('Phone returned an empty route preview.');
  const points=geometry.map(value=>{const p=object(value);return point(finite(p.lat),finite(p.lon));});
  const distanceM=finite(preview.distanceMeters),durationMs=finite(preview.durationMs);if(distanceM<0||durationMs<0)fail('Phone preview duration is invalid.');
  const airportName=(value:unknown):string|undefined=>value==null?undefined:label(object(value).name);
  const accessPoints:Pick<Preview,'originAirportAccessPoint'|'destinationAirportAccessPoint'>={};
  for(const key of ['originAirportAccessPoint','destinationAirportAccessPoint'] as const){if(preview[key]!=null){const p=object(preview[key]);accessPoints[key]=point(finite(p.lat),finite(p.lon));}}
  const segments=decodeSegments(preview.segments,remaining);
  let flightOptions:Pick<Preview,'departureGroundMode'|'arrivalGroundMode'|'flightTransitMode'>={};
  if(preview.mode==='FLIGHT'){
    if((remaining?segments.length<1:segments.length!==3)||!['DRIVE','INSTANT'].includes(String(preview.departureGroundMode))||!['DRIVE','INSTANT'].includes(String(preview.arrivalGroundMode))||!['TIMED','JUMP'].includes(String(preview.flightTransitMode)))fail('Phone returned invalid flight preview options.');
    const departure=segments.find(s=>s.role==='DEPARTURE'),air=segments.find(s=>s.role==='AIR'),arrival=segments.find(s=>s.role==='ARRIVAL');if(departure&&departure.kind!==preview.departureGroundMode||arrival?.kind!==preview.arrivalGroundMode||air&&(preview.flightTransitMode==='JUMP'?air.durationMs!==0:air.durationMs<(remaining?0:1000)))fail('Phone returned inconsistent flight preview options.');
    flightOptions={departureGroundMode:preview.departureGroundMode as GroundMode,arrivalGroundMode:preview.arrivalGroundMode as GroundMode,flightTransitMode:preview.flightTransitMode as FlightTransitMode};
  }else if(segments.length)fail('Phone returned flight segments for an ordinary journey.');
  return {...flightOptions,segments,points,distanceM,durationSeconds:durationMs/1000,departureAirportName:airportName(preview.originAirport),arrivalAirportName:airportName(preview.destinationAirport),simplified:preview.geometrySimplified===true,...accessPoints};
}
export function packChunks(value:string):string[]{
  if(new TextEncoder().encode(value).length>16*1024*1024)fail('Pack exceeds 16 MiB.');
  const chunks:string[]=[];
  for(let start=0;start<value.length;){let end=Math.min(value.length,start+32768);const last=value.charCodeAt(end-1);if(end<value.length&&last>=0xd800&&last<=0xdbff)end--;chunks.push(value.slice(start,end));start=end;}
  return chunks;
}
function object(value:unknown):Record<string,unknown> { return value!==null && typeof value==='object' && !Array.isArray(value) ? value as Record<string,unknown> : fail('Invalid geographic pack.'); }
function list(value:unknown,max:number):unknown[] { return Array.isArray(value) && value.length<=max ? value : fail('Pack is incomplete or exceeds a resource limit.'); }
function label(value:unknown,max=200):string { return typeof value==='string' && value.length>0 && value.length<=max ? value : fail('Pack metadata is invalid.'); }
export function validateGeoPack(value:unknown):GeoPack {
  const pack=object(value); if(pack.schemaVersion!==1) fail('Only GeoPack version 1 is supported.');
  label(pack.id,100);label(pack.name);label(pack.source,1000);label(pack.attribution,2000);
  const bounds=object(pack.bounds),south=finite(bounds.south),north=finite(bounds.north),west=finite(bounds.west),east=finite(bounds.east);
  point(south,west);point(north,east);if(south>north) fail('Pack bounds are invalid.');
  const ids = new Set<string>();
  for(const value of list(pack.nodes,50000)) {const node=object(value),id=label(node.id,100);point(finite(node.lat),finite(node.lon));if(ids.has(id))fail('Pack has duplicate nodes.');ids.add(id);}
  for(const value of list(pack.edges,150000)) {const edge=object(value);if(!ids.has(label(edge.from,100))||!ids.has(label(edge.to,100))) fail('Pack edge references a missing node.');const modes=list(edge.modes,2);if(modes.length===0||modes.some(mode=>mode!=='WALK'&&mode!=='DRIVE')) fail('Pack traversal profile is invalid.');const speed=finite(edge.speedLimitMps);if(speed<=0||speed>100)fail('Pack edge speed is invalid.');if(edge.speedLimitSource!==undefined&&!['MAPPED','ESTIMATED','UNKNOWN'].includes(String(edge.speedLimitSource)))fail('Pack speed provenance is invalid.');if(typeof edge.bidirectional!=='boolean')fail('Pack edge direction is invalid.');}
  for(const value of list(pack.places,50000)){const place=object(value);label(place.id,100);label(place.name);point(finite(place.lat),finite(place.lon));}
  let vertices=0;for(const value of list(pack.polygons,10000)){const polygon=list(value,20000);if(polygon.length<3)fail('Polygon is incomplete.');for(const coordinate of polygon){const p=object(coordinate);point(finite(p.lat),finite(p.lon));if(++vertices>200000)fail('Pack geometry exceeds a resource limit.');}}
  const valid=pack as unknown as GeoPack;
  return {schemaVersion:1,id:valid.id,name:valid.name,bounds:{south,west,north,east},source:valid.source,attribution:valid.attribution,
    nodes:valid.nodes.map(n=>({id:n.id,lat:n.lat,lon:n.lon})),
    edges:valid.edges.map(e=>({from:e.from,to:e.to,modes:[...e.modes],speedLimitMps:e.speedLimitMps,...(e.speedLimitSource===undefined?{}:{speedLimitSource:e.speedLimitSource}),bidirectional:e.bidirectional})),
    places:valid.places.map(p=>({id:p.id,name:p.name,lat:p.lat,lon:p.lon})),
    polygons:valid.polygons.map(polygon=>polygon.map(p=>({lat:p.lat,lon:p.lon})))};
}
