import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
const file=new URL('../../android/app/src/main/assets/mobile-map/motion.mjs',import.meta.url);
async function fixture(options={}){const source=await readFile(file,'utf8');const {createMotionTracker}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));let time=0,id=0;const frames=new Map(),points=[];const tracker=createMotionTracker({now:()=>time,requestFrame:cb=>{frames.set(++id,cb);return id;},cancelFrame:id=>frames.delete(id),draw:(point,stale)=>points.push({point,stale}),...options});return {tracker,points,step(ms){time+=ms;const callbacks=[...frames.values()];frames.clear();callbacks.forEach(cb=>cb(time));},frames};}
const sample=(point,sequence,extra={})=>({point,sequence,ageMs:0,health:'LIVE',speedMps:50,source:'SIMULATION',owner:'drive',...extra});
test('Acknowledged samples interpolate through a road corner without rebuilding route or predicting beyond last fix',async()=>{const f=await fixture();const a={lat:0,lon:0},corner={lat:0,lon:0.00005},b={lat:0.00005,lon:0.00005};const route=[{kind:'DRIVE',points:[a,corner,b]}];f.tracker.update(sample(a,1),route);f.step(200);f.tracker.update(sample(b,2),route);f.step(50);const moving=f.points.at(-1).point;assert.equal(moving.lat,0,'First half follows the first road edge');assert.ok(moving.lon>0&&moving.lon<corner.lon);f.step(150);assert.deepEqual(f.points.at(-1).point,b);f.step(500);assert.deepEqual(f.points.at(-1).point,b,'No extrapolation after acknowledged point');});
test('Duplicate ACKs do not restart animation and stale/paused/teleport/Stop cancel it',async()=>{const f=await fixture();const a={lat:0,lon:0},b={lat:0,lon:0.0001};f.tracker.update(sample(a,1),[]);f.step(200);f.tracker.update(sample(b,2),[]);f.step(50);const before=f.points.length;f.tracker.update(sample(b,2),[]);assert.equal(f.points.length,before);f.step(150);assert.deepEqual(f.points.at(-1).point,b);f.step(1501);assert.equal(f.points.at(-1).stale,true,'A stalled native stream visibly expires');f.tracker.update(sample(a,3,{health:'PAUSED'}),[]);assert.deepEqual(f.points.at(-1).point,a);f.tracker.update(sample({lat:40,lon:40},4),[]);assert.deepEqual(f.points.at(-1).point,{lat:40,lon:40},'Jump cannot animate across earth');f.tracker.update({point:null,health:'IDLE'},[]);assert.equal(f.points.at(-1).point,null);assert.equal(f.frames.size,0);});
test('Old sequence is rejected without renewing freshness; new placement ownership snaps and disposal clears frames',async()=>{const f=await fixture();f.tracker.update(sample({lat:0,lon:0},10),[]);f.step(200);f.tracker.update(sample({lat:0,lon:.0001},9),[]);assert.equal(f.points.at(-1).point.lon,0,'An old ACK cannot move the current point backwards');f.step(1501);assert.equal(f.points.at(-1).stale,true,'An old ACK cannot renew freshness');f.tracker.update(sample({lat:0,lon:.0002},10,{owner:'drive:placement2'}),[]);assert.equal(f.points.at(-1).point.lon,.0002);f.tracker.dispose();assert.equal(f.frames.size,0);});

test('Airborne ACKs interpolate even when the complete journey includes two ground legs',async()=>{const f=await fixture(),a={lat:1,lon:2},b={lat:1,lon:2.0006};const legs=[{kind:'DRIVE',points:[{lat:0,lon:0},{lat:0,lon:1}]},{kind:'FLIGHT',points:[a,b]}];f.tracker.update(sample(a,1,{owner:'FLIGHT:FLIGHT_INTERVAL:1',speedMps:350}),legs);f.step(200);f.tracker.update(sample(b,2,{owner:'FLIGHT:FLIGHT_INTERVAL:1',speedMps:350}),legs);f.step(100);assert.ok(f.points.at(-1).point.lon>a.lon&&f.points.at(-1).point.lon<b.lon);f.step(100);assert.deepEqual(f.points.at(-1).point,b);});

test('An explicit short jump placement generation snaps without interpolation',async()=>{const f=await fixture(),a={lat:0,lon:0},b={lat:0,lon:.0001};f.tracker.update(sample(a,1,{owner:'DRIVE:NONE:1',speedMps:0}),[]);f.step(200);f.tracker.update(sample(b,2,{owner:'DRIVE:NONE:2',speedMps:0}),[]);assert.deepEqual(f.points.at(-1).point,b,'Even a ten-metre explicit jump is exact');f.step(100);assert.deepEqual(f.points.at(-1).point,b);});

test('Flight line tessellation and acknowledged marker use identical spherical geometry',async()=>{
 const {sphericalPoint,flightCoordinates}=await import('../../android/app/src/main/assets/mobile-map/motion.mjs');
 const a={lat:35.0424,lon:-89.9767},b={lat:36.1263,lon:-86.6774},mid=sphericalPoint(a,b,.5),coordinates=flightCoordinates(a,b,18);
 assert.ok(coordinates.length>65,'Close zoom refines the old fixed 64-edge line');
 assert.ok(coordinates.some(([lon,lat])=>Math.abs(lon-mid.lon)<1e-10&&Math.abs(lat-mid.lat)<1e-10),'A confirmed midpoint lies exactly on the tessellated spherical line');
 assert.deepEqual(coordinates[0],[a.lon,a.lat]);assert.deepEqual(coordinates.at(-1),[b.lon,b.lat]);assert.ok(coordinates.length<=8194,'Geometry stays bounded');
 const crossing=flightCoordinates({lat:40,lon:179},{lat:40,lon:-179},14);assert.ok(crossing.every(([lon,lat])=>Math.abs(lon)>170&&lat>=40),'No flight detour across zero longitude');
});
test('Hidden map releases display frames and return expires stale acknowledged fixes',async()=>{
 const f=await fixture();f.tracker.update(sample({lat:1,lon:2},1),[]);f.tracker.setVisible(false);assert.equal(f.frames.size,0);f.step(2000);f.tracker.setVisible(true);assert.equal(f.points.at(-1).stale,true);assert.deepEqual(f.points.at(-1).point,{lat:1,lon:2});
});

test('Overlapping fresh ACKs continue from the displayed point without a backward jump',async()=>{
 const f=await fixture(),a={lat:0,lon:0},b={lat:0,lon:.0001},c={lat:0,lon:.0002};
 f.tracker.update(sample(a,1));f.step(200);f.tracker.update(sample(b,2));f.step(50);
 const displayed=f.points.at(-1).point.lon;assert.ok(displayed>0&&displayed<b.lon);
 f.tracker.update(sample(c,3));assert.ok(Math.abs(f.points.at(-1).point.lon-displayed)<1e-12,'A new acknowledged target must start at the last drawn location');
 f.step(25);assert.ok(f.points.at(-1).point.lon>displayed);assert.ok(f.points.at(-1).point.lon<c.lon);
 f.step(1000);assert.equal(f.points.at(-1).point.lon,c.lon,'Never extrapolate beyond the latest acknowledged location');
});
test('Duplicate ACK receipt does not shorten the next genuine sample cadence',async()=>{
 const f=await fixture(),a={lat:0,lon:0},b={lat:0,lon:.0001};
 f.tracker.update(sample(a,1));f.step(190);f.tracker.update(sample(a,1));f.step(10);f.tracker.update(sample(b,2));f.step(20);
 assert.ok(f.points.at(-1).point.lon>0&&f.points.at(-1).point.lon<b.lon,'The animation uses the 200ms genuine sample interval, not the 10ms duplicate receipt interval');
 f.step(180);assert.equal(f.points.at(-1).point.lon,b.lon);
});
test('Repeated visible replay cannot terminate an in-progress display animation',async()=>{
 const f=await fixture(),a={lat:0,lon:0},b={lat:0,lon:.0001};
 f.tracker.update(sample(a,1));f.step(200);f.tracker.update(sample(b,2));f.step(50);const displayed=f.points.at(-1).point.lon,draws=f.points.length;
 f.tracker.setVisible(true);f.tracker.setVisible(true);assert.equal(f.points.length,draws,'Idempotent visibility has no display side effect');
 f.step(50);assert.ok(f.points.at(-1).point.lon>displayed&&f.points.at(-1).point.lon<b.lon);
});

test('Reduced motion displays acknowledged targets directly and counters retain no coordinates or history',async()=>{
 const f=await fixture({reducedMotion:()=>true}),a={lat:0,lon:0},b={lat:0,lon:.0001};f.tracker.update(sample(a,1));f.step(200);f.tracker.update(sample(b,2));assert.deepEqual(f.points.at(-1).point,b);f.tracker.update(sample(b,2));
 const metrics=f.tracker.diagnostics();assert.equal(metrics.samples,2);assert.equal(metrics.duplicates,1);assert.equal(metrics.sampleIntervalMs,200);assert.ok(Object.values(metrics).every(Number.isFinite));assert.equal('point' in metrics,false);assert.equal('history' in metrics,false);
});
