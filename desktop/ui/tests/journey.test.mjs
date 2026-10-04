import test from 'node:test';
import assert from 'node:assert/strict';
import {decodePhoneLocation,phoneLocationAt,phoneLocationLabel,decodeSegments,routeFeatures} from '../src/journey.ts';

const a={lat:0,lon:179.9},b={lat:1,lon:-179.9};
const device=(overrides={})=>({point:a,source:'DEVICE',accuracyMeters:8,ageMs:100,...overrides});
test('phone location is authenticated, finite and fresh; device data is never treated as simulation',()=>{
  assert.deepEqual(decodePhoneLocation(device(),'IDLE',true),device());
  for(const value of [null,{},device({source:'UNKNOWN'}),device({point:{lat:91,lon:0}}),device({ageMs:-1}),device({ageMs:Infinity}),device({accuracyMeters:-1}),device({ageMs:30001})])assert.equal(decodePhoneLocation(value,'IDLE',true),undefined);
  assert.equal(decodePhoneLocation(device(),'IDLE',false),undefined);
  for(const state of ['ACTIVE','PAUSED','HOLDING','ERROR'])assert.equal(decodePhoneLocation(device(),state,true),undefined);
  assert.equal(decodePhoneLocation(device({source:'SIMULATION'}),'READY',true),undefined);
  assert.deepEqual(decodePhoneLocation(device({source:'SIMULATION'}),'PAUSED',true),device({source:'SIMULATION'}));
});
test('freshness advances without provider timestamps and old device dot expires after thirty seconds',()=>{
  const p=decodePhoneLocation(device({ageMs:29990}),'IDLE',true);
  assert.equal(phoneLocationAt(p,1000,1010).ageMs,30000);
  assert.equal(phoneLocationAt(p,1000,1011),undefined);
  assert.match(phoneLocationLabel(p),/Device location/);
  assert.match(phoneLocationLabel({...p,source:'SIMULATION'}),/Simulated location/);
  assert.match(phoneLocationLabel(undefined),/unavailable/i);
});
test('flight segment decoding separates roads, air interval and instant placement without widening accepted geometry',()=>{
  const segments=[{role:'DEPARTURE',kind:'INSTANT',points:[a,b],durationMs:0,routeSource:'INSTANT_LOCATION'},{role:'AIR',kind:'FLIGHT',points:[b,a],durationMs:1000,routeSource:'FLIGHT_JOURNEY'},{role:'ARRIVAL',kind:'DRIVE',points:[a,b],durationMs:2000,routeSource:'ONLINE_ROUTER'}];
  assert.deepEqual(decodeSegments(segments),segments);
  for(const invalid of [[{kind:'DRIVE',points:[],durationMs:0}],[{kind:'BOAT',points:[a],durationMs:1}],[{kind:'INSTANT',points:[a,b],durationMs:1}],[{kind:'FLIGHT',points:[a,b],durationMs:-1}],[{kind:'DRIVE',points:[{lat:NaN,lon:0}],durationMs:1}]])assert.throws(()=>decodeSegments(invalid));
  const features=routeFeatures([a,b],segments).features;
  assert.deepEqual(features.map(f=>f.properties.kind),['INSTANT','FLIGHT','DRIVE']);
  for(const f of features)assert.ok(Math.abs(f.geometry.coordinates[1][0]-f.geometry.coordinates[0][0])<1,'The line must cross the short side of the date line');
  assert.deepEqual(routeFeatures([a,b]).features.map(f=>f.properties.kind),['DRIVE']);
});

test('flight preview follows the same spherical path as engine motion, including poles and the date line',()=>{
  const west={lat:60,lon:-60},east={lat:60,lon:60};
  const segments=[{role:'DEPARTURE',kind:'INSTANT',points:[west,west],durationMs:0,routeSource:'INSTANT_LOCATION'},{role:'AIR',kind:'FLIGHT',points:[west,east],durationMs:1000,routeSource:'FLIGHT_JOURNEY'},{role:'ARRIVAL',kind:'INSTANT',points:[east,east],durationMs:0,routeSource:'INSTANT_LOCATION'}];
  const coordinates=routeFeatures([west,east],segments).features[1].geometry.coordinates;
  assert.ok(coordinates.length>2);assert.ok(Math.max(...coordinates.map(p=>p[1]))>73,'A great-circle flight bends toward the pole rather than a constant-latitude line');
  assert.deepEqual(coordinates[0],[-60,60]);assert.deepEqual(coordinates.at(-1),[60,60]);
});

test('all location sources expire uniformly and unsupported accuracy or string coordinates remain unavailable',()=>{
  for(const source of ['DEVICE','SIMULATION']){
    const state=source==='DEVICE'?'IDLE':'ACTIVE';
    assert.equal(decodePhoneLocation(device({source,ageMs:30001}),state,true),undefined);
    for(const accuracyMeters of [0,10001,Infinity])assert.equal(decodePhoneLocation(device({source,accuracyMeters}),state,true),undefined);
    assert.equal(decodePhoneLocation(device({source,point:{lat:'0',lon:1}}),state,true),undefined);
    assert.equal(phoneLocationAt(device({source,ageMs:29999}),0,2),undefined);
  }
});
test('segment seams and provenance cannot hide a placement discontinuity inside a drive or air leg',()=>{
  const segments=[{role:'DEPARTURE',kind:'INSTANT',points:[a,b],durationMs:0,routeSource:'INSTANT_LOCATION'},{role:'AIR',kind:'FLIGHT',points:[b,a],durationMs:0,routeSource:'FLIGHT_JOURNEY'},{role:'ARRIVAL',kind:'DRIVE',points:[a,b],durationMs:1,routeSource:'ONLINE_ROUTER'}];
  assert.throws(()=>decodeSegments(segments.map((s,i)=>i===1?{...s,points:[{lat:20,lon:30},a]}:s)),/continuous|segments/i);
  assert.throws(()=>decodeSegments(segments.map((s,i)=>i===0?{...s,routeProvider:'invented'}:s)),/provenance/);
});

test('spherical flight display keeps coincident and antipodal endpoints finite and never modifies the input points',()=>{
  const start={lat:0,lon:0};
  for(const end of [start,{lat:0,lon:180}]){
    const segment={role:'AIR',kind:'FLIGHT',points:[start,end],durationMs:1000,routeSource:'FLIGHT_JOURNEY'};
    const data=routeFeatures([start,end],[segment]).features[0].geometry.coordinates;
    assert.ok(data.every(p=>p.every(Number.isFinite)));assert.deepEqual(start,{lat:0,lon:0});
    assert.deepEqual(data[0],[0,0]);assert.equal(data.at(-1)[1],0);
  }
});

test('authenticated remaining flight tails decode without inventing a departure leg and reject reordered roles',()=>{
 const air={role:'AIR',kind:'FLIGHT',points:[a,b],durationMs:100,routeSource:'FLIGHT_JOURNEY'},arrival={role:'ARRIVAL',kind:'DRIVE',points:[b,a],durationMs:1000,routeSource:'ONLINE_ROUTER',routeProvider:'FOSSGIS OSRM'};
 assert.deepEqual(decodeSegments([air,arrival],true),[air,arrival]);assert.deepEqual(decodeSegments([arrival],true),[arrival]);assert.throws(()=>decodeSegments([air,arrival]));assert.throws(()=>decodeSegments([arrival,air],true));assert.throws(()=>decodeSegments([{...arrival,role:'DEPARTURE'}],true));
});
test('flight display tessellates more densely at close zoom while keeping bounded finite geographic points',()=>{
 const a={lat:80,lon:-30},b={lat:80,lon:30};const segment={role:'AIR',kind:'FLIGHT',points:[a,b],durationMs:1000,routeSource:'FLIGHT_JOURNEY'};const wide=routeFeatures([a,b],[segment],4).features[0].geometry.coordinates,close=routeFeatures([a,b],[segment],18).features[0].geometry.coordinates;assert.ok(close.length>wide.length);assert.ok(close.length<=8192);assert.ok(close.every(p=>p.every(Number.isFinite)));assert.deepEqual(close[0],[a.lon,a.lat]);assert.deepEqual(close.at(-1),[b.lon,b.lat]);
});
