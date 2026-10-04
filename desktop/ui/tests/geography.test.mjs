import test from 'node:test';
import assert from 'node:assert/strict';
import {previewJourney,routeOffline,validateRoadResponse,searchPlaces} from '../src/geography.ts';
import {distance} from '../src/model.ts';

const a={lat:0,lon:0},b={lat:0,lon:0.01},c={lat:0.01,lon:0.01},d={lat:0,lon:0.02};
const plan=(overrides={})=>({mode:'DRIVE',origin:a,destination:d,speedMps:10,waypoints:[],dwellSeconds:30,flightDurationSeconds:3600,radioPolicy:'MANUAL',...overrides});
function pack(edges,nodes=[{id:'a',...a},{id:'b',...b},{id:'c',...c},{id:'d',...d}],bounds={south:-0.1,west:-0.1,north:0.1,east:0.1}){
  return {schemaVersion:1,id:'synthetic',name:'Synthetic regression fixture',bounds,nodes,edges,places:[],polygons:[],source:'test fixture',attribution:'Synthetic test data'};
}
const edge=(from,to,overrides={})=>({from,to,modes:['WALK','DRIVE'],speedLimitMps:20,bidirectional:true,...overrides});
const wire=(points=[a,d],overrides={})=>({profile:'DRIVE',provider:'FOSSGIS OSRM',points,distanceMeters:distance(a,d),durationSeconds:500,snapOriginMeters:0,snapDestinationMeters:0,...overrides});

// Matching Android GraphRouter is observable behavior: road restrictions and
// selected speed must produce the same route and playback estimate on both UIs.
test('offline routing respects a one-way terminal and rejects the reverse journey',()=>{
  const graph=pack([edge('a','b',{bidirectional:false}),edge('b','d',{bidirectional:false})]);
  assert.deepEqual(routeOffline(graph,a,d,'DRIVE'),[a,b,d]);
  assert.throws(()=>routeOffline(graph,d,a,'DRIVE'),/No permitted offline road/);
  assert.deepEqual(routeOffline(graph,d,d,'DRIVE'),[d]);
});

test('offline snapping uses only roads permitted by the selected profile and caps snapping at 250 meters',()=>{
  const graph=pack([edge('a','d',{modes:['WALK']})]);
  assert.deepEqual(routeOffline(graph,a,d,'WALK'),[a,d]);
  assert.throws(()=>routeOffline(graph,a,d,'DRIVE'),/coverage|road/i);
  assert.deepEqual(routeOffline(graph,{lat:0.0022,lon:0},d,'WALK'),[a,d]);
  assert.throws(()=>routeOffline(graph,{lat:0.0023,lon:0},d,'WALK'),/coverage/);
});

test('offline routing rejects an endpoint outside declared coverage even when a road is nearby',()=>{
  const graph=pack([edge('a','d')],undefined,{south:-0.01,west:0,north:0.01,east:0.02});
  assert.throws(()=>routeOffline(graph,{lat:0,lon:-0.001},d,'DRIVE'),/coverage/i);
});

test('offline coverage and distance support a short crossing of the date line',async()=>{
  const west={lat:0,lon:179.999},east={lat:0,lon:-179.999};
  const graph=pack([edge('w','e')],[{id:'w',...west},{id:'e',...east}],{south:-0.1,west:179.99,north:0.1,east:-179.99});
  const preview=await previewJourney(plan({origin:west,destination:east}),{mode:'offline',pack:graph});
  assert.deepEqual(preview.points,[west,east]);assert.ok(preview.distanceM>220&&preview.distanceM<225);
  assert.equal(preview.routeSource,'OFFLINE_GEOPACK');assert.equal(preview.onlineRoute,undefined);
});

test('offline route chooses the faster permitted roads rather than the shortest slow road',async()=>{
  const graph=pack([edge('a','b',{speedLimitMps:1}),edge('b','d',{speedLimitMps:1}),edge('a','c'),edge('c','d')]);
  assert.deepEqual(routeOffline(graph,a,d,'DRIVE',10),[a,c,d]);
  const preview=await previewJourney(plan(),{mode:'offline',pack:graph});
  assert.deepEqual(preview.points,[a,c,d]);assert.ok(Math.abs(preview.durationSeconds-(distance(a,c)+distance(c,d))/10)<0.001);
});

test('offline preview playback duration respects road speed limits below selected speed',async()=>{
  const graph=pack([edge('a','d',{speedLimitMps:2})]);
  const preview=await previewJourney(plan(),{mode:'offline',pack:graph});
  assert.ok(Math.abs(preview.durationSeconds-distance(a,d)/2)<0.001);
});

test('normalized online geometry rejects malformed coordinates, profile, provider, and distant endpoints',()=>{
  assert.deepEqual(validateRoadResponse(wire(),a,d,'DRIVE').points,[a,d]);
  for(const value of [null,{},wire([]),wire([a]),wire([{lat:NaN,lon:0},d]),wire([{lat:91,lon:0},d]),wire([a,{lat:0,lon:181}]),wire([a,d],{profile:'WALK'}),wire([a,d],{provider:'arbitrary'}),wire([a,d],{distanceMeters:-1}),wire([a,d],{durationSeconds:Infinity})]){
    assert.throws(()=>validateRoadResponse(value,a,d,'DRIVE'));
  }
  assert.throws(()=>validateRoadResponse(wire([{lat:0.003,lon:0},d]),a,d,'DRIVE'),/250|endpoint access/);
  assert.throws(()=>validateRoadResponse(wire(Array.from({length:12001},()=>a)),a,d,'DRIVE'),/complex/);
});

test('online road geometry cannot teleport across a disconnected segment',()=>{
  const distant={lat:2,lon:2};
  assert.throws(()=>validateRoadResponse(wire([a,distant]),a,distant,'DRIVE'),/disconnected/);
});

test('online wire payload contains only profile, provider, and normalized points and uses selected playback speed',async()=>{
  const calls=[];globalThis.window={__TAURI__:{core:{invoke:async(command,args)=>{calls.push({command,args});return wire([a,d],{unexpected:'excluded'});}}}};
  const preview=await previewJourney(plan(),{mode:'online'});
  assert.deepEqual(calls,[{command:'online_route',args:{origin:a,destination:d,profile:'DRIVE'}}]);
  assert.deepEqual(Object.keys(preview.onlineRoute).sort(),['points','profile','provider']);
  assert.deepEqual(preview.onlineRoute,{profile:'DRIVE',provider:'FOSSGIS OSRM',points:[a,d]});
  assert.equal(preview.routeSource,'ONLINE_ROUTER');assert.equal(preview.durationSeconds,preview.distanceM/10);
  assert.deepEqual(preview.snappedOrigin,a);assert.deepEqual(preview.snappedDestination,d);
});

test('cancelled native route and place-search replies cannot deliver a preview or results',async()=>{
  let release;globalThis.window={__TAURI__:{core:{invoke:async()=>await new Promise(resolve=>{release=resolve;})}}};
  const routeController=new AbortController(),pending=previewJourney(plan(),{mode:'online',signal:routeController.signal});
  routeController.abort();release(wire());await assert.rejects(pending,{name:'AbortError'});
  const searchController=new AbortController(),search=searchPlaces('Chicago',{provider:'PHOTON',signal:searchController.signal});
  searchController.abort();release([{name:'Chicago',...a}]);await assert.rejects(search,{name:'AbortError'});
});

test('Instant, custom straight paths, and disabled searches make no provider requests',async()=>{
  let calls=0;globalThis.window={__TAURI__:{core:{invoke:async()=>{calls++;throw new Error('Unexpected provider call');}}}};
  const instant=await previewJourney(plan({mode:'INSTANT'}),{mode:'online'});
  assert.deepEqual(instant.points,[d]);assert.equal(instant.routeSource,'INSTANT_LOCATION');
  const custom=await previewJourney(plan({customPath:true}),{mode:'online'});
  assert.equal(custom.routeSource,'CUSTOM_PATH');assert.deepEqual(custom.points,[a,d]);
  assert.deepEqual(await searchPlaces('Chicago',{provider:'DISABLED'}),[]);assert.equal(calls,0);
  const controller=new AbortController();controller.abort();await assert.rejects(previewJourney(plan(),{mode:'online',signal:controller.signal}),{name:'AbortError'});
  assert.equal(calls,0);
});

test('offline archive survives map mode application, rejects cleared reads, and cannot be overwritten by a late online style',async()=>{
  const {createWorldMap}=await import('../src/world-map.ts');
  const originalFetch=globalThis.fetch,originalWindow=globalThis.window;let releaseStyle,source,map;
  const styleReply=new Promise(resolve=>{releaseStyle=resolve;});
  globalThis.fetch=async()=>await styleReply;
  class FakeMap{
    styles=[];jumps=[];
    constructor(){map=this;}
    addControl(){}on(){}isStyleLoaded(){return false;}
    setStyle(style){this.styles.push(style);}resize(){}jumpTo(value){this.jumps.push(value);}
  }
  class FakeArchive{
    constructor(value){source=value;}
    async getHeader(){await source.getBytes(0,127);return {minZoom:0,maxZoom:2,tileType:2,centerLon:0,centerLat:0,centerZoom:0,minLon:-180,maxLon:180,minLat:-85,maxLat:85,rootDirectoryLength:1,jsonMetadataLength:2};}
    async getMetadata(){return {};}
  }
  globalThis.window={maplibregl:{Map:FakeMap,AttributionControl:class{},addProtocol(){},removeProtocol(){}},pmtiles:{PMTiles:FakeArchive,SharedPromiseCache:class{},Protocol:class{add(){}},TileType:{Mvt:1,Png:2,Jpeg:3,Webp:4}}};
  try{
    const api=createWorldMap({}, {onSelect(){},onError(){}});
    await api.setOfflineArchive(new File([new Uint8Array(512)],'synthetic-world.pmtiles'));
    api.setMode('offline');
    assert.equal((await source.getBytes(127,128)).data.byteLength,128,'Style changes must not invalidate the selected archive');
    assert.equal(map.styles.at(-1).layers[0].id,'offline-world');
    releaseStyle({ok:true,json:async()=>({version:8,sources:{},layers:[{id:'online-only',type:'background'}]})});
    await new Promise(resolve=>setImmediate(resolve));
    assert.equal(map.styles.at(-1).layers[0].id,'offline-world','Late online style completion must preserve offline mode');
    await assert.rejects(source.getBytes(500,100),/range/);await assert.rejects(source.getBytes(-1,10),/range/);
    api.clear();assert.deepEqual(map.jumps.at(-1),{center:[-30,28],zoom:1.5});
    await assert.rejects(source.getBytes(0,127),{name:'AbortError'});
  }finally{globalThis.fetch=originalFetch;globalThis.window=originalWindow;}
});

test('offline endpoint snapping breaks equal-distance ties by catalog node order like Android',()=>{
  const shared={lat:0,lon:0},isolated={lat:0.01,lon:0};
  const graph=pack([edge('first','end'),edge('second','isolated')],[{id:'first',...shared},{id:'second',...shared},{id:'isolated',...isolated},{id:'end',...d}]);
  assert.deepEqual(routeOffline(graph,shared,d,'DRIVE'),[shared,d]);
});

test('online flight ground legs carry catalog airport identities and use actual access points for distance',async()=>{
  const originalFetch=globalThis.fetch,originalWindow=globalThis.window;
  const departure={id:'fixture-departure',name:'Departure fixture',lat:0,lon:0.01,type:'large_airport',scheduledService:true,closed:false},arrival={id:'fixture-arrival',name:'Arrival fixture',lat:0,lon:0.1,type:'large_airport',scheduledService:true,closed:false};
  const accessA={lat:0,lon:0.014},accessB={lat:0,lon:0.096},destination={lat:0,lon:0.11};
  const calls=[];
  globalThis.fetch=async()=>({ok:true,json:async()=>[departure,arrival]});
  globalThis.window={__TAURI__:{core:{invoke:async(command,args)=>{
    assert.deepEqual(Object.keys(args.origin).sort(),['lat','lon']);assert.deepEqual(Object.keys(args.destination).sort(),['lat','lon']);calls.push({command,args});const points=calls.length===1?[a,accessA]:[accessB,destination];
    return wire(points,{distanceMeters:distance(...points),snapOriginMeters:distance(points[0],args.origin),snapDestinationMeters:distance(points[1],args.destination)});
  }}}};
  try{
    const preview=await previewJourney(plan({mode:'FLIGHT',origin:a,destination,departureAirportId:departure.id,arrivalAirportId:arrival.id}),{mode:'online'});
    assert.equal(calls.length,2);assert.equal(calls[0].args.departureAirportId,departure.id);assert.equal(calls[1].args.arrivalAirportId,arrival.id);
    assert.deepEqual(preview.onlineFlightRoutes,{departureAirportId:departure.id,arrivalAirportId:arrival.id,originGround:{profile:'DRIVE',provider:'FOSSGIS OSRM',points:[a,accessA]},destinationGround:{profile:'DRIVE',provider:'FOSSGIS OSRM',points:[accessB,destination]}});
    assert.deepEqual(Object.keys(preview.onlineFlightRoutes.originGround).sort(),['points','profile','provider']);
    assert.ok(Math.abs(preview.distanceM-(distance(a,accessA)+distance(accessA,accessB)+distance(accessB,destination)))<0.001);
    assert.match(preview.description,/5 km/);assert.match(preview.description,/Ground access/);
  }finally{globalThis.fetch=originalFetch;globalThis.window=originalWindow;}
});

test('airport journeys route only chosen driving legs; instant and jump remain explicit placements with honest sources',async()=>{
  const originalFetch=globalThis.fetch,originalWindow=globalThis.window;
  const departure={id:'test-departure',name:'Departure',lat:0,lon:0.01,type:'large_airport',scheduledService:true,closed:false};
  const arrival={id:'test-arrival',name:'Arrival',lat:0,lon:0.1,type:'large_airport',scheduledService:true,closed:false};
  const rejected={id:'closed',name:'Closed airport',lat:0,lon:0,type:'large_airport',scheduledService:true,closed:true};
  globalThis.fetch=async()=>({ok:true,json:async()=>[rejected,departure,arrival]});
  const geography=await import('../src/geography.ts?flight-options-test');
  const calls=[];globalThis.window={__TAURI__:{core:{invoke:async(command,args)=>{calls.push(args);return wire([args.origin,args.destination],{distanceMeters:distance(args.origin,args.destination)});}}}};
  try{
    for(const departureGroundMode of ['DRIVE','INSTANT'])for(const arrivalGroundMode of ['DRIVE','INSTANT'])for(const flightTransitMode of ['TIMED','JUMP']){
      calls.length=0;
      const result=await geography.previewJourney(plan({mode:'FLIGHT',destination:{lat:0,lon:0.11},departureGroundMode,arrivalGroundMode,flightTransitMode}),{mode:'online'});
      assert.equal(calls.length,Number(departureGroundMode==='DRIVE')+Number(arrivalGroundMode==='DRIVE'));
      assert.equal(result.routeSource,'FLIGHT_JOURNEY');
      assert.deepEqual(result.segments.map(s=>s.kind),[departureGroundMode,'FLIGHT',arrivalGroundMode]);
      assert.deepEqual(result.segments.map(s=>s.role),['DEPARTURE','AIR','ARRIVAL']);
      assert.equal(result.segments[1].durationMs,flightTransitMode==='JUMP'?0:3600000);
      assert.equal(result.onlineFlightRoutes.departureAirportId,departure.id);assert.equal(result.onlineFlightRoutes.arrivalAirportId,arrival.id);
      assert.equal(!!result.onlineFlightRoutes.originGround,departureGroundMode==='DRIVE');assert.equal(!!result.onlineFlightRoutes.destinationGround,arrivalGroundMode==='DRIVE');
      for(const segment of [result.segments[0],result.segments[2]])assert.equal(segment.routeSource,segment.kind==='DRIVE'?'ONLINE_ROUTER':'INSTANT_LOCATION');
      assert.equal(result.segments[1].routeSource,'FLIGHT_JOURNEY');
    }
    calls.length=0;
    const offline=await geography.previewJourney(plan({mode:'FLIGHT',destination:arrival,departureGroundMode:'INSTANT',arrivalGroundMode:'INSTANT',flightTransitMode:'JUMP'}),{mode:'offline'});
    assert.equal(calls.length,0);assert.equal(offline.routeSource,'FLIGHT_JOURNEY');assert.equal(offline.onlineFlightRoutes,undefined);
    assert.deepEqual((await geography.loadAirports()).map(a=>a.id),[departure.id,arrival.id]);
    await assert.rejects(geography.previewJourney(plan({mode:'FLIGHT',departureGroundMode:'WALK'}),{mode:'online'}),/ground|leg/i);
  }finally{globalThis.fetch=originalFetch;globalThis.window=originalWindow;}
});

test('world map renders road and air layers separately and represents instant legs as endpoint placements',async()=>{
  const {createWorldMap}=await import('../src/world-map.ts');const originalFetch=globalThis.fetch,originalWindow=globalThis.window,originalDocument=globalThis.document;let map,marker;
  class FakeMap {
    sources=new Map();layers=[];sourceUpdates=0;
    constructor(){map=this;}addControl(){}on(){}isStyleLoaded(){return true;}setStyle(){}resize(){}jumpTo(value){this.camera=value;}
    getSource(id){return this.sources.get(id);}
    addSource(id,value){this.sources.set(id,{data:value.data,setData(data){map.sourceUpdates++;this.data=data;}});}
    addLayer(layer){this.layers.push(layer);}
  }
  globalThis.fetch=async()=>({ok:true,json:async()=>({version:8,sources:{},layers:[]})});
  globalThis.document={createElement:()=>({className:'',setAttribute(){}})};
  globalThis.window={maplibregl:{Map:FakeMap,Marker:class{constructor(){marker=this;}setLngLat(point){this.point=point;return this;}addTo(){return this;}remove(){}},AttributionControl:class{},addProtocol(){}}};
  try{
    const api=createWorldMap({}, {onSelect(){},onError(){}});
    api.setState({current:a,route:[a,b,c,d],segments:[{role:'DEPARTURE',kind:'INSTANT',points:[a,b],durationMs:0,routeSource:'INSTANT_LOCATION'},{role:'AIR',kind:'FLIGHT',points:[b,c],durationMs:1000,routeSource:'FLIGHT_JOURNEY'},{role:'ARRIVAL',kind:'DRIVE',points:[c,d],durationMs:1000,routeSource:'ONLINE_ROUTER'}]});
    assert.equal(map.sources.get('journey').data.type,'FeatureCollection');
    const road=map.layers.find(l=>l.id==='journey-route'),air=map.layers.find(l=>l.id==='journey-air');
    assert.deepEqual(road.filter,['==',['get','kind'],'DRIVE']);assert.deepEqual(air.filter,['==',['get','kind'],'FLIGHT']);assert.ok(air.paint['line-dasharray']);
    assert.ok(map.sources.get('journey-markers').data.features.some(f=>f.properties.label==='Instant placement'));
    assert.deepEqual(marker.point,[a.lon,a.lat]);const updates=map.sourceUpdates;api.setCurrent(b);assert.deepEqual(marker.point,[b.lon,b.lat]);assert.equal(map.sourceUpdates,updates,'Moving the phone marker must not rebuild route geometry');api.follow(b);assert.deepEqual(map.camera,{center:[b.lon,b.lat]},'Follow preserves the chosen zoom');
  }finally{globalThis.fetch=originalFetch;globalThis.window=originalWindow;globalThis.document=originalDocument;}
});

test('automatic and explicit airport choices exclude regional medium airports near the user',async()=>{
 const previous=globalThis.fetch;const medium={id:'MKL',name:'Jackson regional',lat:35.6,lon:-88.9,type:'medium_airport',scheduledService:true,closed:false},major={id:'MEM',name:'Memphis International',lat:35.0,lon:-89.9,type:'large_airport',scheduledService:true,closed:false};
 globalThis.fetch=async()=>({ok:true,json:async()=>[medium,major,{...major,id:'closed',closed:true}]});try{const g=await import('../src/geography.ts?major-airports-only');const catalog=await g.loadAirports();assert.deepEqual(catalog.map(a=>a.id),['MEM']);assert.equal(g.nearestAirport(catalog,medium).id,'MEM');assert.equal(g.nearestAirport(catalog,medium,'MKL'),undefined);}finally{globalThis.fetch=previous;}
});
test('custom stop road legs preserve ordered indices and durations and reject fabricated connecting roads',async()=>{
 const previous=globalThis.window;const middle={lat:0,lon:0.005};let disconnected=false;globalThis.window={__TAURI__:{core:{invoke:async(_command,args)=>wire([disconnected&&args.origin.lon===middle.lon?{...args.origin,lon:middle.lon+0.001}:args.origin,args.destination],{distanceMeters:distance(args.origin,args.destination)})}}};try{const stops=[{point:middle,dwellSeconds:20,label:'Coffee'}],preview=await previewJourney(plan({stops}),{mode:'online'});assert.deepEqual(preview.points,[a,middle,d]);assert.deepEqual(preview.stopPointIndices,[1]);assert.ok(Math.abs(preview.durationSeconds-distance(a,d)/10-20)<0.001);disconnected=true;await assert.rejects(previewJourney(plan({stops}),{mode:'online'}),/do not meet/);}finally{globalThis.window=previous;}
});

test('mapped road limits remain aligned with geometry and cap ETA only when enabled',async()=>{
 const previous=globalThis.window;globalThis.window={__TAURI__:{core:{invoke:async()=>wire([a,b,d],{edgeSpeedLimits:[5,null]})}}};
 try{
  const preview=await previewJourney(plan(),{mode:'online'});assert.deepEqual(preview.onlineRoute.edgeSpeedLimits,[5,null]);assert.ok(Math.abs(preview.durationSeconds-(distance(a,b)/5+distance(b,d)/10))<0.001);
  const unrestricted=await previewJourney(plan({followRoadLimits:false}),{mode:'online'});assert.ok(Math.abs(unrestricted.durationSeconds-distance(a,d)/10)<0.001);
  const variable=await previewJourney(plan({drivingSpeedMode:'VARIABLE',driveMinMps:20,driveMaxMps:40,followRoadLimits:false}),{mode:'online'});assert.ok(Math.abs(variable.durationSeconds-distance(a,d)/30)<0.001);
  for(const edgeSpeedLimits of [[5],[5,NaN],[5,101],[5,'25']])assert.throws(()=>validateRoadResponse(wire([a,b,d],{edgeSpeedLimits}),a,d,'DRIVE'),/mapped road limits/);
 }finally{globalThis.window=previous;}
});
test('mapped limits on multiple stop legs preserve segment alignment and leave small connectors unknown',async()=>{
 const previous=globalThis.window,middle={lat:0,lon:0.005};let leg=0;globalThis.window={__TAURI__:{core:{invoke:async(_command,args)=>{leg++;const first=leg===1?args.origin:{...args.origin,lon:args.origin.lon+0.000002};return wire([first,args.destination],{edgeSpeedLimits:[leg===1?10:20],distanceMeters:distance(first,args.destination)});}}}};
 try{const preview=await previewJourney(plan({stops:[{point:middle,dwellSeconds:10}]}),{mode:'online'});assert.equal(preview.onlineRoute.points.length,4);assert.deepEqual(preview.onlineRoute.edgeSpeedLimits,[10,null,20]);assert.deepEqual(preview.stopPointIndices,[1]);}finally{globalThis.window=previous;}
});

test('OSRM HTTP400 NoSegment and NoRoute are endpoint failures, while other HTTP errors and malformed bodies remain unavailable',async()=>{
 const {routingErrorCode}=await import('../src/geography.ts');
 for(const code of ['NoSegment','NoRoute']){const body=JSON.stringify({code,message:'private provider details'});assert.equal(routingErrorCode(400,body),'NO_ROUTE');for(const status of [404,429,500])assert.equal(routingErrorCode(status,body),'ROUTING_UNAVAILABLE');}
 for(const body of ['<html>gateway error</html>','{"code":"InvalidQuery","message":"private"}','{"code":null}','[]','{"code":"NoSegment"}'+' '.repeat(65536)])assert.equal(routingErrorCode(400,body),'ROUTING_UNAVAILABLE');
});
test('native endpoint-too-far error maps the actual native code to static road guidance',async()=>{
 const previous=globalThis.window;globalThis.window={__TAURI__:{core:{invoke:async()=>{throw 'ROUTE_ENDPOINT_TOO_FAR';}}}};try{await assert.rejects(previewJourney(plan(),{mode:'online'}),/within 250 meters/);}finally{globalThis.window=previous;}
});
