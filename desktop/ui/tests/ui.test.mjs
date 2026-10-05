import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

class Element {
  value='';textContent='';disabled=false;hidden=false;checked=false;children=[];listeners=new Map();dataset={};attributes=new Map();
  classList={toggle:()=>{}};style={};
  addEventListener(type,callback){this.listeners.set(type,callback);}
  querySelector(){return this.usbOption;}
  setAttribute(key,value){this.attributes.set(key,value);}
  replaceChildren(...children){this.children=children;}
  append(child){this.children.push(child);if(!this.value&&child.value)this.value=child.value;}
  showModal(){this.open=true;}
  close(){this.open=false;this.dispatch('close');}
  focus(){globalThis.document.activeElement=this;}
  getBoundingClientRect(){return {left:0,top:0,width:1000,height:600};}
  setPointerCapture(){}
  getContext(){return new Proxy({}, {get:()=>()=>{}});}
  dispatch(type,event={}){this.listeners.get(type)?.({preventDefault(){},...event});}
  click(){this.dispatch('click');}
}
async function environment(native){
  const html=await readFile(new URL('../index.html',import.meta.url),'utf8');
  const elements=new Map();
  for(const match of html.matchAll(/<([a-z]+)[^>]*\bid="([^"]+)"[^>]*>/g)){
    const element=new Element(),tag=match[0];element.value=tag.match(/\bvalue="([^"]*)"/)?.[1]??'';element.hidden=/\bhidden(?:\s|>)/.test(tag);element.disabled=/\bdisabled(?:\s|>)/.test(tag);elements.set(match[2],element);
  }
  for(const [id,value] of Object.entries({originLat:'41.8781',originLon:'-87.6298',destinationLat:'41.8838',destinationLon:'-87.6324'}))elements.get(id).value=value;
  elements.get('searchProvider').value='PHOTON';elements.get('mapMode').value='online';elements.get('radioPolicy').value='MANUAL';elements.get('radioPolicy').usbOption=new Element();
  const categories=['HOTEL','FOOD','COFFEE','SHOPS','FUEL','HEALTH','ATTRACTIONS'].map(category=>{const e=new Element();e.dataset.category=category;return e;});
  const modes=['INSTANT','WALK','DRIVE','FLIGHT'].map(mode=>{const e=new Element();e.dataset.mode=mode;return e;});
  const intervals=new Map();let timerId=0,clock=0;globalThis.performance={now:()=>clock};
  globalThis.document={getElementById:id=>elements.get(id),querySelectorAll:selector=>selector==='[data-category]'?categories:modes,createElement:()=>new Element()};
  globalThis.window={__TAURI__:native?{core:{invoke:native}}:undefined,devicePixelRatio:1,addEventListener(){},setInterval(fn,delay){const tick=()=>{clock+=4000;fn();};tick.delay=delay;tick.advance=ms=>{clock+=ms;fn();};intervals.set(++timerId,tick);return timerId;},clearInterval(id){intervals.delete(id);}};
  return {elements,modes,intervals,categories,advanceTime(ms){clock+=ms;}};
}
const settle=()=>new Promise(resolve=>setImmediate(resolve));
const pendingApproval=()=>({ok:true,payload:{pairingId:'a'.repeat(32),comparisonCode:'012345',pending:true}});

test('browser preview cannot start spoofing and supports mode/coordinate controls',async()=>{
  const {elements,modes,intervals}=await environment();await import('../src/app.ts?preview-test');
  assert.equal(elements.get('start').disabled,true);assert.equal(elements.get('prepare').disabled,false);assert.equal(elements.get('scanDevices').disabled,true);
  assert.match(elements.get('status').textContent,/Preview journeys/);assert.equal(intervals.size,0);
  assert.ok(elements.get('importPack'),'Geographic import must expose a native keyboard-focusable button');let pickerOpened=false;elements.get('packFile').addEventListener('click',()=>{pickerOpened=true;});elements.get('importPack').dispatch('click');assert.equal(pickerOpened,true);
  elements.get('setupToggle').dispatch('click');elements.get('setup').close();assert.equal(document.activeElement,elements.get('setupToggle'),'Closing connection setup restores its trigger focus');
  modes[3].dispatch('click');assert.equal(elements.get('flightSettings').hidden,false);assert.equal(elements.get('customPathLabel').hidden,true);
  assert.equal(elements.get('boardingHold').hidden,false);
  modes[1].dispatch('click');assert.equal(elements.get('customPathLabel').hidden,false);assert.equal(elements.get('speed').value,'3.1');
  assert.equal(elements.get('boardingHold').hidden,true);
  elements.get('chooseDestination').dispatch('click');elements.get('offlineMap').dispatch('pointerdown',{clientX:500,clientY:300,pointerId:1});elements.get('offlineMap').dispatch('pointerup',{clientX:500,clientY:300});
  assert.equal(elements.get('destinationLat').value,'28.000000');assert.equal(elements.get('destinationLon').value,'-30.000000');
});

test('native USB pairing, prepared plan, playback and Stop erase activity',async()=>{
  const calls=[];let state='IDLE',revision=0,deferPrepare=false,releasePrepare;
  const native=async(command,args={})=>{
    calls.push({command,args});
    if(command==='list_devices')return [{serial:'synthetic-usb',state:'device',model:'Synthetic phone'}];
    if(command==='connect_phone')return {connected:true,usbAirplaneAvailable:true};
    if(command==='pair_phone')return pendingApproval();
    if(command==='confirm_pair')return {ok:true,payload:{paired:true,usbAirplaneAvailable:true}};
    if(command==='request_phone'){
      const {type}=args.request;
      if(type==='Airports')return {ok:true,revision,state,payload:{airports:[{id:'test-airport',name:'Synthetic airport'}]}};
      if(type==='BeginRemainingEdit')return {ok:true,revision,state,payload:{editId:'synthetic-edit',confirmedOrigin:{lat:1,lon:1},choices:{mode:'INSTANT',destination:{lat:2,lon:2},speedMps:1.4,radioPolicy:'MANUAL'}}};
      if(type==='PreparePlan'||type==='PrepareRemainingPlan'){const response={ok:true,revision,state,payload:{preparedId:'synthetic-plan',preview:{geometry:[{lat:1,lon:1},{lat:2,lon:2}],distanceMeters:1000,durationMs:100000,originAirport:null,destinationAirport:null,geometrySimplified:false}}};if(deferPrepare)return await new Promise(resolve=>{releasePrepare=()=>resolve(response);});return response;}
      if(type==='CommitPlan')state='READY';if(type==='Start')state='ACTIVE';if(type==='Stop'){state='IDLE';revision++;}
      return {ok:true,state,revision,payload:type==='Stop'?{message:'Restore airplane mode manually on your phone.'}:{}};
    }
    return {};
  };
  const {elements,intervals}=await environment(native);await import('../src/app.ts?native-test');
  assert.equal(elements.get('adbPath').value,'');elements.get('scanDevices').dispatch('click');await settle();assert.equal(calls.length,1);assert.equal(calls[0].command,'discover_local_tools');assert.match(elements.get('status').textContent,/absolute path/);
  elements.get('adbPath').value='/synthetic/platform-tools/adb';
  elements.get('scanDevices').dispatch('click');await settle();assert.equal(elements.get('devices').value,'synthetic-usb');
  elements.get('connect').dispatch('click');await settle();assert.equal(intervals.size,1);
  elements.get('pair').dispatch('click');await settle();assert.equal(elements.get('pairComparison').textContent,'012 345');assert.equal(intervals.size,1);
  [...intervals.values()][0]();await settle();assert.equal(elements.get('prepare').disabled,false);assert.equal(elements.get('radioPolicy').usbOption.disabled,false);
  elements.get('prepare').dispatch('click');await settle();assert.equal(elements.get('start').disabled,false);
  elements.get('start').dispatch('click');await settle();assert.equal(elements.get('sessionState').textContent,'Simulation active');
  elements.get('destinationLat').value='';elements.get('prepare').dispatch('click');await settle();assert.equal(elements.get('start').disabled,true);elements.get('destinationLat').value='2';
  deferPrepare=true;elements.get('prepare').dispatch('click');await settle();assert.equal(elements.get('stop').disabled,false);
  elements.get('stop').dispatch('click');await settle();assert.equal(elements.get('originLat').value,'');assert.equal(elements.get('destinationLon').value,'');assert.match(elements.get('status').textContent,/Restore airplane/);assert.equal(intervals.size,0);assert.equal(elements.get('prepare').disabled,false);assert.equal(elements.get('sessionState').textContent,'Simulation stopped');
  releasePrepare();await settle();assert.equal(elements.get('start').disabled,true);assert.match(elements.get('status').textContent,/Restore airplane/);
  assert.ok(calls.some(call=>call.command==='request_phone'&&call.args.request.type==='CommitPlan'));
  elements.get('disconnect').dispatch('click');await settle();assert.equal(intervals.size,0);assert.equal(elements.get('pairComparison').textContent,'');
});

for (const stopSucceeds of [true, false]) test(`a deferred snapshot cannot revive activity after ${stopSucceeds ? 'confirmed' : 'rejected'} Stop`,async()=>{
  let state='IDLE',deferred=false,releaseSnapshot;
  const native=async(command,args={})=>{
    if(command==='list_devices')return [{serial:'synthetic-usb',state:'device',model:'Synthetic phone'}];
    if(command==='connect_phone')return {connected:true,usbAirplaneAvailable:false};
    if(command==='pair_phone')return pendingApproval();
    if(command==='confirm_pair')return {ok:true,payload:{paired:true,usbAirplaneAvailable:false}};
    if(command==='request_phone'){
      const type=args.request.type;
      if(type==='Snapshot'&&deferred)return await new Promise(resolve=>{releaseSnapshot=()=>resolve({ok:true,state:'ACTIVE',revision:0,point:{lat:1,lon:1},payload:{}});});
      if(type==='PreparePlan')return {ok:true,revision:0,state,payload:{preparedId:'test',preview:{geometry:[{lat:1,lon:1}],distanceMeters:0,durationMs:0}}};
      if(type==='Start')state='ACTIVE';
      if(type==='Stop'){if(!stopSucceeds)return {ok:false,error:'TRANSIENT',revision:0,state,payload:{}};state='IDLE';return {ok:true,revision:1,state,payload:{message:'Restore airplane mode manually on your phone.'}};}
      return {ok:true,revision:0,state,payload:type==='Airports'?{airports:[]}:{}};
    }
    return {};
  };
  const {elements,intervals}=await environment(native);await import(`../src/app.ts?deferred-poll-test-${stopSucceeds}`);
  elements.get('adbPath').value='/synthetic/platform-tools/adb';elements.get('apkPath').value='/synthetic/app.apk';
  elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();
  elements.get('pair').dispatch('click');await settle();
  [...intervals.values()][0]();await settle();elements.get('prepare').dispatch('click');await settle();elements.get('start').dispatch('click');await settle();
  deferred=true;[...intervals.values()][0]();await settle();assert.equal(typeof releaseSnapshot,'function');
  elements.get('stop').dispatch('click');await settle();
  if(!stopSucceeds){
    const failure=elements.get('status').textContent;
    assert.match(failure,/rejected/);assert.equal(elements.get('disconnect').disabled,false,'A rejected Stop must release canceled work so Disconnect remains available');
    releaseSnapshot();await settle();assert.equal(elements.get('status').textContent,failure);
    elements.get('disconnect').dispatch('click');await settle();assert.equal(intervals.size,0);assert.equal(elements.get('devices').value,'');assert.equal(elements.get('adbPath').value,'');
    return;
  }
  assert.match(elements.get('status').textContent,/Restore airplane/);
  releaseSnapshot();await settle();
  assert.equal(elements.get('sessionState').textContent,'Simulation stopped');assert.match(elements.get('status').textContent,/Restore airplane/);assert.equal(elements.get('originLat').value,'');
  assert.equal(elements.get('devices').value,'');assert.equal(elements.get('devices').children.length,0);assert.equal(elements.get('adbPath').value,'');assert.equal(elements.get('apkPath').value,'');
  assert.equal(elements.get('start').disabled,true);assert.equal(intervals.size,0);assert.equal(elements.get('scanDevices').disabled,false);
});


test('worldwide preview defaults online, offers offline, and Clear removes entered activity',async()=>{
  const {elements}=await environment();await import('../src/app.ts?worldwide-clear-test');
  assert.equal(elements.get('mapMode').value,'online');assert.equal(elements.get('placeSearch').disabled,false);
  assert.match(elements.get('providerDisclosure').textContent,/provider/i);assert.equal(elements.get('start').disabled,true);
  elements.get('originLat').value='51.5';elements.get('placeSearch').value='London';
  elements.get('clearActivity').dispatch('click');await settle();
  assert.equal(elements.get('originLat').value,'');assert.equal(elements.get('placeSearch').value,'');
  assert.equal(elements.get('start').disabled,true);assert.match(elements.get('status').textContent,/cleared/i);
  elements.get('mapMode').value='offline';elements.get('mapMode').dispatch('change');
  assert.equal(elements.get('placeSearch').disabled,true);assert.equal(elements.get('map').hidden,false,'Offline world map remains available without a GeoPack');
  assert.match(elements.get('coverage').textContent,/import/i);
});

test('desktop Instant preview works without a phone and never arms playback',async()=>{
  const {elements}=await environment();await import('../src/app.ts?instant-preview-test');
  elements.get('prepare').dispatch('click');await settle();
  assert.match(elements.get('status').textContent,/Desktop preview ready/);
  assert.match(elements.get('routeSummary').textContent,/mi/);
  assert.equal(elements.get('start').disabled,true);
  elements.get('clearActivity').dispatch('click');await settle();
  assert.equal(elements.get('originLat').value,'');assert.equal(elements.get('destinationLon').value,'');
});


test('Clear discards a late online route and provider Off prevents place requests',async()=>{
  let releaseRoute,routeCalls=0,searchCalls=0;
  const native=async(command)=>{
    if(command==='online_route'){routeCalls++;return await new Promise(resolve=>{releaseRoute=()=>resolve({profile:'DRIVE',provider:'FOSSGIS OSRM',points:[{lat:41.8781,lon:-87.6298},{lat:41.8838,lon:-87.6324}],distanceMeters:1000,durationSeconds:80,snapOriginMeters:0,snapDestinationMeters:0});});}
    if(command==='search_places'){searchCalls++;return [];}
    return {};
  };
  const {elements,modes}=await environment(native);await import('../src/app.ts?online-clear-test');
  modes[2].dispatch('click');elements.get('prepare').dispatch('click');await settle();
  assert.equal(routeCalls,1);assert.equal(typeof releaseRoute,'function');
  elements.get('clearActivity').dispatch('click');await settle();
  const clearStatus=elements.get('status').textContent;assert.match(clearStatus,/cleared/i);
  releaseRoute();await settle();assert.equal(elements.get('status').textContent,clearStatus);
  assert.equal(elements.get('originLat').value,'');assert.equal(elements.get('start').disabled,true);
  elements.get('searchProvider').value='DISABLED';elements.get('searchProvider').dispatch('change');
  elements.get('placeSearch').value='Chicago';elements.get('searchPlaces').dispatch('click');await settle();
  assert.equal(searchCalls,0);assert.equal(elements.get('placeSearch').disabled,true);
});


test('paired online planning keeps the short controller lease alive and forwards vetted road geometry',async()=>{
  let releaseRoute;const calls=[];
  const native=async(command,args={})=>{
    calls.push({command,args});
    if(command==='list_devices')return [{serial:'synthetic-usb',state:'device'}];
    if(command==='connect_phone')return {connected:true,usbAirplaneAvailable:false};
    if(command==='pair_phone')return pendingApproval();if(command==='confirm_pair')return {ok:true,payload:{paired:true}};
    if(command==='online_route')return await new Promise(resolve=>{releaseRoute=()=>resolve({profile:'DRIVE',provider:'FOSSGIS OSRM',points:[{lat:41.8781,lon:-87.6298},{lat:41.8838,lon:-87.6324}],distanceMeters:1000,durationSeconds:80,snapOriginMeters:0,snapDestinationMeters:0});});
    if(command==='request_phone')return {ok:true,state:'IDLE',revision:0,payload:args.request.type==='PreparePlan'?{preparedId:'vetted',preview:{geometry:[{lat:41.8781,lon:-87.6298},{lat:41.8838,lon:-87.6324}],distanceMeters:1000,durationMs:80000}}:args.request.type==='Airports'?{airports:[]}: {}};
    return {};
  };
  const {elements,modes,intervals}=await environment(native);await import('../src/app.ts?paired-online-heartbeat-test');
  elements.get('adbPath').value='/synthetic/platform-tools/adb';elements.get('scanDevices').dispatch('click');await settle();
  elements.get('connect').dispatch('click');await settle();elements.get('pair').dispatch('click');await settle();
  [...intervals.values()][0]();await settle();modes[2].dispatch('click');elements.get('prepare').dispatch('click');await settle();
  assert.equal(typeof releaseRoute,'function');const prior=calls.filter(c=>c.command==='request_phone'&&c.args.request.type==='Heartbeat').length;
  [...intervals.values()][0]();await settle();assert.equal(calls.filter(c=>c.command==='request_phone'&&c.args.request.type==='Heartbeat').length,prior+1);
  releaseRoute();await settle();const prepared=calls.find(c=>c.command==='request_phone'&&c.args.request.type==='PreparePlan');
  assert.equal(prepared.args.request.payload.routeSource,'ONLINE_ROUTER');assert.equal(prepared.args.request.payload.onlineRoute.provider,'FOSSGIS OSRM');
  assert.equal(elements.get('start').disabled,false);
});

test('mph defaults convert to engine meters per second, unit changes preserve a prepared plan, and speed edits invalidate it',async()=>{
  const payloads=[];
  const native=async(command,args={})=>{
    if(command==='list_devices')return [{serial:'synthetic-usb',state:'device'}];
    if(command==='connect_phone')return {connected:true,usbAirplaneAvailable:false};
    if(command==='pair_phone')return pendingApproval();if(command==='confirm_pair')return {ok:true,payload:{paired:true}};
    if(command==='request_phone'){
      const type=args.request.type;if(type==='PreparePlan'){payloads.push(args.request.payload);return {ok:true,state:'READY',revision:0,payload:{preparedId:'speed-plan',preview:{geometry:[{lat:1,lon:1},{lat:2,lon:2}],distanceMeters:1609.344,durationMs:60000}}};}
      return {ok:true,state:'IDLE',revision:0,payload:type==='Airports'?{airports:[]}: {}};
    }
    return {};
  };
  const {elements,modes,intervals}=await environment(native);await import('../src/app.ts?speed-unit-ui-test');
  assert.equal(elements.get('speedUnit').value,'MPH');assert.equal(elements.get('distanceUnit').value,'MI');
  elements.get('adbPath').value='/synthetic/platform-tools/adb';elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();
  elements.get('pair').dispatch('click');await settle();[...intervals.values()][0]();await settle();
  modes[2].dispatch('click');elements.get('customPath').checked=true;elements.get('customPath').dispatch('input');
  elements.get('speed').value='60';elements.get('speed').dispatch('input');elements.get('prepare').dispatch('click');await settle();
  assert.equal(payloads.at(-1).speedMps,26.8224);assert.equal(elements.get('start').disabled,false);assert.match(elements.get('routeSummary').textContent,/1\.0 mi/);
  for(let i=0;i<10;i++)for(const unit of ['KMH','MPS','MPH']){elements.get('speedUnit').value=unit;elements.get('speedUnit').dispatch('change');}
  assert.equal(elements.get('start').disabled,false,'Display-only changes preserve the prepared phone plan');
  elements.get('prepare').dispatch('click');await settle();assert.equal(payloads.at(-1).speedMps,26.8224);
  elements.get('distanceUnit').value='KM';elements.get('distanceUnit').dispatch('change');assert.match(elements.get('routeSummary').textContent,/1\.6 km/);
  elements.get('speed').value='65';elements.get('speed').dispatch('input');assert.equal(elements.get('start').disabled,true);
  modes[1].dispatch('click');elements.get('speed').value='7';elements.get('speed').dispatch('input');elements.get('prepare').dispatch('click');await settle();assert.match(elements.get('status').textContent,/mph/);elements.get('speedUnit').value='KMH';elements.get('speedUnit').dispatch('change');assert.equal(elements.get('speedUnit').value,'MPH');assert.equal(elements.get('start').disabled,true,'Invalid speed plus unit change cannot revive a prepared plan');
});

test('USB modal exposes progress and actionable scan errors instead of hiding them behind the dialog',async()=>{
  let fail=true,releaseScan;
  const native=async(command)=>{if(command==='list_devices'){if(fail)throw 'ADB_SERVER_UNAVAILABLE';return await new Promise(resolve=>{releaseScan=resolve;});}return {};};
  const {elements}=await environment(native);await import('../src/app.ts?usb-modal-error-test');
  elements.get('setupToggle').dispatch('click');elements.get('adbPath').value='/synthetic/platform-tools/adb';
  elements.get('scanDevices').dispatch('click');await settle();assert.match(elements.get('setupStatus').textContent,/local server/);assert.equal(elements.get('setup').open,true);
  fail=false;elements.get('scanDevices').dispatch('click');await settle();assert.match(elements.get('setupStatus').textContent,/Scanning/);assert.equal(elements.get('scanDevices').disabled,true);assert.equal(elements.get('setup').attributes.get('aria-busy'),'true');
  releaseScan([{serial:'unauthorized-usb',state:'unauthorized'},{serial:'offline-usb',state:'offline'}]);await settle();
  assert.match(elements.get('deviceStatus').textContent,/approve/i);assert.match(elements.get('deviceStatus').textContent,/offline/i);assert.equal(elements.get('connect').disabled,true);
  assert.equal(elements.get('setup').attributes.get('aria-busy'),'false');
});

test('local tool discovery fills empty paths without installing or overwriting a manual ADB path',async()=>{
  const calls=[];
  const native=async(command,args)=>{calls.push({command,args});return {adbPath:'C:\\Android\\platform-tools\\adb.exe',apkPath:'C:\\LocalSpoof\\companion\\localspoof.apk'};};
  const {elements}=await environment(native);await import('../src/app.ts?tool-discovery-ui-test');
  elements.get('findTools').dispatch('click');await settle();assert.equal(elements.get('adbPath').value,'C:\\Android\\platform-tools\\adb.exe');assert.match(elements.get('apkPath').value,/localspoof\.apk/);
  elements.get('adbPath').value='D:\\MySDK\\adb.exe';elements.get('findTools').dispatch('click');await settle();assert.equal(elements.get('adbPath').value,'D:\\MySDK\\adb.exe');
  assert.deepEqual(calls.map(c=>c.command),['discover_local_tools','discover_local_tools']);
  assert.match(elements.get('setupStatus').textContent,/found|ready/i);
});

test('session settings presets apply by mode and Reset defaults restores mph without writing storage',async()=>{
  const {elements,modes}=await environment();await import('../src/app.ts?session-settings-ui-test');
  elements.get('settingsToggle').dispatch('click');assert.equal(elements.get('settingsDialog').open,true);
  elements.get('defaultWalkSpeed').value='4';elements.get('defaultWalkSpeed').dispatch('input');modes[1].dispatch('click');assert.equal(elements.get('speed').value,'4');
  elements.get('autoFitRoute').checked=false;elements.get('autoFitRoute').dispatch('change');
  elements.get('speedUnit').value='KMH';elements.get('speedUnit').dispatch('change');elements.get('resetSettings').dispatch('click');
  assert.equal(elements.get('speedUnit').value,'MPH');assert.equal(elements.get('autoFitRoute').checked,true);
  assert.match(elements.get('settingsStatus').textContent,/restored/i);
  elements.get('settingsDialog').close();assert.equal(document.activeElement,elements.get('settingsToggle'));
});

test('Scan USB finds ADB and the companion automatically without PowerShell or a separate discovery step',async()=>{
  const commands=[];const native=async(command)=>{commands.push(command);if(command==='discover_local_tools')return {adbPath:'C:\\SDK\\platform-tools\\adb.exe',apkPath:'C:\\LocalSpoof\\companion\\localspoof.apk'};if(command==='list_devices')return [{serial:'synthetic-usb',state:'device'}];return {};};
  const {elements}=await environment(native);await import('../src/app.ts?auto-scan-ui-test');
  assert.equal(elements.get('adbPath').value,'');elements.get('scanDevices').dispatch('click');await settle();
  assert.deepEqual(commands,['discover_local_tools','list_devices']);assert.equal(elements.get('devices').value,'synthetic-usb');assert.equal(elements.get('connect').disabled,false);
});

test('APK-only discovery clearly identifies that ADB still needs SDK Platform-Tools',async()=>{
  const {elements}=await environment(async()=>({adbPath:null,apkPath:'C:\\LocalSpoof\\companion\\localspoof.apk'}));await import('../src/app.ts?apk-only-discovery-test');
  elements.get('findTools').dispatch('click');await settle();assert.match(elements.get('setupStatus').textContent,/ADB is missing/);assert.match(elements.get('setupStatus').textContent,/Platform-Tools/);
});

test('Clear erases airport and journey selections while retaining window preferences',async()=>{
  const {elements}=await environment();await import('../src/app.ts?clear-preferences-test');
  elements.get('distanceUnit').value='KM';elements.get('distanceUnit').dispatch('change');elements.get('defaultDriveSpeed').value='45';elements.get('defaultDriveSpeed').dispatch('input');
  elements.get('departureAirport').value='synthetic-departure';elements.get('arrivalAirport').value='synthetic-arrival';elements.get('dwell').value='900';elements.get('flightDuration').value='8000';elements.get('customPath').checked=true;
  elements.get('clearActivity').dispatch('click');await settle();
  assert.equal(elements.get('departureAirport').value,'');assert.equal(elements.get('arrivalAirport').value,'');assert.equal(elements.get('dwell').value,'30');assert.equal(elements.get('flightDuration').value,'3600');assert.equal(elements.get('customPath').checked,false);
  assert.equal(elements.get('distanceUnit').value,'KM');assert.equal(elements.get('defaultDriveSpeed').value,'45');
});

test('Clear and editable radio choices cannot disable a committed phone USB flight request',async()=>{
  const calls=[];
  const native=async(command,args={})=>{
    calls.push({command,args});if(command==='list_devices')return [{serial:'synthetic-usb',state:'device'}];
    if(command==='connect_phone')return {connected:true,usbAirplaneAvailable:true};if(command==='pair_phone')return pendingApproval();if(command==='confirm_pair')return {ok:true,payload:{paired:true,usbAirplaneAvailable:true}};
    if(command==='request_phone')return {ok:true,state:'ACTIVE',revision:0,payload:args.request.type==='Airports'?{airports:[]}:{mode:'FLIGHT',radioRequest:{phaseId:'flight-committed-airplane',enabled:true}}};
    return {};
  };
  const {elements,intervals}=await environment(native);await import('../src/app.ts?clear-committed-radio-test');
  elements.get('adbPath').value='/synthetic/platform-tools/adb';elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();
  elements.get('pair').dispatch('click');await settle();[...intervals.values()][0]();await settle();
  elements.get('radioPolicy').value='USB';elements.get('clearActivity').dispatch('click');await settle();assert.equal(elements.get('radioPolicy').value,'USB');assert.equal(elements.get('stop').disabled,false);
  [...intervals.values()][0]();await settle();assert.ok(calls.some(call=>call.command==='set_airplane'&&call.args.phaseId==='flight-committed-airplane'&&call.args.enabled===true));
});

for(const finish of ['complete','stop','clear','heartbeat-loss'])test(`paired local GeoPack reads renew lease and discard stale work after ${finish}`,async()=>{
  const calls=[];let releaseRead,releaseImport,failHeartbeat=false;
  const native=async(command,args={})=>{
    calls.push({command,args});
    if(command==='list_devices')return [{serial:'synthetic-usb',state:'device'}];
    if(command==='connect_phone')return {connected:true};
    if(command==='pair_phone')return pendingApproval();if(command==='confirm_pair')return {ok:true,payload:{paired:true}};
    if(command==='request_phone'){
      const type=args.request.type;
      if(type==='Heartbeat'&&failHeartbeat)throw 'PHONE_CONTROL_UNAVAILABLE';
      if(type==='ImportGeoPack')return await new Promise(resolve=>{releaseImport=()=>resolve({ok:true,state:'ACTIVE',revision:1,payload:{}});});
      return {ok:true,state:type==='Stop'?'IDLE':'ACTIVE',revision:type==='Stop'?1:0,payload:type==='Airports'?{airports:[]}: {}};
    }
    return {};
  };
  const {elements,intervals}=await environment(native);await import(`../src/app.ts?local-io-${finish}`);
  elements.get('adbPath').value='/synthetic/platform-tools/adb';elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();
  elements.get('pair').dispatch('click');await settle();[...intervals.values()][0]();await settle();
  const tick=[...intervals.values()][0];
  const pack={schemaVersion:1,id:'synthetic',name:'Delayed local pack',bounds:{south:0,west:0,north:2,east:2},nodes:[{id:'a',lat:0,lon:0},{id:'b',lat:1,lon:1}],edges:[{from:'a',to:'b',modes:['WALK'],speedLimitMps:2,bidirectional:true}],places:[],polygons:[],source:'synthetic tests',attribution:'Synthetic; not real roads'};
  elements.get('packFile').files=[{size:100,text:()=>new Promise(resolve=>{releaseRead=()=>resolve(JSON.stringify(pack));})}];elements.get('packFile').dispatch('change');await settle();
  const heartbeats=()=>calls.filter(c=>c.command==='request_phone'&&c.args.request.type==='Heartbeat').length;
  const prior=heartbeats();tick();await settle();assert.equal(heartbeats(),prior+1,'Local file reads must not suspend the paired controller lease');
  if(finish==='stop'){elements.get('stop').dispatch('click');await settle();}
  if(finish==='clear'){elements.get('clearActivity').dispatch('click');await settle();}
  if(finish==='heartbeat-loss'){failHeartbeat=true;tick();await settle();assert.equal(intervals.size,0);}
  const message=elements.get('status').textContent;
  releaseRead();await settle();
  if(finish!=='complete'){
    assert.equal(calls.some(c=>c.command==='request_phone'&&c.args.request.type==='ImportGeoPack'),false,'Canceled read cannot send its pack to a changed/cleared phone session');
    assert.equal(elements.get('status').textContent,message);assert.notEqual(elements.get('coverage').textContent,pack.name);return;
  }
  assert.equal(typeof releaseImport,'function');const duringImport=heartbeats();tick();await settle();assert.equal(heartbeats(),duringImport,'Keepalive cannot queue behind phone import/mutation work');
  releaseImport();await settle();assert.equal(elements.get('coverage').textContent,pack.name);
});

test('local I/O drains an outstanding heartbeat before a pack mutation captures revision',async()=>{
  const calls=[];let releaseRead,releaseHeartbeat;
  const native=async(command,args={})=>{
    calls.push({command,args});if(command==='list_devices')return [{serial:'synthetic-usb',state:'device'}];if(command==='connect_phone')return {connected:true};if(command==='pair_phone')return pendingApproval();if(command==='confirm_pair')return {ok:true,payload:{paired:true}};
    if(command==='request_phone'){const type=args.request.type;if(type==='Heartbeat')return await new Promise(resolve=>{releaseHeartbeat=()=>resolve({ok:true,state:'ACTIVE',revision:7,payload:{}});});return {ok:true,state:'ACTIVE',revision:type==='ImportGeoPack'?8:0,payload:type==='Airports'?{airports:[]}: {}};}return {};
  };
  const {elements,intervals}=await environment(native);await import('../src/app.ts?local-io-heartbeat-drain');
  elements.get('adbPath').value='/synthetic/platform-tools/adb';elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();elements.get('pair').dispatch('click');await settle();[...intervals.values()][0]();await settle();
  const pack={schemaVersion:1,id:'synthetic',name:'Drain test',bounds:{south:0,west:0,north:2,east:2},nodes:[],edges:[],places:[],polygons:[],source:'synthetic tests',attribution:'Synthetic'};
  elements.get('packFile').files=[{size:100,text:()=>new Promise(resolve=>{releaseRead=()=>resolve(JSON.stringify(pack));})}];elements.get('packFile').dispatch('change');await settle();const tick=[...intervals.values()][0];tick();await settle();
  releaseRead();await settle();assert.equal(calls.some(c=>c.args.request?.type==='ImportGeoPack'),false,'Phone import waits for the existing independent heartbeat');tick();await settle();assert.equal(calls.filter(c=>c.args.request?.type==='Heartbeat').length,1,'No second heartbeat queues while draining');
  releaseHeartbeat();await settle();const imported=calls.find(c=>c.args.request?.type==='ImportGeoPack');assert.equal(imported.args.request.expectedRevision,7);assert.equal(elements.get('coverage').textContent,'Drain test');
});

test('large GeoPack hashing keeps lease alive but phone transfer stays serialized',async()=>{
  const originalCrypto=globalThis.crypto;let releaseDigest;const calls=[];
  Object.defineProperty(globalThis,'crypto',{configurable:true,value:{subtle:{digest:()=>new Promise(resolve=>{releaseDigest=()=>resolve(new Uint8Array(32).buffer);})}}});
  try{
    const native=async(command,args={})=>{calls.push({command,args});if(command==='list_devices')return [{serial:'synthetic-usb',state:'device'}];if(command==='connect_phone')return {connected:true};if(command==='pair_phone')return pendingApproval();if(command==='confirm_pair')return {ok:true,payload:{paired:true}};if(command==='request_phone')return {ok:true,state:'ACTIVE',revision:0,payload:args.request.type==='Airports'?{airports:[]}:args.request.type==='BeginGeoPack'?{transferId:'synthetic-transfer'}: {}};return {};};
    const {elements,intervals}=await environment(native);await import('../src/app.ts?local-io-hash');
    elements.get('adbPath').value='/synthetic/platform-tools/adb';elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();elements.get('pair').dispatch('click');await settle();[...intervals.values()][0]();await settle();
    const pack={schemaVersion:1,id:'synthetic',name:'Hash test',bounds:{south:0,west:0,north:2,east:2},nodes:[],edges:[],places:Array.from({length:3000},(_,i)=>({id:`p${i}`,name:'x'.repeat(200),lat:1,lon:1})),polygons:[],source:'synthetic tests',attribution:'Synthetic'};
    const data=JSON.stringify(pack);assert.ok(new TextEncoder().encode(data).length>600000);
    elements.get('packFile').files=[{size:data.length,text:async()=>data}];elements.get('packFile').dispatch('change');await settle();assert.equal(typeof releaseDigest,'function');const tick=[...intervals.values()][0];tick();await settle();assert.ok(calls.some(c=>c.args.request?.type==='Heartbeat'));assert.equal(calls.some(c=>c.args.request?.type==='BeginGeoPack'),false);
    releaseDigest();await settle();assert.ok(calls.some(c=>c.args.request?.type==='CommitGeoPack'));assert.equal(elements.get('coverage').textContent,'Hash test');
  }finally{Object.defineProperty(globalThis,'crypto',{configurable:true,value:originalCrypto});}
});

for(const stop of [false,true])test(`local PMTiles index loading renews the lease${stop?' and Stop cancels late application':''}`,async()=>{
  const originalFetch=globalThis.fetch;const calls=[];let releaseHeader;
  globalThis.fetch=async()=>({ok:true,json:async()=>({version:8,sources:{},layers:[]})});
  try{
    const native=async(command,args={})=>{calls.push({command,args});if(command==='list_devices')return [{serial:'synthetic-usb',state:'device'}];if(command==='connect_phone')return {connected:true};if(command==='pair_phone')return pendingApproval();if(command==='confirm_pair')return {ok:true,payload:{paired:true}};if(command==='request_phone')return {ok:true,state:args.request.type==='Stop'?'IDLE':'ACTIVE',revision:0,payload:args.request.type==='Airports'?{airports:[]}: {}};return {};};
    const {elements,intervals}=await environment(native);
    window.maplibregl={Map:class{addControl(){}on(){}isStyleLoaded(){return false;}setStyle(){}resize(){}jumpTo(){}},AttributionControl:class{},addProtocol(){},removeProtocol(){}};
    window.pmtiles={PMTiles:class{getHeader(){return new Promise(resolve=>{releaseHeader=()=>resolve({minZoom:0,maxZoom:2,tileType:2,centerLon:0,centerLat:0,centerZoom:0,minLon:-180,maxLon:180,minLat:-85,maxLat:85,rootDirectoryLength:1,jsonMetadataLength:2});});}async getMetadata(){return {};}},SharedPromiseCache:class{},Protocol:class{add(){}},TileType:{Mvt:1,Png:2,Jpeg:3,Webp:4}};
    await import(`../src/app.ts?local-archive-${stop}`);
    elements.get('adbPath').value='/synthetic/platform-tools/adb';elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();elements.get('pair').dispatch('click');await settle();[...intervals.values()][0]();await settle();
    elements.get('archiveFile').files=[new File([new Uint8Array(512)],'synthetic-world.pmtiles')];elements.get('archiveFile').dispatch('change');await settle();[...intervals.values()][0]();await settle();assert.equal(calls.filter(c=>c.args.request?.type==='Heartbeat').length,1);
    if(stop){elements.get('stop').dispatch('click');await settle();}const message=elements.get('status').textContent;releaseHeader();await settle();
    if(stop){assert.equal(elements.get('status').textContent,message);assert.equal(elements.get('mapMode').value,'online');assert.equal(intervals.size,0);}else{assert.equal(elements.get('mapMode').value,'offline');assert.match(elements.get('coverage').textContent,/synthetic-world/);}
  }finally{globalThis.fetch=originalFetch;}
});

test('companion discovery normalizes an extended Windows drive path and preserves manual choices',async()=>{
  const extended=String.raw`\\?\C:\LocalSpoof\companion\localspoof.apk`;
  const {elements}=await environment(async()=>({adbPath:String.raw`C:\SDK\platform-tools\adb.exe`,apkPath:extended}));await import('../src/app.ts?extended-apk-discovery');
  elements.get('findTools').dispatch('click');await settle();assert.equal(elements.get('apkPath').value,String.raw`C:\LocalSpoof\companion\localspoof.apk`);
  elements.get('apkPath').value=String.raw`D:\Manual\chosen.apk`;elements.get('adbPath').value=String.raw`D:\Manual\adb.exe`;
  elements.get('findTools').dispatch('click');await settle();assert.equal(elements.get('apkPath').value,String.raw`D:\Manual\chosen.apk`);assert.equal(elements.get('adbPath').value,String.raw`D:\Manual\adb.exe`);
});

for(const [index,path] of [String.raw`\\server\share\companion.apk`,String.raw`\\?\UNC\server\share\companion.apk`,String.raw`\\.\C:\companion.apk`,String.raw`\\?\GLOBALROOT\Device\companion.apk`,'//server/share/companion.apk'].entries())test(`APK discovery rejects network and device path ${index}`,async()=>{
  const {elements}=await environment(async()=>({adbPath:null,apkPath:path}));await import(`../src/app.ts?unsafe-apk-discovery-${index}`);
  elements.get('findTools').dispatch('click');await settle();assert.equal(elements.get('apkPath').value,'');
});

test('USB connect requests approval without typing and waits for explicit phone approval',async()=>{
  const calls=[];let approved=false;
  const native=async(command,args={})=>{calls.push({command,args});if(command==='list_devices')return [{serial:'synthetic-usb',state:'device'}];if(command==='connect_phone')return {connected:true};if(command==='pair_phone')return {ok:true,payload:{pairingId:'a'.repeat(32),comparisonCode:'012345',pending:true}};if(command==='confirm_pair')return approved?{ok:true,payload:{paired:true}}:{ok:false,error:'PAIRING_PENDING'};if(command==='request_phone')return {ok:true,state:'IDLE',revision:0,payload:args.request.type==='Airports'?{airports:[]}: {}};return {};};
  const {elements,intervals}=await environment(native);await import('../src/app.ts?no-typing-connect');
  assert.equal(elements.has('pairSecret'),false,'No secret/code entry exists in the renderer');
  elements.get('adbPath').value=String.raw`C:\SDK\adb.exe`;elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();
  assert.equal(calls.filter(c=>c.command==='pair_phone').length,1);assert.deepEqual(calls.find(c=>c.command==='pair_phone').args,{});
  assert.equal(elements.get('pairComparison').textContent,'012 345');assert.equal(elements.get('pairComparison').hidden,false);assert.equal(elements.get('start').disabled,true);assert.match(elements.get('setupStatus').textContent,/Compare|Approve/i);
  [...intervals.values()][0]();await settle();assert.equal(elements.get('start').disabled,true);assert.equal(elements.get('pairComparison').textContent,'012 345');
  approved=true;[...intervals.values()][0]();await settle();assert.equal(elements.get('pairComparison').textContent,'');assert.equal(elements.get('pairComparison').hidden,true);assert.equal(elements.get('setupToggle').attributes.get('aria-label'),'Phone connected');
});

for(const failure of ['expired','transport'])test(`pending approval ${failure} keeps the selected target for a fresh no-typing retry`,async()=>{
  const calls=[];let attempts=0;
  const native=async(command,args={})=>{calls.push({command,args});if(command==='list_devices')return [{serial:'synthetic-usb',state:'device'}];if(command==='connect_phone')return {connected:true};if(command==='pair_phone')return {ok:true,payload:{pairingId:(++attempts===1?'a':'b').repeat(32),comparisonCode:attempts===1?'012345':'678901',pending:true}};if(command==='confirm_pair'){if(failure==='transport')throw 'PHONE_CONTROL_UNAVAILABLE';return {ok:false,error:'PAIRING_EXPIRED'};}return {};};
  const {elements,intervals}=await environment(native);await import(`../src/app.ts?no-typing-retry-${failure}`);
  const adb=String.raw`C:\SDK\adb.exe`;elements.get('adbPath').value=adb;elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();[...intervals.values()][0]();await settle();
  assert.equal(elements.get('adbPath').value,adb);assert.equal(elements.get('devices').value,'synthetic-usb');assert.equal(elements.get('pair').disabled,false);assert.equal(elements.get('pairComparison').textContent,'');assert.equal(intervals.size,0);assert.equal(elements.get('start').disabled,true);
  elements.get('pair').dispatch('click');await settle();assert.equal(elements.get('pairComparison').textContent,'678 901');assert.equal(intervals.size,1);assert.equal(calls.filter(c=>c.command==='connect_phone').length,1,'Native retry reopens its retained verified target');assert.equal(calls.filter(c=>c.command==='pair_phone').length,2);
});

for(const payload of [{pairingId:'a'.repeat(32),comparisonCode:'12345',pending:true},{pairingId:'not-an-id',comparisonCode:'123456',pending:true},{pairingId:'a'.repeat(32),comparisonCode:'123456',pending:false}])test(`malformed public approval response cannot enable playback ${JSON.stringify(payload)}`,async()=>{
  const native=async(command)=>{if(command==='list_devices')return [{serial:'synthetic-usb',state:'device'}];if(command==='connect_phone')return {connected:true};if(command==='pair_phone')return {ok:true,payload};return {};};
  const {elements,intervals}=await environment(native);await import(`../src/app.ts?bad-approval-${Object.values(payload).join('-')}`);
  elements.get('adbPath').value='/synthetic/adb';elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();
  assert.equal(elements.get('start').disabled,true);assert.equal(elements.get('pairComparison').textContent,'');assert.equal(intervals.size,0);assert.equal(elements.get('pair').disabled,false);assert.match(elements.get('setupStatus').textContent,/invalid approval/i);
});

test('a late approval reply after explicit Disconnect cannot revive pairing or comparison activity',async()=>{
  let releaseApproval;const calls=[];
  const native=async(command,args={})=>{calls.push({command,args});if(command==='list_devices')return [{serial:'synthetic-usb',state:'device'}];if(command==='connect_phone')return {connected:true};if(command==='pair_phone')return pendingApproval();if(command==='confirm_pair')return new Promise(resolve=>{releaseApproval=()=>resolve({ok:true,payload:{paired:true}});});return {};};
  const {elements,intervals}=await environment(native);await import('../src/app.ts?late-phone-approval');
  elements.get('adbPath').value='/synthetic/adb';elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();[...intervals.values()][0]();await settle();
  elements.get('disconnect').dispatch('click');await settle();releaseApproval();await settle();
  assert.equal(elements.get('start').disabled,true);assert.equal(elements.get('pairComparison').textContent,'');assert.equal(elements.get('devices').value,'');assert.equal(intervals.size,0);assert.equal(calls.some(c=>c.command==='request_phone'),false);
});

async function locationEnvironment(snapshots, suffix){
  const calls=[];let index=0;
  const native=async(command,args={})=>{
    calls.push({command,args});
    if(command==='list_devices')return [{serial:'location-fixture',state:'device'}];
    if(command==='connect_phone')return {connected:true};
    if(command==='pair_phone')return pendingApproval();
    if(command==='confirm_pair')return {ok:true,payload:{paired:true}};
    if(command==='request_phone')return {ok:true,state:'IDLE',revision:0,payload:{},...(args.request.type==='Snapshot'?snapshots[Math.min(index++,snapshots.length-1)]:{})};
    return {};
  };
  const env=await environment(native);await import(`../src/app.ts?${suffix}`);env.elements.get('adbPath').value='/fixture/adb';
  const approve=async()=>{env.elements.get('scanDevices').dispatch('click');await settle();env.elements.get('connect').dispatch('click');await settle();[...env.intervals.values()][0]();await settle();};
  return {...env,calls,approve};
}

test('approved fresh device location seeds an untouched origin once; live simulation and editing preserve the chosen origin',async()=>{
  const first={point:{lat:10,lon:20},source:'DEVICE',ageMs:0,accuracyMeters:5},second={point:{lat:11,lon:21},source:'SIMULATION',ageMs:1};
  const env=await locationEnvironment([{payload:{phoneLocation:first}},{state:'ACTIVE',payload:{phoneLocation:second}}],'live-location-test');
  const {elements,intervals}=env;
  assert.equal(elements.get('usePhoneOrigin').disabled,true);
  await env.approve();assert.equal(elements.get('originLat').value,'10.000000');assert.match(elements.get('phoneLocationStatus').textContent,/Device location/);
  [...intervals.values()][0]();await settle();assert.equal(elements.get('originLat').value,'10.000000');assert.match(elements.get('phoneLocationStatus').textContent,/Simulated location/);
  elements.get('usePhoneOrigin').dispatch('click');assert.equal(elements.get('originLat').value,'11.000000');
  elements.get('disconnect').dispatch('click');await settle();assert.equal(elements.get('usePhoneOrigin').disabled,true);assert.match(elements.get('phoneLocationStatus').textContent,/unavailable/i);
});

test('a chosen origin before pairing cannot be silently replaced and stale or wrong-source device data disables capture',async()=>{
  const env=await locationEnvironment([{payload:{phoneLocation:{point:{lat:10,lon:20},source:'DEVICE',ageMs:0}}},{payload:{phoneLocation:{point:{lat:12,lon:22},source:'DEVICE',ageMs:30001}}},{state:'ACTIVE',payload:{phoneLocation:{point:{lat:13,lon:23},source:'DEVICE',ageMs:0}}}],'chosen-origin-test');
  env.elements.get('originLat').value='4';env.elements.get('originLat').dispatch('input');await env.approve();assert.equal(env.elements.get('originLat').value,'4');
  for(let i=0;i<2;i++){[...env.intervals.values()][0]();await settle();assert.equal(env.elements.get('usePhoneOrigin').disabled,true);assert.equal(env.elements.get('originLat').value,'4');}
});

test('changing address role cancels outstanding results instead of applying them to another endpoint',async()=>{
  let release;
  const env=await environment(async(command)=>{if(command==='search_places')return await new Promise(resolve=>{release=resolve;});return {};});await import('../src/app.ts?address-role-test');
  env.elements.get('searchRole').value='origin';env.elements.get('searchRole').dispatch('change');env.elements.get('placeSearch').value='Synthetic address';env.elements.get('searchPlaces').dispatch('click');
  await settle();env.elements.get('searchRole').value='destination';env.elements.get('searchRole').dispatch('change');release([{name:'Late origin result',lat:5,lon:6}]);await settle();
  assert.equal(env.elements.get('searchResults').children.length,0);assert.equal(env.elements.get('destinationLat').value,'41.8838');
});

test('desktop prepares a no-pack all-instant airport journey with jump wait and sends flight-only options',async()=>{
  const originalFetch=globalThis.fetch;globalThis.fetch=async()=>({ok:true,json:async()=>[{id:'UI-departure',name:'Departure',lat:0,lon:0.01,type:'large_airport',scheduledService:true,closed:false},{id:'UI-arrival',name:'Arrival',lat:0,lon:0.1,type:'large_airport',scheduledService:true,closed:false}]});
  let prepared;const calls=[];
  const native=async(command,args={})=>{
    calls.push({command,args});
    if(command==='list_devices')return [{serial:'flight-ui',state:'device'}];if(command==='connect_phone')return {connected:true};if(command==='pair_phone')return pendingApproval();if(command==='confirm_pair')return {ok:true,payload:{paired:true}};
    if(command==='request_phone'){
      if(args.request.type==='PreparePlan'){
        prepared=args.request.payload;
        const catalog=await (await import('../src/geography.ts')).loadAirports(),departure=catalog.find(a=>a.id===prepared.onlineFlightRoutes.departureAirportId),arrival=catalog.find(a=>a.id===prepared.onlineFlightRoutes.arrivalAirportId);
        const segments=[{role:'DEPARTURE',kind:'INSTANT',points:[prepared.origin,{lat:departure.lat,lon:departure.lon}],durationMs:0,routeSource:'INSTANT_LOCATION'},{role:'AIR',kind:'FLIGHT',points:[{lat:departure.lat,lon:departure.lon},{lat:arrival.lat,lon:arrival.lon}],durationMs:0,routeSource:'FLIGHT_JOURNEY'},{role:'ARRIVAL',kind:'INSTANT',points:[{lat:arrival.lat,lon:arrival.lon},prepared.destination],durationMs:0,routeSource:'INSTANT_LOCATION'}];
        return {ok:true,state:'IDLE',revision:0,payload:{preparedId:'flight-ui-plan',preview:{mode:'FLIGHT',geometry:segments.flatMap(s=>s.points),segments,departureGroundMode:'INSTANT',arrivalGroundMode:'INSTANT',flightTransitMode:'JUMP',distanceMeters:10000,durationMs:33000,originAirport:departure,destinationAirport:arrival}}};
      }
      return {ok:true,state:'IDLE',revision:0,payload:{}};
    }
    if(command==='online_route')throw new Error('Instant legs must not request roads');return {};
  };
  try{
    const {elements,modes,intervals}=await environment(native);await import('../src/app.ts?flight-wire-ui-test');await settle();
    const catalog=await (await import('../src/geography.ts')).loadAirports();
    assert.equal(elements.get('departureAirport').children.length,catalog.length+1);
    elements.get('adbPath').value='/fixture/adb';elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();[...intervals.values()][0]();await settle();
    modes[3].dispatch('click');for(const id of ['departureGroundMode','arrivalGroundMode']){elements.get(id).value='INSTANT';elements.get(id).dispatch('input');}
    elements.get('flightTransitMode').value='JUMP';elements.get('flightTransitMode').dispatch('input');elements.get('flightDuration').value='';
    elements.get('originLat').value=String(catalog[0].lat);elements.get('originLon').value=String(catalog[0].lon);elements.get('destinationLat').value=String(catalog[1].lat);elements.get('destinationLon').value=String(catalog[1].lon);
    elements.get('prepare').dispatch('click');await settle();await settle();
    assert.ok(prepared);assert.equal(prepared.routeSource,'FLIGHT_JOURNEY');assert.equal(prepared.departureGroundMode,'INSTANT');assert.equal(prepared.arrivalGroundMode,'INSTANT');assert.equal(prepared.flightTransitMode,'JUMP');
    assert.deepEqual(Object.keys(prepared.onlineFlightRoutes).sort(),['arrivalAirportId','departureAirportId']);
    assert.equal(calls.some(c=>c.command==='online_route'),false);assert.equal(calls.some(c=>c.command==='request_phone'&&c.args.request.type==='Airports'),false);assert.equal(elements.get('start').disabled,false);assert.equal(calls.some(c=>c.command==='request_phone'&&['Start','CommitPlan'].includes(c.args.request.type)),false);
    elements.get('clearActivity').dispatch('click');assert.equal(elements.get('departureGroundMode').value,'DRIVE');assert.equal(elements.get('arrivalGroundMode').value,'DRIVE');assert.equal(elements.get('flightTransitMode').value,'TIMED');assert.equal(elements.get('start').disabled,true);
  }finally{globalThis.fetch=originalFetch;}
});

test('a cancelled earlier online preview cannot remove the keepalive ownership of a newly paired preview',async()=>{
  const calls=[];let state='IDLE',oldReply,newReply,routeCount=0;
  const native=async(command,args={})=>{
    calls.push({command,args});if(command==='list_devices')return [{serial:'owner-fixture',state:'device'}];if(command==='connect_phone')return {connected:true};if(command==='pair_phone')return pendingApproval();if(command==='confirm_pair')return {ok:true,payload:{paired:true}};
    if(command==='online_route')return await new Promise(resolve=>{const reply=()=>resolve({profile:'DRIVE',provider:'FOSSGIS OSRM',points:[args.origin,args.destination],distanceMeters:1000,durationSeconds:100,snapOriginMeters:0,snapDestinationMeters:0});if(++routeCount===1)oldReply=reply;else newReply=reply;});
    if(command==='request_phone'){if(args.request.type==='Stop')state='IDLE';return {ok:true,state,revision:0,payload:args.request.type==='PreparePlan'?{preparedId:'owner-plan',preview:{mode:'DRIVE',geometry:[{lat:0,lon:0},{lat:0,lon:0.01}],distanceMeters:1000,durationMs:100000}}:{}};}return {};
  };
  const {elements,modes,intervals}=await environment(native);await import('../src/app.ts?online-work-owner-test');
  const pair=async()=>{elements.get('adbPath').value='/fixture/adb';elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();[...intervals.values()][0]();await settle();};
  const preview=async()=>{modes[2].dispatch('click');elements.get('originLat').value='0';elements.get('originLon').value='0';elements.get('destinationLat').value='0';elements.get('destinationLon').value='0.01';elements.get('prepare').dispatch('click');await settle();};
  await pair();await preview();elements.get('stop').dispatch('click');await settle();await pair();await preview();
  const heartbeatCount=()=>calls.filter(c=>c.command==='request_phone'&&c.args.request.type==='Heartbeat').length;
  [...intervals.values()][0]();await settle();const first=heartbeatCount();assert.equal(first,1);
  oldReply();await settle();[...intervals.values()][0]();await settle();assert.equal(heartbeatCount(),first+1,'The old preview must not clear the new preview\u2019s independent keepalive');
  newReply();await settle();
});

test('live speeds and jump use mutations without re-preparing; unit changes preserve untouched exact speeds',async()=>{
 const calls=[];let waiting=null,phase=undefined,speeds={walkMps:1.4,driveMps:13.9,flightMps:230};
 const native=async(command,args={})=>{calls.push({command,args});if(command==='list_devices')return [{serial:'live-controls-fixture',state:'device'}];if(command==='connect_phone')return {connected:true};if(command==='pair_phone')return pendingApproval();if(command==='confirm_pair')return {ok:true,payload:{paired:true}};if(command==='request_phone'){if(args.request.type==='SetSpeeds')speeds=args.request.payload;return {ok:true,state:'ACTIVE',phase,revision:0,payload:{speeds,remainingMs:waiting?null:60000,remainingMeters:1000,waitingFor:waiting,nextStop:{point:{lat:5,lon:6},label:'departure airport',index:0}}};}return {};};
 const {elements,intervals}=await environment(native);await import('../src/app.ts?live-speeds-eta-jump');elements.get('adbPath').value='/fixture/adb';elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();[...intervals.values()][0]();await settle();
 assert.equal(elements.get('liveControls').hidden,false);assert.equal(elements.get('jumpNextStop').textContent,'Jump to departure airport');assert.equal(elements.get('jumpNextStop').disabled,false);assert.match(elements.get('eta').textContent,/1 min/);
 elements.get('speedUnit').value='KMH';elements.get('speedUnit').dispatch('change');assert.equal(elements.get('liveDriveSpeed').value,'50');elements.get('applySpeeds').dispatch('click');await settle();assert.deepEqual(calls.find(c=>c.args?.request?.type==='SetSpeeds').args.request.payload,{walkMps:1.4,driveMps:13.9,flightMps:230});
 elements.get('jumpNextStop').dispatch('click');await settle();assert.equal(calls.filter(c=>c.args?.request?.type==='JumpToNextStop').length,1);assert.equal(calls.some(c=>c.args?.request?.type==='PreparePlan'),false);
 waiting='AIRPLANE_MODE';[...intervals.values()][0]();await settle();assert.equal(elements.get('jumpNextStop').disabled,true);assert.equal(elements.get('eta').textContent,'Waiting for airplane mode');elements.get('jumpNextStop').dispatch('click');await settle();assert.equal(calls.filter(c=>c.args?.request?.type==='JumpToNextStop').length,1);waiting=null;phase='BOARDING';[...intervals.values()][0]();await settle();assert.equal(elements.get('jumpNextStop').disabled,true);elements.get('jumpNextStop').dispatch('click');await settle();assert.equal(calls.filter(c=>c.args?.request?.type==='JumpToNextStop').length,1);
});

test('bookmarks write only after explicit save and saved route loading cannot start phone playback',async()=>{
 const calls=[];let store={version:1,items:[]};const native=async(command,args={})=>{calls.push({command,args});if(command==='saved_load')return store;if(command==='saved_put'){store={version:1,items:[args.item]};return store;}return {};};
 const {elements,modes}=await environment(native);await import('../src/app.ts?explicit-bookmark-only');assert.equal(calls.some(c=>c.command.startsWith('saved_')),false);
 elements.get('savePlace').dispatch('click');assert.equal(elements.get('saveDialog').open,true);assert.equal(calls.some(c=>c.command==='saved_put'),false);elements.get('savedName').value='A chosen destination';elements.get('savedGroup').value='Travel';elements.get('confirmSave').dispatch('click');await settle();
 const saved=calls.find(c=>c.command==='saved_put').args.item;assert.deepEqual(Object.keys(saved).sort(),['destination','group','id','kind','name']);assert.equal(saved.group,'Travel');assert.equal(saved.kind,'PLACE');assert.equal(calls.some(c=>c.command==='request_phone'),false);
 elements.get('savedToggle').dispatch('click');await settle();assert.equal(calls.filter(c=>c.command==='saved_load').length,1);assert.equal(elements.get('savedList').children.length,1);
 modes[2].dispatch('click');elements.get('addMapStop').dispatch('click');elements.get('offlineMap').dispatch('pointerdown',{clientX:500,clientY:300,pointerId:1});elements.get('offlineMap').dispatch('pointerup',{clientX:500,clientY:300});assert.equal(elements.get('stopList').children.length,1);
 elements.get('saveRoute').dispatch('click');elements.get('savedName').value='My route';elements.get('confirmSave').dispatch('click');await settle();const route=calls.filter(c=>c.command==='saved_put').at(-1).args.item;assert.equal(route.mode,'DRIVE');assert.equal(route.stops.length,1);assert.equal(Object.hasOwn(route,'phoneLocation'),false);assert.equal(Object.hasOwn(route,'geometry'),false);assert.equal(calls.some(c=>c.command==='request_phone'),false);
});

test('nearby category uses map center, stages current role and discards pan/offline late replies',async()=>{
 const calls=[];let resolvePoi;
 const native=async(command,args={})=>{calls.push({command,args});if(command==='nearby_places')return new Promise(r=>resolvePoi=r);return {};};
 const {elements,categories}=await environment(native);let fakeMap;
 class MapStub {events=new Map();constructor(){fakeMap=this;}on(event,fn){this.events.set(event,fn);}addControl(){}isStyleLoaded(){return false;}getCenter(){return {lat:41.8781,lng:-87.6298};}getZoom(){return 13;}resize(){}setStyle(){}jumpTo(){}easeTo(){} }
 class MarkerStub {constructor(){}setLngLat(){return this;}addTo(){return this;}remove(){} }
 window.maplibregl={Map:MapStub,Marker:MarkerStub,AttributionControl:class{},addProtocol(){},removeProtocol(){}};
 const oldFetch=globalThis.fetch;globalThis.fetch=async()=>({ok:true,json:async()=>({version:8,sources:{},layers:[]})});
 try{
  await import('../src/app.ts?nearby-epoch-test');await settle();categories[0].dispatch('click');await settle();const args=calls.find(c=>c.command==='nearby_places').args;assert.equal(args.category,'HOTEL');assert.equal(args.center.lat,41.8781);assert.ok(Math.abs(args.center.lon+87.6298)<1e-8);
  fakeMap.events.get('moveend')();resolvePoi([{name:'Late hotel',lat:41.8781,lon:-87.6298,category:'HOTEL'}]);await settle();assert.equal(elements.get('nearbyResults').children.length,0);
  elements.get('searchArea').dispatch('click');await settle();resolvePoi([{name:'Public hotel',lat:41.8781,lon:-87.6298,category:'HOTEL'}]);await settle();assert.equal(elements.get('nearbyResults').children.length,1);
  elements.get('searchRole').value='origin';elements.get('searchRole').dispatch('change');elements.get('nearbyResults').children[0].dispatch('click');assert.equal(elements.get('originAddress').textContent,'Public hotel');assert.equal(elements.get('start').disabled,true);
  elements.get('searchArea').dispatch('click');await settle();elements.get('mapMode').value='offline';elements.get('mapMode').dispatch('change');resolvePoi([{name:'Late hotel',lat:41.8781,lon:-87.6298,category:'HOTEL'}]);await settle();assert.equal(elements.get('nearbyPanel').hidden,true);assert.equal(elements.get('nearbyResults').children.length,0);const before=calls.length;categories[0].dispatch('click');await settle();assert.equal(calls.length,before);
 }finally{globalThis.fetch=oldFetch;}
});

test('actual simulated speed and cruise range come from phone telemetry, live edits preserve 125mph, and original radio state controls guidance',async()=>{
 const calls=[];let controls={mode:'FIXED',driveMinMps:13.9,driveMaxMps:13.9,followRoadLimits:true},waiting=null,radio;
 const payload=()=>({speeds:{walkMps:1.4,driveMps:13.9,flightMps:230},remainingMs:60000,remainingMeters:1000,waitingFor:waiting,speedControls:controls,motion:{speedMps:26.8224,sampleAgeMs:0,sampleSequence:5,placementGeneration:1,roadLimitMps:31.2928,roadLimitSource:'MAPPED',health:'LIVE'},phoneLocation:{point:{lat:0,lon:0},source:'SIMULATION',ageMs:0,sampleSequence:5,speedMps:26.8224},...(radio?{desiredAirplaneMode:radio.enabled,radioObserved:!radio.enabled}:{})});
 const native=async(command,args={})=>{calls.push({command,args});if(command==='list_devices')return [{serial:'motion-fixture',state:'device'}];if(command==='connect_phone')return {connected:true};if(command==='pair_phone')return pendingApproval();if(command==='confirm_pair')return {ok:true,payload:{paired:true}};if(command==='request_phone'){if(args.request.type==='SetSpeeds'){const r=args.request.payload;controls={mode:r.drivingSpeedMode,driveMinMps:r.driveMinMps,driveMaxMps:r.driveMaxMps,followRoadLimits:r.followRoadLimits};}return {ok:true,state:'ACTIVE',revision:0,payload:payload()};}return {};};
 const {elements,intervals}=await environment(native);await import('../src/app.ts?actual-motion-controls');elements.get('adbPath').value='/fixture/adb';elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();const tick=[...intervals.values()][0];assert.equal(tick.delay,500);tick();await settle();
 assert.equal(elements.get('actualSpeed').textContent,'60 mph · limit 70');elements.get('liveDrivingMode').value='VARIABLE';elements.get('liveDrivingMode').dispatch('change');elements.get('liveDriveMin').value='90';elements.get('liveDriveMax').value='125';elements.get('liveFollowLimits').checked=false;elements.get('applySpeeds').dispatch('click');await settle();const set=calls.find(c=>c.args?.request?.type==='SetSpeeds').args.request.payload;assert.equal(set.drivingSpeedMode,'VARIABLE');assert.ok(Math.abs(set.driveMinMps-40.2336)<1e-10);assert.ok(Math.abs(set.driveMaxMps-55.88)<1e-10);assert.equal(set.followRoadLimits,false);
 elements.get('speedUnit').value='KMH';elements.get('speedUnit').dispatch('change');assert.equal(elements.get('liveDriveMax').value,'201.2');elements.get('applySpeeds').dispatch('click');await settle();const sets=calls.filter(c=>c.args?.request?.type==='SetSpeeds');assert.equal(sets.at(-1).args.request.payload.driveMaxMps,set.driveMaxMps,'Changing units must not round or change physical cruise speed');
 waiting='RESTORE_CONNECTIVITY';radio={phaseId:'restore-initial-on',enabled:true};tick();await settle();assert.match(elements.get('radioGuidance').textContent,/original.*enable airplane mode/i);assert.equal(elements.get('radioGuidance').hidden,false);
 elements.get('followLocation').dispatch('click');assert.equal(elements.get('followLocation').attributes.get('aria-pressed'),'true');
});
test('rapid telemetry polling sends one heartbeat per four seconds and retains status during slow planning',async()=>{
 const calls=[];let release;const native=async(command,args={})=>{calls.push({command,args});if(command==='list_devices')return [{serial:'cadence-fixture',state:'device'}];if(command==='connect_phone')return {connected:true};if(command==='pair_phone')return pendingApproval();if(command==='confirm_pair')return {ok:true,payload:{paired:true}};if(command==='online_route')return new Promise(resolve=>{release=()=>resolve({profile:'DRIVE',provider:'FOSSGIS OSRM',points:[{lat:41.8781,lon:-87.6298},{lat:41.8838,lon:-87.6324}],distanceMeters:1000,durationSeconds:80,snapOriginMeters:0,snapDestinationMeters:0});});if(command==='request_phone')return {ok:true,state:'IDLE',revision:0,payload:{}};return {};};
 const {elements,modes,intervals}=await environment(native);await import('../src/app.ts?motion-poll-cadence');elements.get('adbPath').value='/fixture/adb';elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();const tick=[...intervals.values()][0];tick.advance(500);await settle();tick.advance(500);await settle();const count=type=>calls.filter(c=>c.args?.request?.type===type).length;const first=count('Heartbeat');assert.equal(first,1);for(let i=0;i<4;i++){tick.advance(500);await settle();}assert.equal(count('Heartbeat'),first);const snapshots=count('Snapshot');modes[2].dispatch('click');elements.get('prepare').dispatch('click');await settle();tick.advance(500);await settle();assert.ok(count('Snapshot')>snapshots,'Provider planning must not freeze live status');tick.advance(1500);await settle();assert.equal(count('Heartbeat'),first+1);elements.get('clearActivity').dispatch('click');release();await settle();
});

test('an active phone route survives ordinary map clicks and draft edits; canceled held editing restores the approved geometry',async()=>{
 const oldFetch=globalThis.fetch;globalThis.fetch=async()=>({ok:true,json:async()=>({version:8,sources:{},layers:[]})});let map;const sources=new Map(),calls=[];let held=false,timer=60000;
 const activePreview={mode:'DRIVE',geometry:[{lat:0,lon:0},{lat:0,lon:.01}],distanceMeters:1112,durationMs:100000};
 const choices={mode:'DRIVE',origin:{lat:0,lon:0},destination:{lat:0,lon:.01},speedMps:13.9,radioPolicy:'MANUAL',stops:[]};
 const native=async(command,args={})=>{calls.push({command,args});if(command==='list_devices')return [{serial:'active-map-fixture',state:'device'}];if(command==='connect_phone')return {connected:true};if(command==='pair_phone')return pendingApproval();if(command==='confirm_pair')return {ok:true,payload:{paired:true}};if(command==='request_phone'){const type=args.request.type;if(type==='BeginRemainingEdit'){held=true;timer=null;return {ok:true,state:'PAUSED',revision:1,payload:{editId:'edit-1',confirmedOrigin:{lat:0,lon:.005},choices}};}if(type==='CancelRemainingEdit')held=false;return {ok:true,state:'PAUSED',revision:held?1:2,payload:{timedPauseRemainingMs:timer,activePreview,activeChoices:choices,canUndo:true,speeds:{walkMps:1.4,driveMps:13.9,flightMps:230},remainingMs:100000,remainingMeters:1112,waitingFor:null}};}return {};};
 try{
  const {elements,intervals}=await environment(native);
  class MapStub{events=new Map();constructor(){map=this;}on(k,v){this.events.set(k,v);}isStyleLoaded(){return true;}getSource(k){return sources.get(k);}addSource(k,v){sources.set(k,{data:v.data,setData(data){this.data=data;}});}addLayer(){}addControl(){}setStyle(){this.events.get('style.load')?.();}resize(){}jumpTo(){}easeTo(){}getCenter(){return {lat:0,lng:0};}getZoom(){return 12;}}
  window.maplibregl={Map:MapStub,AttributionControl:class{},Marker:class{setLngLat(){return this;}addTo(){return this;}remove(){}},addProtocol(){},removeProtocol(){}};
  await import('../src/app.ts?active-map-retained-104');await settle();elements.get('adbPath').value='/fixture/adb';elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();[...intervals.values()][0]();await settle();
  const original=structuredClone(sources.get('journey').data);const destination=elements.get('destinationLat').value;map.events.get('click')({lngLat:{lat:10,lng:20}});assert.deepEqual(sources.get('journey').data,original);assert.equal(elements.get('destinationLat').value,destination);assert.match(elements.get('status').textContent,/unchanged/);
  elements.get('chooseDestination').dispatch('click');map.events.get('click')({lngLat:{lat:10,lng:20}});assert.equal(elements.get('destinationLat').value,'10.000000');assert.deepEqual(sources.get('journey').data,original);assert.equal(calls.some(c=>['CommitPlan','ApplyRemainingPlan'].includes(c.args?.request?.type)),false);
  elements.get('beginRouteEdit').dispatch('click');await settle();assert.equal(elements.get('originLat').value,'0');assert.equal(elements.get('originLon').value,'0.005');assert.equal(elements.get('originLat').disabled,true);assert.match(elements.get('editNotice').textContent,/Scheduled resume was canceled/);[...intervals.values()][0]();await settle();assert.match(elements.get('editNotice').textContent,/press Resume/);elements.get('cancelRouteEdit').dispatch('click');await settle();assert.equal(held,false);assert.deepEqual(sources.get('journey').data,original);assert.equal(elements.get('applyRouteEdit').hidden,true);assert.match(elements.get('status').textContent,/Scheduled resume was canceled/);
 }finally{globalThis.fetch=oldFetch;}
});

test('Wi-Fi debugging pairing and app approval are separate; code cleared and transport never silently switches',async()=>{
 const calls=[];
 const native=async(command,args={})=>{calls.push({command,args});if(command==='discover_wireless')return {services:[{instance:'phone',kind:'CONNECT',endpoint:'192.168.1.10:44001'}],devices:[]};if(command==='pair_wireless_os')return {paired:true};if(command==='connect_wireless_os')return {serial:'192.168.1.10:44001',state:'device',model:'Synthetic phone',identity:'synthetic-id'};if(command==='connect_phone')return {connected:true,usbAirplaneAvailable:true,transport:'WIFI'};if(command==='pair_phone')return pendingApproval();if(command==='confirm_pair')return {ok:true,payload:{paired:true,usbAirplaneAvailable:true}};if(command==='request_phone')return {ok:true,state:'IDLE',revision:0,payload:{desktopUpdateSafe:true}};return {};};
 const {elements,intervals}=await environment(native);await import('../src/app.ts?wifi-flow');
 elements.get('adbPath').value='/synthetic/platform-tools/adb';elements.get('wifiTransport').dispatch('click');assert.equal(elements.get('wifiSetup').hidden,false);assert.equal(elements.get('usbInstall').hidden,true);
 elements.get('wifiPairEndpoint').value='192.168.1.10:37001';elements.get('wifiPairCode').value='012345';elements.get('pairWirelessOs').dispatch('click');await settle();assert.equal(elements.get('wifiPairCode').value,'');assert.equal(calls.some(c=>c.command==='pair_phone'),false);assert.equal(calls.at(-1).args.pairingCode,'012345');
 elements.get('wifiConnectEndpoint').value='192.168.1.10:44001';elements.get('connectWirelessOs').dispatch('click');await settle();assert.equal(elements.get('devices').value,'192.168.1.10:44001');
 elements.get('connect').dispatch('click');await settle();assert.equal(calls.find(c=>c.command==='connect_phone').args.transport,'WIFI');assert.equal(elements.get('pairComparison').textContent,'012 345');assert.equal(elements.get('usbTransport').disabled,true);elements.get('usbTransport').dispatch('click');assert.equal(elements.get('wifiSetup').hidden,false);
 [...intervals.values()][0]();await settle();assert.equal(elements.get('radioPolicy').usbOption.disabled,true,'Wireless never enables remote airplane-mode control, even for malformed capability assertion');
});

test('update checks are manual and install remains blocked after active phone disconnect',async()=>{
 const calls=[];let phase='AVAILABLE';
 const native=async(command,args={})=>{calls.push({command,args});if(command==='list_devices')return [{serial:'synthetic-usb',state:'device',model:'Synthetic phone'}];if(command==='connect_phone')return {connected:true};if(command==='pair_phone')return pendingApproval();if(command==='confirm_pair')return {ok:true,payload:{paired:true}};if(command==='request_phone')return {ok:true,state:'PAUSED',revision:0,payload:{desktopUpdateSafe:false}};if(command==='check_updates'||command==='update_status')return {phase,currentVersion:'0.8.0',version:'0.9.0',notes:'Verified notes',received:0,total:1234};if(command==='download_update'){phase='READY';return {phase,currentVersion:'0.8.0',version:'0.9.0',notes:'Verified notes',received:1234,total:1234};}return {};};
 const {elements,intervals}=await environment(native);await import('../src/app.ts?updates-flow');assert.equal(calls.some(c=>c.command==='check_updates'),false);assert.equal(intervals.size,0);
 elements.get('checkUpdates').dispatch('click');await settle();assert.match(elements.get('updateStatus').textContent,/0.9.0/);assert.equal(elements.get('downloadUpdate').disabled,false);
 elements.get('downloadUpdate').dispatch('click');await settle();assert.equal(elements.get('installUpdate').disabled,false);assert.equal(intervals.size,0);
 elements.get('adbPath').value='/synthetic/platform-tools/adb';elements.get('scanDevices').dispatch('click');await settle();elements.get('connect').dispatch('click');await settle();[...intervals.values()][0]();await settle();assert.equal(elements.get('installUpdate').disabled,true,'Paused journey is not safe for install');elements.get('disconnect').dispatch('click');await settle();assert.equal(elements.get('installUpdate').disabled,true,'Disconnect cannot turn an unknown phone state into idle');assert.equal(calls.some(c=>c.command==='install_update'),false);
});
