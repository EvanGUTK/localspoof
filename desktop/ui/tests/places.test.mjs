import test from 'node:test';
import assert from 'node:assert/strict';
import {decodeNearby,nearbyPlaces,categorySpec} from '../src/places.ts';
const center={lat:41.8781,lon:-87.6298};
const feature=(name='Public hotel',lat=center.lat,lon=center.lon,key='tourism',value='hotel')=>({geometry:{type:'Point',coordinates:[lon,lat]},properties:{name,osm_key:key,osm_value:value}});
test('categories strictly accept matching named nearby principal tags only',()=>{
 assert.equal(decodeNearby({features:[feature(),feature('wrong',center.lat,center.lon,'amenity','cafe'),feature('remote',0,0),feature('control\n'),feature('string','41.87')]},center,'HOTEL').length,1);
 assert.throws(()=>categorySpec('OTHER'));assert.throws(()=>decodeNearby({features:[]},{...center,token:'secret'},'HOTEL'));
 assert.throws(()=>decodeNearby({features:Array.from({length:9},()=>feature())},center,'HOTEL'));
 assert.deepEqual(decodeNearby({features:[feature('closed',center.lat,center.lon,'shop','vacant'),feature('empty',center.lat,center.lon,'shop','')]},center,'SHOPS'),[]);
});
test('nearby validates native results and cancels a late native result',async()=>{
 let finish;globalThis.window={__TAURI__:{core:{invoke:()=>new Promise(r=>finish=r)}}};
 const abort=new AbortController();const pending=nearbyPlaces(center,'HOTEL',abort.signal);abort.abort();finish([{name:'Public hotel',...center,category:'HOTEL'}]);await assert.rejects(pending,/Cancelled/);
 window.__TAURI__.core.invoke=async()=>[{name:'remote',lat:0,lon:0,category:'HOTEL'}];assert.deepEqual(await nearbyPlaces(center,'HOTEL'),[]);delete globalThis.window;
});
test('browser request uses fixed reverse OR tags and bounded stream',async()=>{
 globalThis.window={};const old=globalThis.fetch;let called;
 globalThis.fetch=async(url,options)=>{called={url:String(url),options};return new Response(JSON.stringify({features:[feature()]}));};
 try{assert.equal((await nearbyPlaces(center,'HOTEL')).length,1);const url=new URL(called.url);assert.equal(url.origin,'https://photon.komoot.io');assert.equal(url.pathname,'/reverse');assert.deepEqual(url.searchParams.getAll('osm_tag'),['tourism:hotel','tourism:motel','tourism:hostel','tourism:guest_house']);assert.equal(called.options.credentials,'omit');assert.equal(called.options.referrerPolicy,'no-referrer');}
 finally{globalThis.fetch=old;delete globalThis.window;}
});
test('category validation rejects string centers, oversized payloads and matches principal tags across the dateline',()=>{
 assert.throws(()=>decodeNearby({features:[]},{lat:'41.8',lon:-87.6},'HOTEL'));
 const dateline={lat:0,lon:179.99};assert.equal(decodeNearby({features:[feature('Hotel across dateline',0,-179.99)]},dateline,'HOTEL').length,1);
 assert.equal(decodeNearby({features:[feature('named cafe',center.lat,center.lon,'amenity','cafe')]},center,'COFFEE').length,1);
 assert.equal(decodeNearby({features:[feature('hospital',center.lat,center.lon,'amenity','hospital')]},center,'HEALTH').length,1);
 assert.deepEqual(decodeNearby({features:[feature('invalid shop',center.lat,center.lon,'shop','x'.repeat(81)),feature('controls',center.lat,center.lon,'shop','supermarket\n')]},center,'SHOPS'),[]);
});
test('browser response stream beyond 256 KiB is rejected',async()=>{
 const {nearbyPlaces:freshNearby}=await import('../src/places.ts?bounded-stream-test');globalThis.window={};const old=globalThis.fetch;globalThis.fetch=async()=>new Response(' '.repeat(262145));try{await assert.rejects(freshNearby(center,'HOTEL'),/exceeds limit/);}finally{globalThis.fetch=old;delete globalThis.window;}
});
