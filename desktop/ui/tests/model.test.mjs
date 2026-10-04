import test from 'node:test';
import assert from 'node:assert/strict';
import { point, planFromInputs, longitudeDelta, project, unproject, eligibleDevices, validateGeoPack, flightSuggested, packChunks, decodePreview,validateAdbPath } from '../src/model.ts';

test('coordinates reject blanks, impossible and nonfinite values', () => {
  assert.deepEqual(point('0', '-180'), {lat: 0, lon: -180});
  for (const input of [['', '0'], ['91', '0'], ['0', '181'], ['NaN', '0'], ['0', 'Infinity']]) assert.throws(() => point(...input));
});
test('maps choose the short antimeridian displacement and invert projection', () => {
  assert.equal(longitudeDelta(-179,179), 2);
  const center = {lat: 10, lon: 179};
  const p = project({lat: 11, lon: -179}, center, 4, 1000, 500);
  const roundTrip = unproject(p.x,p.y,center,4,1000,500);
  assert.ok(Math.abs(roundTrip.lat-11)<1e-8);
  assert.ok(Math.abs(roundTrip.lon+179)<1e-8);
});
test('only ready USB devices can be selected', () => {
  assert.deepEqual(eligibleDevices([]), []);
  assert.deepEqual(eligibleDevices([{serial:'test',state:'unauthorized'},{serial:'ok',state:'device',model:'Synthetic'}]), [{serial:'ok',state:'device',model:'Synthetic'}]);
});
test('route payload validates velocity, duration and radio policy', () => {
  const input={mode:'WALK',originLat:'10',originLon:'10',destinationLat:'11',destinationLon:'11',speed:'1.4',dwell:'30',flightDuration:'3600',radioPolicy:'MANUAL'};
  const plan=planFromInputs(input);
  assert.equal(plan.speedMps,1.4);
  assert.deepEqual(plan.waypoints,[]);
  assert.throws(()=>planFromInputs({...input,speed:'100'}));
  assert.throws(()=>planFromInputs({...input,mode:'FLIGHT',dwell:'-1'}));
  assert.throws(()=>planFromInputs({...input,radioPolicy:'SILENT'}));
  assert.throws(()=>planFromInputs({...input,flightDuration:'0',mode:'FLIGHT'}));
});
test('flight suggestion uses strictly greater than 250 statute miles', () => {
  assert.equal(flightSuggested(402335),false);
  assert.equal(flightSuggested(402336),false);
  assert.equal(flightSuggested(402337),true);
});
test('geopack rejects unbounded and malformed data before import', () => {
  assert.throws(()=>validateGeoPack({}));
  assert.throws(()=>validateGeoPack({schemaVersion:1,nodes:[{id:'x',lat:100,lon:0}],edges:[],features:[],places:[],airports:[],manifest:{}}));
  assert.throws(()=>validateGeoPack({schemaVersion:1,nodes:Array(50001).fill({id:'x',lat:0,lon:0}),edges:[],features:[],places:[],airports:[],manifest:{}}));
});

const syntheticPack={schemaVersion:1,id:'synthetic',name:'Synthetic test pack',bounds:{south:0,west:0,north:2,east:2},nodes:[{id:'a',lat:0,lon:0},{id:'b',lat:1,lon:1}],edges:[{from:'a',to:'b',modes:['WALK'],speedLimitMps:2,bidirectional:true}],places:[{id:'place',name:'Synthetic place',lat:1,lon:1}],polygons:[[{lat:0,lon:0},{lat:0,lon:1},{lat:1,lon:1}]],source:'synthetic tests',attribution:'Synthetic; not real roads'};
test('valid packs are normalized so unknown fields cannot travel to phone',()=>{
  assert.deepEqual(validateGeoPack({...syntheticPack,activityHistory:['never forward']}),syntheticPack);
});
test('geopack rejects graph references, duplicate nodes and invalid traversal',()=>{
  assert.throws(()=>validateGeoPack({...syntheticPack,edges:[{...syntheticPack.edges[0],to:'missing'}]}));
  assert.throws(()=>validateGeoPack({...syntheticPack,nodes:[syntheticPack.nodes[0],syntheticPack.nodes[0]]}));
  assert.throws(()=>validateGeoPack({...syntheticPack,edges:[{...syntheticPack.edges[0],modes:['AIR']}] }));
  assert.throws(()=>validateGeoPack({...syntheticPack,polygons:[[{lat:100,lon:0},{lat:0,lon:0},{lat:1,lon:1}]]}));
});
test('large pack chunks preserve Unicode and stay below the frame data cap',()=>{
  const value='a'.repeat(32767)+'😀'+'界'.repeat(70000);
  const chunks=packChunks(value);
  assert.equal(chunks.join(''),value);
  for(const chunk of chunks){assert.ok(new TextEncoder().encode(chunk).length<=131072);assert.doesNotMatch(chunk,/\p{Surrogate}/u);}
  assert.equal(packChunks('a'.repeat(16*1024*1024)).length,512);
});
test('canonical Android preview decodes geometry, milliseconds and airport legs',()=>{
  const wire={geometry:[{lat:1,lon:2},{lat:3,lon:4}],distanceMeters:1500,durationMs:120000,originAirport:{id:'a',name:'Synthetic departure'},destinationAirport:{id:'b',name:'Synthetic arrival'},geometrySimplified:true};
  assert.deepEqual(decodePreview(wire),{points:wire.geometry,distanceM:1500,durationSeconds:120,segments:[],departureAirportName:'Synthetic departure',arrivalAirportName:'Synthetic arrival',simplified:true});
  assert.throws(()=>decodePreview({geometry:[{lat:Infinity,lon:0}],distanceMeters:0,durationMs:0}));
  assert.throws(()=>decodePreview({points:[],distanceM:0,durationSeconds:0}));
});
test('instant preview holds its single destination without a travel duration',()=>{
  const destination={lat:1,lon:2};
  const preview=decodePreview({mode:'INSTANT',geometry:[destination],distanceMeters:0,durationMs:0,originAirport:null,destinationAirport:null,geometrySimplified:false});
  assert.deepEqual(preview.points,[destination]);assert.equal(preview.distanceM,0);assert.equal(preview.durationSeconds,0);
});
test('flight preview exposes actual ground access points and validates them',()=>{
  const fixture={geometry:[{lat:1,lon:2}],distanceMeters:10,durationMs:1000,originAirportAccessPoint:{lat:1,lon:2},destinationAirportAccessPoint:{lat:3,lon:4}};
  const preview=decodePreview(fixture);
  assert.deepEqual(preview.originAirportAccessPoint,fixture.originAirportAccessPoint);assert.deepEqual(preview.destinationAirportAccessPoint,fixture.destinationAirportAccessPoint);
  assert.throws(()=>decodePreview({...fixture,originAirportAccessPoint:{lat:91,lon:0}}));
});
test('ADB selection requires an absolute executable path on Windows or Linux',()=>{
  assert.equal(validateAdbPath('/synthetic/platform-tools/adb'),'/synthetic/platform-tools/adb');
  assert.equal(validateAdbPath('C:\\Synthetic Tools\\adb.exe'),'C:\\Synthetic Tools\\adb.exe');
  for(const value of ['','adb','platform-tools/adb','C:adb.exe','/tmp/shell','/tmp/adb\u0000'])assert.throws(()=>validateAdbPath(value));
});
test('boarding hold is flight-only and bounded to the wire maximum',()=>{
  const input={mode:'FLIGHT',originLat:'10',originLon:'10',destinationLat:'11',destinationLon:'11',speed:'13.9',dwell:'3600',flightDuration:'3600',radioPolicy:'MANUAL'};
  assert.equal(planFromInputs(input).dwellSeconds,3600);
  assert.throws(()=>planFromInputs({...input,dwell:'3601'}));
  assert.equal(planFromInputs({...input,mode:'WALK',speed:'1.4',dwell:'invalid',flightDuration:'invalid'}).dwellSeconds,30);
});

test('flight controls are emitted only for airport journeys and defaults match Android leg options',()=>{
  const input={mode:'INSTANT',originLat:'10',originLon:'10',destinationLat:'11',destinationLon:'11',speed:'1.4',dwell:'30',flightDuration:'3600',radioPolicy:'MANUAL',departureGroundMode:'INSTANT',arrivalGroundMode:'DRIVE',flightTransitMode:'JUMP'};
  for(const mode of ['INSTANT','WALK','DRIVE']){
    const p=planFromInputs({...input,mode});
    for(const key of ['departureGroundMode','arrivalGroundMode','flightTransitMode'])assert.equal(Object.hasOwn(p,key),false,'Non-flight Android requests forbid flight-specific options');
  }
  const selected=planFromInputs({...input,mode:'FLIGHT'});assert.equal(selected.departureGroundMode,'INSTANT');assert.equal(selected.arrivalGroundMode,'DRIVE');assert.equal(selected.flightTransitMode,'JUMP');
  for(const key of ['departureGroundMode','arrivalGroundMode','flightTransitMode'])assert.throws(()=>planFromInputs({...input,mode:'FLIGHT',[key]:'UNKNOWN'}));
});

test('minor3 flight previews require validated segments and matching explicit travel options',()=>{
  const a={lat:1,lon:2},b={lat:3,lon:4};
  const segments=[{role:'DEPARTURE',kind:'INSTANT',points:[a,a],durationMs:0,routeSource:'INSTANT_LOCATION'},{role:'AIR',kind:'FLIGHT',points:[a,b],durationMs:0,routeSource:'FLIGHT_JOURNEY'},{role:'ARRIVAL',kind:'DRIVE',points:[b,b],durationMs:0,routeSource:'OFFLINE_GEOPACK'}];
  const fixture={mode:'FLIGHT',geometry:[a,b],distanceMeters:10,durationMs:3000,departureGroundMode:'INSTANT',arrivalGroundMode:'DRIVE',flightTransitMode:'JUMP',segments};
  const preview=decodePreview(fixture);assert.equal(preview.departureGroundMode,'INSTANT');assert.equal(preview.arrivalGroundMode,'DRIVE');assert.equal(preview.flightTransitMode,'JUMP');
  assert.throws(()=>decodePreview({...fixture,segments:undefined}));assert.throws(()=>decodePreview({...fixture,mode:'DRIVE'}));
  assert.throws(()=>decodePreview({...fixture,departureGroundMode:'DRIVE'}));assert.throws(()=>decodePreview({...fixture,flightTransitMode:'TIMED'}));
});
