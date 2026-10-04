import test from 'node:test';
import assert from 'node:assert/strict';
import {decodeSavedStore,filterSaved} from '../src/saved.ts';
const place={id:'11111111-1111-4111-8111-111111111111',name:'Tokyo station',group:'Japan',kind:'PLACE',destination:{lat:35.6812,lon:139.7671}};
test('saved items preserve explicit collections and route drafts independently from runtime',()=>{
 const route={...place,id:'22222222-2222-4222-8222-222222222222',name:'Airport trip',kind:'ROUTE',mode:'FLIGHT',origin:{lat:35,lon:139},speedMps:13.9,flightSpeedMps:230};
 const store=decodeSavedStore({version:1,items:[place,route]});store.items[0].destination.lat=0;assert.equal(place.destination.lat,35.6812);assert.equal(filterSaved(store.items,'japan').length,2);assert.equal(filterSaved(store.items,'station')[0].name,'Tokyo station');
});
test('invalid or duplicate saved locations and unbounded route speeds/stops are rejected',()=>{
 for(const item of [{...place,id:'not-uuid'},{...place,name:''},{...place,destination:{lat:91,lon:0}},{...place,kind:'ROUTE',origin:{lat:0,lon:0},mode:'WALK',speedMps:4}])assert.throws(()=>decodeSavedStore({version:1,items:[item]}));
 assert.throws(()=>decodeSavedStore({version:1,items:[place,place]}));assert.throws(()=>decodeSavedStore({version:2,items:[]}));
});
test('mobile plane speed alias, legacy waypoint dwells and optional route defaults remain portable',()=>{
 const common={...place,id:'33333333-3333-4333-8333-333333333333',kind:'ROUTE',mode:'FLIGHT',origin:{lat:0,lon:0},planeSpeedMps:200,dwellSeconds:90};const flight=decodeSavedStore({version:1,items:[common]}).items[0];assert.equal(flight.flightSpeedMps,200);assert.equal(flight.speedMps,13.9);assert.equal(flight.dwellSeconds,90);
 const walk=decodeSavedStore({version:1,items:[{...common,mode:'WALK',waypoints:[{lat:0,lon:0.01}],stopDwellSeconds:45}]}).items[0];assert.equal(walk.speedMps,1.4);assert.equal(walk.stops[0].dwellSeconds,45);assert.deepEqual(walk.waypoints,[]);
 assert.throws(()=>decodeSavedStore({version:1,items:[{...common,flightSpeedMps:220}]}));assert.throws(()=>decodeSavedStore({version:1,items:[{...common,token:'authority'}]}));
});
test('saved variable cruising and road-limit preference round trip without losing 125mph support',()=>{
 const route={...place,kind:'ROUTE',origin:{lat:0,lon:0},mode:'DRIVE',speedMps:55.88,drivingSpeedMode:'VARIABLE',driveMinMps:40.2336,driveMaxMps:55.88,followRoadLimits:false};
 const first=decodeSavedStore({version:1,items:[route]});assert.deepEqual(decodeSavedStore(JSON.parse(JSON.stringify(first))),first);assert.equal(first.items[0].driveMaxMps,55.88);assert.equal(first.items[0].followRoadLimits,false);
 for(const extra of [{driveMinMps:56,driveMaxMps:40},{driveMaxMps:57},{drivingSpeedMode:'RANDOM'},{followRoadLimits:'false'}])assert.throws(()=>decodeSavedStore({version:1,items:[{...route,...extra}]}));
 const legacy=decodeSavedStore({version:1,items:[{...place,kind:'ROUTE',origin:{lat:0,lon:0},mode:'DRIVE',speedMps:13.9}]}).items[0];assert.equal(legacy.drivingSpeedMode,'FIXED');assert.equal(legacy.driveMinMps,13.9);assert.equal(legacy.followRoadLimits,true);
});
