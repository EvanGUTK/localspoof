import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
const motionSource=await readFile(new URL('../../android/app/src/main/assets/mobile-map/motion.mjs',import.meta.url),'utf8');
const {createMotionTracker,flightCoordinates}=await import('data:text/javascript;base64,'+Buffer.from(motionSource).toString('base64'));
const file=new URL('../../android/app/src/main/assets/mobile-map/map.mjs',import.meta.url);
test('Phone snapshot received before map style finishes is replayed once on idle',async()=>{
 const source=(await readFile(file,'utf8')).replace(/^import .*?;\n/gm,'').replace(/^ml\.setWorkerUrl.*?;\n/m,'').replace(/^ml\.setWorkerCount.*?;\n/m,'');
 let map,updates=0,fits=0,ready=0,resolveStyle,resizes=0;const frames=[];const node={clientWidth:384,clientHeight:0,style:{}};Object.defineProperty(node.style,'height',{get(){return this.value;},set(value){this.value=value;node.clientHeight=Number.parseFloat(value);}});
 const styleWait=new Promise(r=>{resolveStyle=r;});
 class Bounds{extend(){return this;}}
 class FakeMap{resize(){resizes++;}constructor(){map=this;this.loaded=false;this.sources=new Map();this.handlers=new Map();}on(event,callback){this.handlers.set(event,callback);}isStyleLoaded(){return this.loaded;}getSource(id){return this.sources.get(id);}addSource(id,spec){this.sources.set(id,{...spec,setData(data){this.data=data;updates++;}});}addLayer(){}fitBounds(){fits++;}jumpTo(){}fire(event){this.handlers.get(event)?.();}}
 const window={innerWidth:384,innerHeight:0,addEventListener(){},mobileMapReady(){ready++;},mobileMapFail(){assert.fail('Unexpected map failure');}};
 const context=vm.createContext({createMotionTracker,flightCoordinates,performance:{now:()=>0},cancelAnimationFrame(){},document:{getElementById:()=>node,documentElement:{style:{}},body:{style:{}}},ResizeObserver:class{observe(){}},requestAnimationFrame:callback=>{frames.push(callback);return frames.length;},window,ml:{Map:FakeMap,LngLatBounds:Bounds},fetch:async()=>({json:()=>styleWait}),location:{origin:'https://localspoof.invalid'}});
 const execution=new vm.Script(`(async()=>{${source}})()`).runInContext(context);
 window.updateMapState({origin:{lat:1,lon:2},destination:{lat:1.1,lon:2.1},current:{lat:1.01,lon:2.01},route:[{lat:1,lon:2},{lat:1.1,lon:2.1}],segments:[]});
 resolveStyle({version:8,sources:{},layers:[]});await execution;frames.shift()();assert.equal(resizes,0,'A zero-height prelayout container cannot resize the renderer');window.innerHeight=628;window.resizeMapViewport();window.resizeMapViewport();assert.equal(frames.length,1,'Viewport changes coalesce within one animation frame');frames.shift()();assert.equal(node.clientHeight,628,'Native window measurements repair a zero CSS viewport with explicit pixels');assert.equal(node.style.height,'628px');assert.equal(resizes,1,'The first nonzero AndroidView viewport resizes the canvas');window.resizeMapViewport();frames.shift()();assert.equal(resizes,1,'Unchanged viewport dimensions cannot cause a resize loop');
 map.fire('style.load');assert.equal(map.sources.size,0,'A loading style cannot receive native sources yet');
 map.loaded=true;map.fire('idle');
 assert.equal(map.getSource('markers').data.features.length,2,'The early native origin/destination points must appear');
 assert.equal(map.getSource('current').data.features.length,1,'The early native current point has an independent motion source');
 assert.equal(map.getSource('journey').data.features.length,1,'The early native route must appear');
 assert.equal(fits,1,'The initial native route changes the camera once');
 map.fire('idle');map.fire('idle');assert.equal(updates,0,'Idle cannot continually setData and trigger another idle');
 window.updateMapState({current:{lat:1.02,lon:2.02},route:[],segments:[]});assert.equal(updates,3,'A newer native snapshot updates geometry sources and the separate current marker once');
 map.fire('idle');assert.equal(updates,3,'A clean snapshot cannot cause an idle redraw loop');assert.ok(ready>0);
});

async function rendererFixture(){
 const source=(await readFile(file,'utf8')).replace(/^import .*?;\n/gm,'').replace(/^ml\.setWorkerUrl.*?;\n/m,'').replace(/^ml\.setWorkerCount.*?;\n/m,'');
 let map,resolveStyle;const styleWait=new Promise(resolve=>{resolveStyle=resolve;});
 const node={clientWidth:384,clientHeight:628,style:{}};
 class Bounds{extend(){return this;}}
 class FakeMap{
  constructor(){map=this;this.loaded=false;this.sources=new Map();this.layers=new Map();this.handlers=new Map();this.queryResults=[];this.updates=0;this.geometryUpdates=0;this.fits=0;this.jumps=[];}
  resize(){}on(event,callback){this.handlers.set(event,callback);}isStyleLoaded(){return this.loaded;}getSource(id){return this.sources.get(id);}
  addSource(id,spec){const owner=this;this.sources.set(id,{...spec,setData(data){this.data=data;owner.updates++;if(id==='journey'||id==='markers')owner.geometryUpdates++;}});}addLayer(layer){this.layers.set(layer.id,layer);}getLayer(id){return this.layers.get(id);}
  queryRenderedFeatures(){return this.queryResults;}fitBounds(){this.fits++;}jumpTo(value){this.jumps.push(value);}getCenter(){return {lat:41.8781,lng:-87.6298};}getZoom(){return 14;}fire(event,value){this.handlers.get(event)?.(value);}
 }
 const window={innerWidth:384,innerHeight:628,addEventListener(){},mobileMapReady(){},mobileMapFail(){assert.fail('Unexpected renderer failure');}};
 const location={origin:'https://localspoof.invalid',href:''};
 const context=vm.createContext({createMotionTracker,flightCoordinates,performance:{now:()=>0},cancelAnimationFrame(){},document:{documentElement:{style:{}},body:{style:{}},getElementById:()=>node},ResizeObserver:class{observe(){}},requestAnimationFrame:()=>1,window,ml:{Map:FakeMap,LngLatBounds:Bounds},fetch:async()=>({json:()=>styleWait}),location});
 const execution=new vm.Script(`(async()=>{${source}})()`).runInContext(context);
 return {window,location,async ready(){resolveStyle({version:8,sources:{},layers:[]});await execution;map.loaded=true;map.fire('idle');return map;}};
}

test('Nearby pins replay early results once and clearing fences stale clickable features',async()=>{
 const fixture=await rendererFixture();const point={lat:41.8781391,lon:-87.6301201,number:1,color:'#e9b76a'};
 fixture.window.updateNearbyPlaces([point]);const map=await fixture.ready();const data=map.getSource('nearby').data;
 assert.equal(data.features.length,1,'Pending category results must appear after style readiness');
 const feature=data.features[0];assert.equal(feature.properties.number,1);
 fixture.window.updateNearbyPlaces([point]);map.fire('idle');map.fire('idle');assert.equal(map.updates,0,'An unchanged result batch cannot create an idle redraw loop');
 map.queryResults=[feature];map.fire('click',{point:{x:5,y:10},lngLat:{lat:0,lng:0}});
 const selection=new URL(fixture.location.href);assert.equal(selection.pathname,'/select');assert.equal(Number(selection.searchParams.get('lat')),point.lat);assert.ok(Math.abs(Number(selection.searchParams.get('lon'))-point.lon)<1e-10,'A pin must select its exact approved point, even when its pixel covers another coordinate');
 fixture.window.updateNearbyPlaces([]);assert.equal(map.getSource('nearby').data.features.length,0,'Clear must remove every displayed nearby result');
 fixture.location.href='';map.fire('click',{point:{x:5,y:10},lngLat:{lat:1,lng:2}});assert.equal(fixture.location.href,'','A renderer feature from a cleared batch cannot stage a stale place');
});

test('Map viewport messages stay separate from point selection and avoid duplicate navigation',async()=>{
 const fixture=await rendererFixture();fixture.window.updateMapState({origin:{lat:1,lon:2},route:[],segments:[]});const map=await fixture.ready();
 const before=JSON.stringify(map.getSource('markers').data);const viewport=new URL(fixture.location.href);
 assert.equal(viewport.pathname,'/viewport');assert.equal(viewport.searchParams.get('zoom'),'14');
 fixture.location.href='';map.fire('moveend');assert.equal(fixture.location.href,'','The same viewport must not repeatedly trigger native navigation');assert.equal(JSON.stringify(map.getSource('markers').data),before,'Reporting a viewport cannot rewrite phone/origin/destination data');
});

test('Live motion only updates its marker source; Stop clears it without rebuilding roads',async()=>{
 const fixture=await rendererFixture();fixture.window.updateMapState({origin:{lat:0,lon:0},destination:{lat:0,lon:.001},route:[{lat:0,lon:0},{lat:0,lon:.001}],segments:[{kind:'DRIVE',points:[{lat:0,lon:0},{lat:0,lon:.001}]}]});const map=await fixture.ready();
 const updates=map.geometryUpdates;
 fixture.window.updateMapMotion({current:{lat:0,lon:.0001},sequence:1,ageMs:0,health:'LIVE',source:'SIMULATION',owner:'drive',speedMps:10});
 fixture.window.updateMapMotion({current:{lat:0,lon:.0002},sequence:2,ageMs:0,health:'LIVE',source:'SIMULATION',owner:'drive',speedMps:10});
 assert.equal(map.geometryUpdates,updates,'New acknowledged points cannot redraw static route and endpoint sources');
 fixture.window.updateMapMotion({sequence:0,health:'IDLE',source:'NONE'});assert.equal(map.getSource('current').data.features.length,0);
 assert.equal(map.geometryUpdates,updates,'Stop clears the marker independently from route geometry');
});

test('Map navigation return replays routes and markers without refitting a user camera',async()=>{
 const fixture=await rendererFixture();fixture.window.updateMapState({origin:{lat:1,lon:2},destination:{lat:1.1,lon:2.1},route:[{lat:1,lon:2},{lat:1.1,lon:2.1}],segments:[]});const map=await fixture.ready();
 fixture.window.updateMapMotion({current:{lat:1.01,lon:2.01},sequence:1,ageMs:0,health:'LIVE',source:'SIMULATION',owner:'drive',speedMps:10});
 const route=JSON.stringify(map.getSource('journey').data),current=JSON.stringify(map.getSource('current').data);
 fixture.window.setMapVisible(false);fixture.window.setMapVisible(true);map.fire('idle');
 assert.equal(JSON.stringify(map.getSource('journey').data),route);assert.equal(JSON.stringify(map.getSource('current').data),current);
});
test('Dragging disables camera follow through a separate native gesture message',async()=>{
 const fixture=await rendererFixture();const map=await fixture.ready();fixture.location.href='';map.fire('dragstart');assert.equal(new URL(fixture.location.href).pathname,'/gesture');assert.equal(new URL(fixture.location.href).search,'');
});

test('Recreated renderer restores RAM camera once; ordinary draft pin and Cancel retain approved line',async()=>{
 const f=await rendererFixture(),approved={origin:{lat:1,lon:2},destination:{lat:1.1,lon:2.1},route:[{lat:1,lon:2},{lat:1.1,lon:2.1}],segments:[],camera:{lat:3,lon:4,zoom:13}};
 f.window.updateMapState(approved);const map=await f.ready();assert.equal(map.fits,0,'Restored user camera wins over initial route autofit');assert.deepEqual(JSON.parse(JSON.stringify(map.jumps[0])),{center:[4,3],zoom:13});
 const line=JSON.stringify(map.getSource('journey').data);f.window.updateMapState({...approved,candidateDestination:{lat:1.2,lon:2.2},candidateSegments:[{kind:'DRIVE',points:[{lat:1,lon:2},{lat:1.2,lon:2.2}]}]});
 assert.equal(JSON.stringify(map.getSource('journey').data),line);assert.equal(map.getSource('candidate').data.features.length,1);assert.equal(map.fits,0,'Draft editing cannot refit the approved journey');
 f.window.updateMapState(approved);assert.equal(map.getSource('candidate').data.features.length,0);assert.equal(JSON.stringify(map.getSource('journey').data),line);
});
