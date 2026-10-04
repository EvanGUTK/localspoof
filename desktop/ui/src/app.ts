import {point, planFromInputs, eligibleDevices, project, unproject, longitudeDelta, distance, flightSuggested, validateGeoPack,packChunks,decodePreview,validateAdbPath} from './model.ts';
import type {Point, Mode, GeoPack, Device} from './model.ts';
import {nearbyPlaces,categorySpec} from './places.ts';
import type {PoiPlace,PoiCategory} from './places.ts';
import {createWorldMap} from './world-map.ts';
import {previewJourney, searchPlaces,loadAirports as loadAirportCatalog} from './geography.ts';
import {defaultPreferences,parseDisplaySpeed,toDisplaySpeed,formatSpeedValue,speedUnitLabel,maximumSpeed,distanceLabel} from './settings.ts';
import type {SpeedUnit} from './settings.ts';
import {decodePhoneLocation,phoneLocationAt,phoneLocationLabel,routeFeatures} from './journey.ts';
import type {PhoneLocation,JourneySegment} from './journey.ts';
import {decodeSavedStore,filterSaved,savedLabel} from './saved.ts';
import type {SavedItem} from './saved.ts';
import {decodeProgress,etaLabel,durationLabel} from './playback.ts';
import {ConfirmedMotion,decodeMotion,decodeSpeedControls,motionLabel,radioGuidance} from './motion.ts';
import type {SpeedControls} from './motion.ts';
import {nativeErrorMessage} from './connection-errors.ts';

type PhoneResponse = {ok:boolean;error?:string;sessionId?:string;revision?:number;state?:string;point?:Point;phase?:string;payload?:Record<string,unknown>};
type Invoke = <T=unknown>(command:string,args?:Record<string,unknown>)=>Promise<T>;
declare global {interface Window {__TAURI__?:{core:{invoke:Invoke}}}}
const native=window.__TAURI__?.core.invoke;
const el=<T extends HTMLElement>(id:string):T=>document.getElementById(id) as T;
const input=(id:string):HTMLInputElement=>el<HTMLInputElement>(id);
const button=(id:string):HTMLButtonElement=>el<HTMLButtonElement>(id);
const text=(id:string,value:string):void=>{el(id).textContent=value;};
const status=(message:string):void=>text('status',message);
const canvas=el<HTMLCanvasElement>('offlineMap');
const context=canvas.getContext('2d');
let preferences=defaultPreferences(),selectedSpeedMps=preferences.walkSpeedMps,planeSpeedMps=230;
let draftStops:{point:Point;dwellSeconds:number;label?:string}[]=[],savedItems:SavedItem[]=[],saveKind:'PLACE'|'ROUTE'='PLACE',savedEpoch=0;
let speedError:string|undefined,walkDefaultError:string|undefined,driveDefaultError:string|undefined;
type Summary={distanceM:number;durationSeconds:number;departureAirportName?:string;arrivalAirportName?:string;description?:string;simplified?:boolean};
let lastSummary:{preview:Summary;custom:boolean}|undefined;
let mode:Mode='INSTANT', selection:'origin'|'destination'|'stop'='destination';
let connected=false, socketOpen=false, paired=false, pendingPair=false, retryTargetAvailable=false, busy=false, pollTimer:number|undefined;
let revision=0, snapshot:PhoneResponse|undefined, preparedId:string|undefined;
let pack:GeoPack|undefined, route:Point[]=[],segments:JourneySegment[]=[],current:Point|undefined;
let latestPhoneLocation:PhoneLocation|undefined,locationReceivedMs=0,originAutoEligible=true,draftTouched=false;
let center:Point={lat:28,lon:-30},zoom=1.15,mapWidth=1,mapHeight=1;
let radioPhase:string|undefined, usbAirplaneAvailable=false;
let activityEpoch=0,stopping=false,lastHeartbeatMs=-Infinity,animationFrame:number|undefined;
const motionTrack=new ConfirmedMotion();const noRoad:Point[]=[];let motionRoad:Point[]|undefined;
let drivingControls:SpeedControls={mode:'FIXED',driveMinMps:11.175999999999998,driveMaxMps:13.9,followRoadLimits:true};
let mapMode:'online'|'offline'='online',previewEpoch=0,searchEpoch=0;
let previewAbort:AbortController|undefined,searchAbort:AbortController|undefined;
let offlineArchiveName:string|undefined;
let poiCategory:PoiCategory|undefined,poiResults:PoiPlace[]=[],poiCenter:Point|undefined,poiEpoch=0,poiAbort:AbortController|undefined;
let onlinePlanningOwner:{epoch:number}|undefined;
let localIOOwner:{epoch:number}|undefined;
let heartbeatWork:{epoch:number;done:Promise<void>}|undefined;
const world=createWorldMap(el('map'),{onSelect:(p:Point)=>setEndpoint(p),onError:(message:string)=>status(message),onPlace:(place:PoiPlace)=>chooseNearby(place),onUserMove:()=>{preferences.followPhone=false;input('followPhone').checked=false;button('followLocation').setAttribute('aria-pressed','false');},onMove:()=>{if(poiCategory&&poiCenter){invalidateNearbyPending();text('nearbyStatus','Map moved. Search this area to refresh nearby places.');}}});
function nearbyAvailable():boolean{return mapMode==='online'&&el<HTMLSelectElement>('searchProvider').value!=='DISABLED';}
function invalidateNearbyPending():void{poiEpoch++;poiAbort?.abort();poiAbort=undefined;button('searchArea').disabled=false;}
function clearNearby():void{invalidateNearbyPending();poiCategory=undefined;poiResults=[];poiCenter=undefined;world.setPlaces([]);el('nearbyPanel').hidden=true;el('nearbyResults').replaceChildren();el('categoryLegend').hidden=true;for(const b of document.querySelectorAll<HTMLButtonElement>('[data-category]')){b.setAttribute('aria-pressed','false');b.disabled=!nearbyAvailable();}button('searchArea').disabled=false;}
function chooseNearby(place:PoiPlace):void{if(!poiResults.includes(place)||!nearbyAvailable())return;const role=el<HTMLSelectElement>('searchRole').value as 'origin'|'destination'|'stop';setEndpoint(place,role,place.name);status(`${place.name} selected as ${role==='stop'?'a route stop':role}. Preview before starting.`);}
async function findNearby(category:PoiCategory):Promise<void>{
 if(!nearbyAvailable()){clearNearby();status('Nearby categories need online maps and enabled place search.');return;}
 const viewport=world.getViewport();if(!viewport||viewport.zoom<9){status('Zoom into a city or search for one, then choose a category.');return;}
 cancelSearch();poiEpoch++;const epoch=poiEpoch;poiAbort?.abort();poiAbort=new AbortController();poiCategory=category;poiResults=[];poiCenter=viewport.center;world.setPlaces([]);el('nearbyResults').replaceChildren();el('nearbyPanel').hidden=false;text('nearbyTitle',`${categorySpec(category).label} · within 10 km`);text('nearbyStatus','Searching this map area · Photon / OpenStreetMap');button('searchArea').disabled=true;
 for(const b of document.querySelectorAll<HTMLButtonElement>('[data-category]'))b.setAttribute('aria-pressed',String(b.dataset.category===category));
 try{const results=await nearbyPlaces(viewport.center,category,poiAbort.signal);if(epoch!==poiEpoch||!nearbyAvailable())return;poiResults=results;world.setPlaces(results);status(results.length?'Choose a numbered nearby place, then preview your journey.':'No named places found in this map area.');const spec=categorySpec(category);el('categoryLegend').hidden=false;el('categoryLegend').style.color=spec.color;text('categoryLegend',`${spec.label}: numbered pins match the list`);text('nearbyStatus',results.length?`${results.length} named places · distance from searched center. Select a place for ${el<HTMLSelectElement>('searchRole').value==='stop'?'Add stop':el<HTMLSelectElement>('searchRole').value==='origin'?'From':'To'}.`:'No named places found here. Pan to another area and search again.');
 for(const [index,place] of results.entries()){const result=document.createElement('button');result.type='button';const number=document.createElement('span');number.className='place-number';number.style.backgroundColor=spec.color;number.textContent=String(index+1);const copy=document.createElement('span');copy.className='place-copy';copy.textContent=place.name;const sub=document.createElement('small');sub.textContent=distanceLabel(distance(viewport.center,place),preferences.distanceUnit);copy.append(sub);result.append(number,copy);result.addEventListener('click',()=>chooseNearby(place));el('nearbyResults').append(result);}
 }catch{if(epoch===poiEpoch)text('nearbyStatus','Nearby places could not load. Wait briefly and search this area again.');}finally{if(epoch===poiEpoch){poiAbort=undefined;button('searchArea').disabled=false;}}
}
for(const b of document.querySelectorAll<HTMLButtonElement>('[data-category]'))b.addEventListener('click',()=>void findNearby(b.dataset.category as PoiCategory));
button('searchArea').addEventListener('click',()=>{if(poiCategory)void findNearby(poiCategory);});button('clearPlaces').addEventListener('click',clearNearby);
button('morePlaces').addEventListener('click',()=>{const opened=el('moreCategories').hidden;el('moreCategories').hidden=!opened;button('morePlaces').setAttribute('aria-expanded',String(opened));});
button('legendToggle').addEventListener('click',()=>{const opened=el('legendDetails').hidden;el('legendDetails').hidden=!opened;button('legendToggle').setAttribute('aria-expanded',String(opened));});
class UiError extends Error {}
function adbExecutable():string{try{return validateAdbPath(input('adbPath').value);}catch{throw new UiError('Enter an absolute path to adb or adb.exe before scanning or connecting.');}}
const initial={originLat:'41.8781',originLon:'-87.6298',destinationLat:'41.8838',destinationLon:'-87.6324'};

function refreshControls():void {
  const ready=paired&&connected&&!busy&&!stopping,location=freshPhoneLocation();refreshMotion();
  button('prepare').disabled=busy||stopping;button('fitJourney').disabled=!route.length;
  button('prepare').textContent=paired&&connected?'Preview on phone':'Preview journey';
  button('clearActivity').disabled=stopping;
  button('start').disabled=!ready||!preparedId;
  button('pause').disabled=!ready||!['ACTIVE','PAUSED','HOLDING'].includes(snapshot?.state??'');
  button('pause').textContent=snapshot?.state==='PAUSED'?'Resume':'Pause';
  button('stop').disabled=!paired||!connected||stopping||!snapshot||snapshot.state==='IDLE';
  button('connect').disabled=!native||busy||stopping||!el<HTMLSelectElement>('devices').value||socketOpen;
  button('installApk').disabled=!native||busy||stopping||!el<HTMLSelectElement>('devices').value||!input('apkPath').value.trim()||socketOpen;
  button('pair').disabled=!native||!retryTargetAvailable||paired||busy||stopping||pendingPair;
  button('disconnect').disabled=(!socketOpen&&!retryTargetAvailable)||stopping;
  button('scanDevices').disabled=!native||busy||stopping||socketOpen;
  button('findTools').disabled=!native||busy||stopping||socketOpen;
  button('setupToggle').setAttribute('aria-label',connected?'Phone connected':'Connect phone');button('setupToggle').setAttribute('title',connected?'Phone connected':'Connect phone');
  el('previewBanner').hidden=connected;
  el('statusDot').classList.toggle('connected',connected);
  el('flightSettings').hidden=mode!=='FLIGHT';el('advancedFlight').hidden=mode!=='FLIGHT';el('planeSpeedLabel').hidden=mode!=='FLIGHT';el('routeSpeed').hidden=mode==='INSTANT';el('originCard').hidden=mode==='INSTANT';el('routeModes').hidden=mode==='INSTANT';el('stopBuilder').hidden=!['DRIVE','WALK'].includes(mode);button('saveRoute').hidden=mode==='INSTANT';button('routeTab').setAttribute('aria-pressed',String(mode!=='INSTANT'));text('destinationHeading',mode==='INSTANT'?'Selected location':'To');
  const progress=decodeProgress(snapshot?.payload),active=['ACTIVE','PAUSED','HOLDING'].includes(snapshot?.state??'');el('liveControls').hidden=!paired||!active;const playing=['ACTIVE','PAUSED'].includes(snapshot?.state??'');button('prepare').hidden=playing;button('start').hidden=playing;button('applySpeeds').disabled=!ready||!active;button('jumpNextStop').disabled=!ready||snapshot?.state!=='ACTIVE'||!progress?.nextStop||!!progress.waitingFor||!!snapshot?.phase&&!['ORIGIN_GROUND','FLIGHT_INTERVAL','DESTINATION_GROUND'].includes(snapshot.phase);text('jumpNextStop',progress?.nextStop?`Jump to ${progress.nextStop.label}`:'Jump to next stop');
  if(progress&&active)text('eta',etaLabel(progress));if(!progress&&active)text('eta',snapshot?.state==='PAUSED'?'Paused':'ETA unavailable');
  el('drivingOptions').hidden=!['DRIVE','FLIGHT'].includes(mode);el('cruiseRange').hidden=el<HTMLSelectElement>('drivingSpeedMode').value!=='VARIABLE';el('liveCruiseRange').hidden=el<HTMLSelectElement>('liveDrivingMode').value!=='VARIABLE';
  el('boardingHold').hidden=mode!=='FLIGHT';
  el('flightDurationLabel').hidden=true;
  button('usePhoneOrigin').disabled=!paired||!connected||stopping||!location||location.ageMs>30000;
  el('customPathLabel').hidden=!['WALK','DRIVE'].includes(mode);
  const usb=el<HTMLSelectElement>('radioPolicy').querySelector<HTMLOptionElement>('option[value="USB"]');
  if(usb) usb.disabled=!connected || !usbAirplaneAvailable;
  if(usb?.disabled && el<HTMLSelectElement>('radioPolicy').value==='USB') el<HTMLSelectElement>('radioPolicy').value='MANUAL';
}
function searchAvailable():boolean{return mapMode==='offline'?!!pack:el<HTMLSelectElement>('searchProvider').value!=='DISABLED';}
function cancelPreview():void{previewEpoch++;previewAbort?.abort();previewAbort=undefined;searchEpoch++;searchAbort?.abort();searchAbort=undefined;button('searchPlaces').disabled=!searchAvailable();}
function discardActivity():void {
  clearNearby();cancelPreview();pack=undefined;offlineArchiveName=undefined;
  draftStops=[];renderStops();route=[];segments=[];latestPhoneLocation=undefined;motionTrack.clear();current=undefined;drivingControls={mode:'FIXED',driveMinMps:11.176,driveMaxMps:13.9,followRoadLimits:true};originAutoEligible=false;preparedId=undefined;snapshot=undefined;revision=0;radioPhase=undefined;
  input('placeSearch').value='';el('searchResults').replaceChildren();
  for(const name of Object.keys(initial))input(name).value='';text('originAddress','');text('destinationAddress','');draftStops=[];renderStops();el<HTMLSelectElement>('departureGroundMode').value='DRIVE';el<HTMLSelectElement>('arrivalGroundMode').value='DRIVE';el<HTMLSelectElement>('flightTransitMode').value='TIMED';text('airportChoiceSummary','Airports are chosen automatically from the worldwide catalog. You can override either airport.');
  selectedSpeedMps=mode==='WALK'?preferences.walkSpeedMps:mode==='INSTANT'?preferences.walkSpeedMps:preferences.driveSpeedMps;speedError=undefined;renderSpeed();lastSummary=undefined;input('dwell').value='30';input('flightDuration').value='3600';
  input('customPath').checked=false;
  el<HTMLSelectElement>('departureAirport').value='';el<HTMLSelectElement>('arrivalAirport').value='';el<HTMLSelectElement>('radioPolicy').value='MANUAL';
  center={lat:28,lon:-30};zoom=1.15;world.clear();text('routeSummary','Select coordinates to prepare a new journey.');
  text('sessionState',connected?'Phone ready':'No phone connected');updateMapMode();draw();
}
async function invoke<T>(command:string,args:Record<string,unknown>={}):Promise<T>{if(!native)throw new Error('Open the desktop app to connect a phone.');return native<T>(command,args);}
function setupFeedback(message:string,error=false):void{text('setupStatus',message);el('setupStatus').classList.toggle('error',error);status(message);}
async function run(action:()=>Promise<void>,setupProgress?:string):Promise<void>{
  if(busy||stopping)return;const epoch=activityEpoch;busy=true;
  if(setupProgress){el('setup').setAttribute('aria-busy','true');setupFeedback(setupProgress);}
  refreshControls();try{await action();}catch(error){if(epoch===activityEpoch){const message=error instanceof UiError?error.message:nativeErrorMessage(error);if(setupProgress)setupFeedback(message,true);else status(message);}}
  finally{if(epoch===activityEpoch)busy=false;if(setupProgress)el('setup').setAttribute('aria-busy','false');refreshControls();}
}
async function request(type:string,payload:Record<string,unknown>={}):Promise<PhoneResponse>{
  if(!paired)throw new Error('Pair with the phone first.');
  const epoch=activityEpoch;
  const response=await invoke<PhoneResponse>('request_phone',{request:{type,payload,expectedRevision:revision}});
  if(epoch!==activityEpoch)throw new UiError('The controller connection changed.');
  if(typeof response.revision==='number'&&response.revision>=revision) revision=response.revision;
  if(type==='Heartbeat'&&response.ok)lastHeartbeatMs=performance.now();
  if(!response.ok)throw new UiError(friendlyError(response.error));
  return response;
}
function friendlyError(code:unknown):string {
  const known:Record<string,string>={MISSING_COVERAGE:'Import a geographic pack covering both endpoints.',NO_ROUTE:'No permitted road route connects these points.',STALE_REVISION:'The phone changed. Refresh its state and preview again.',PHONE_START_REQUIRED:'Open LocalSpoof on the phone and start the prepared plan.',MOCK_PERMISSION:'Select LocalSpoof as the mock location app in Developer options.',INVALID_PLAN:'The phone could not prepare this plan. Check coordinates, coverage, and durations.',PAIRING_PENDING:'Confirm pairing on your phone.',PAIRING_EXPIRED:'The approval request expired. Request approval again and compare the new number.'};
  return typeof code==='string'&&known[code]?known[code]:'The phone rejected this operation. Check its current status.';
}
function applySnapshot(response:PhoneResponse):void {
  if(typeof response.revision==='number'&&response.revision<revision)return;
  snapshot=response;if(typeof response.revision==='number')revision=response.revision;
  const state=response.state??'IDLE';latestPhoneLocation=decodePhoneLocation(response.payload?.phoneLocation,state,paired&&connected);locationReceivedMs=performance.now();
  if(state!=='IDLE'||response.payload?.preparedPreview)originAutoEligible=false;
  const location=latestPhoneLocation;const telemetry=decodeMotion(response.payload?.motion);
  if(!location)motionTrack.clear();else if(location.source==='SIMULATION'&&telemetry){const phase=response.phase;const road=phase==='ORIGIN_GROUND'?segments[0]?.points??route:phase==='DESTINATION_GROUND'?segments[2]?.points??route:phase==='FLIGHT_INTERVAL'?noRoad:route;if(motionRoad!==road){motionTrack.clear();motionRoad=road;}motionTrack.accept(location.point,telemetry.sampleSequence,locationReceivedMs,state==='ACTIVE'&&telemetry.health==='LIVE'&&telemetry.sampleAgeMs!==null&&telemetry.sampleAgeMs<=1500&&!preferencesReduceMotion(),road,`${response.payload?.mode??''}:${phase??''}:${telemetry.placementGeneration}`);}else{motionTrack.clear();motionTrack.accept(location.point,0,locationReceivedMs,false);}
  refreshPhoneLocation();startAnimation();
  if(originAutoEligible&&!draftTouched&&!preparedId&&!route.length&&latestPhoneLocation?.source==='DEVICE'&&current){copyPhoneOrigin();originAutoEligible=false;world.center(current);}
  const names:Record<string,string>={IDLE:'Phone ready',READY:'Plan ready',ACTIVE:'Simulation active',PAUSED:'Simulation paused',HOLDING:'Holding destination',ERROR:'Phone needs attention'};
  text('sessionState',names[state]??'Phone connected');
  if(response.phase)status(`Journey phase: ${response.phase.replaceAll('_',' ').toLowerCase()}.`);
  else if(response.payload?.phoneStartRequired===true)status('Open LocalSpoof on your phone to start the prepared plan.');
  else if(state==='HOLDING')status('The destination stays active until Stop releases mocking.');
  else if(state==='ACTIVE')status('Phone playback continues if USB is disconnected.');
  else if(state==='IDLE')status('Select your journey and preview it on the phone.');
  const progress=decodeProgress(response.payload);if(progress&&document.activeElement!==input('liveWalkSpeed')&&document.activeElement!==input('liveDriveSpeed')&&document.activeElement!==input('livePlaneSpeed')){input('liveWalkSpeed').value=displaySpeed(progress.speeds.walkMps);input('liveDriveSpeed').value=displaySpeed(progress.speeds.driveMps);input('livePlaneSpeed').value=displaySpeed(progress.speeds.flightMps);}
  if(progress?.waitingFor){const waits:Record<string,string>={PROVIDER_ACK:'Waiting for Android to confirm the next location. Movement resumes after confirmation.',SCHEDULING_GAP:'Phone playback paused after an interrupted update. Resume on your phone to continue from the same position.',STOP_DWELL:'At a planned stop. The journey continues when its stop duration ends.',PAUSED:'Journey paused. Resume to continue from the same position.',AIRPLANE_MODE:'Departure is ready. Follow the airplane-mode prompt on your phone.',RESTORE_CONNECTIVITY:'Arrival is ready. Restore the original airplane-mode setting on your phone.'};status(waits[progress.waitingFor]??'The journey is waiting on your phone.');}
  if(state==='ERROR'){text('eta','Playback needs attention');status('Phone playback needs attention. Check LocalSpoof on your phone; Stop remains available.');}
  const controls=decodeSpeedControls(response.payload?.speedControls);if(controls&&!['liveDriveMin','liveDriveMax','liveDrivingMode','liveFollowLimits'].some(id=>document.activeElement===el(id)))renderLiveDriving(controls);
  refreshMotion();followCurrentPhone();refreshControls();draw();
}
async function loseConnection():Promise<void>{
  activityEpoch++;localIOOwner=undefined;onlinePlanningOwner=undefined;busy=false;
  connected=false;paired=false;socketOpen=false;pendingPair=false;retryTargetAvailable=false;usbAirplaneAvailable=false;clearPairingComparison();
  input('adbPath').value='';input('apkPath').value='';el<HTMLSelectElement>('devices').replaceChildren();el<HTMLSelectElement>('devices').value='';text('deviceStatus','Use Scan USB to reconnect. Enable desktop control on your phone, connect, compare the number, and approve again.');
  if(pollTimer!==undefined)window.clearInterval(pollTimer);pollTimer=undefined;snapshot=undefined;current=undefined;preparedId=undefined;revision=0;radioPhase=undefined;
  discardActivity();text('sessionState','Desktop disconnected');status('Phone playback may continue. Reconnect and pair to retrieve its current state.');text('pairStatus','Reconnect over USB to pair again.');
  try{await invoke('disconnect_phone');}catch{/* Connection already unavailable. */}refreshControls();draw();
}
function clearPairingComparison():void{text('pairComparison','');el('pairComparison').hidden=true;}
function pairingFailure(message:string):void {
  pendingPair=false;paired=false;connected=false;socketOpen=false;clearPairingComparison();
  if(pollTimer!==undefined)window.clearInterval(pollTimer);pollTimer=undefined;
  text('pairStatus',message);setupFeedback(message,true);refreshControls();
}
async function beginPairing():Promise<void>{
  const epoch=activityEpoch;clearPairingComparison();
  try{
    const result=await invoke<PhoneResponse>('pair_phone');if(epoch!==activityEpoch||stopping)return;
    if(!result.ok)throw new UiError(result.error==='PAIRING_EXPIRED'?'The request expired. Request approval again.':'Approval could not begin. Request approval again or enable USB desktop control on the phone.');
    const {pairingId,comparisonCode,pending}=result.payload??{};
    if(pending!==true||typeof pairingId!=='string'||!/^[a-fA-F0-9]{32}$/.test(pairingId)||typeof comparisonCode!=='string'||!/^[0-9]{6}$/.test(comparisonCode))throw new UiError('The phone returned an invalid approval request. Reconnect and try again.');
    socketOpen=true;pendingPair=true;text('pairComparison',`${comparisonCode.slice(0,3)} ${comparisonCode.slice(3)}`);el('pairComparison').hidden=false;
    text('pairStatus','Compare this number with LocalSpoof on your phone, then tap Approve there. This request expires after two minutes.');setupFeedback('Compare the matching number on both screens, then tap Approve on the phone. No typing needed.');startPolling();
  }catch(error){if(epoch===activityEpoch&&!stopping)pairingFailure(error instanceof UiError?error.message:nativeErrorMessage(error));}
}
async function independentHeartbeat():Promise<void>{
  if(heartbeatWork)return;
  const epoch=activityEpoch,work={epoch,done:Promise.resolve()};heartbeatWork=work;
  work.done=(async()=>{try{if(performance.now()-lastHeartbeatMs>=4000)await request('Heartbeat');if(epoch!==activityEpoch||stopping||!paired||!connected)return;applySnapshot(await request('Snapshot'));}catch{if(epoch===activityEpoch&&!stopping&&socketOpen)await loseConnection();}finally{if(heartbeatWork===work)heartbeatWork=undefined;}})();
  await work.done;
}
/** Only local asynchronous reads/hashing may share the controller with a keepalive. */
async function localIO<T>(load:()=>Promise<T>):Promise<T>{
  const owner={epoch:activityEpoch};localIOOwner=owner;
  try{return await load();}finally{
    if(localIOOwner===owner)localIOOwner=undefined;
    // Drain an already-sent heartbeat before any subsequent phone mutation captures revision.
    const heartbeat=heartbeatWork;if(owner.epoch===activityEpoch&&heartbeat?.epoch===owner.epoch)await heartbeat.done;
  }
}
async function poll():Promise<void>{
  if(stopping||!socketOpen)return;refreshPhoneLocation();draw();
  if(busy){if((onlinePlanningOwner?.epoch===activityEpoch||localIOOwner?.epoch===activityEpoch)&&paired&&!heartbeatWork)await independentHeartbeat();return;}const epoch=activityEpoch;const valid=()=>epoch===activityEpoch&&!stopping&&socketOpen;busy=true;
  try {
    if(pendingPair){const response=await invoke<PhoneResponse>('confirm_pair');if(!valid())return;if(response.ok&&response.payload?.paired===true){pendingPair=false;paired=true;connected=true;clearPairingComparison();usbAirplaneAvailable=response.payload?.usbAirplaneAvailable===true;text('pairStatus','Approved on phone. Paired for this connection.');el<HTMLDialogElement>('setup').close();const state=await request('Snapshot');if(!valid())return;applySnapshot(state);if(!valid())return;status('Phone connected. Preview your plan before starting.');}else if(response.ok||!['PairingPending','PENDING','PENDING_CONFIRMATION','PAIRING_PENDING'].includes(response.error??'')){pairingFailure('The approval request expired or was denied. Request approval again and compare the new number on the phone.');}}
    else if(paired){if(performance.now()-lastHeartbeatMs>=4000){await request('Heartbeat');if(!valid())return;}const state=await request('Snapshot');if(!valid())return;applySnapshot(state);const radio=snapshot?.payload?.radioRequest as {phaseId?:string;enabled?:boolean}|undefined;
      if(usbAirplaneAvailable&&radio&&typeof radio.phaseId==='string'&&typeof radio.enabled==='boolean'&&radio.phaseId!==radioPhase){radioPhase=radio.phaseId;try{await invoke('set_airplane',{enabled:radio.enabled,phaseId:radio.phaseId});if(!valid())return;}catch{if(!valid())return;status('Automatic airplane control failed. Use the guided radio step on your phone; the journey stays at this phase.');}}
    }
  }catch{if(valid()){if(pendingPair)pairingFailure('Phone approval connection closed. Check the USB cable and enable desktop control, then request approval again.');else await loseConnection();}}finally{if(epoch===activityEpoch)busy=false;refreshControls();}
}
function startPolling():void{if(pollTimer!==undefined)window.clearInterval(pollTimer);pollTimer=window.setInterval(()=>{void poll();},500);lastHeartbeatMs=-Infinity;}
function freshPhoneLocation():PhoneLocation|undefined{return phoneLocationAt(latestPhoneLocation,locationReceivedMs,performance.now());}
function preferencesReduceMotion():boolean{return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches??false;}
function refreshMotion():void{
 const active=paired&&connected&&['ACTIVE','PAUSED','HOLDING','ERROR'].includes(snapshot?.state??'');el('motionGauge').hidden=!active;
 const motion=decodeMotion(snapshot?.payload?.motion),elapsed=performance.now()-locationReceivedMs;
 text('actualSpeed',motionLabel(motion,preferences.speedUnit,elapsed));
 const progress=decodeProgress(snapshot?.payload),guidance=radioGuidance(progress?.waitingFor,snapshot?.payload?.desiredAirplaneMode,snapshot?.payload?.radioObserved);
 el('radioGuidance').hidden=!active||!guidance;text('radioGuidance',guidance??'');
 const delayed=motion?.health==='STALE'||motion?.sampleAgeMs!==null&&motion?.sampleAgeMs!==undefined&&motion.sampleAgeMs+elapsed>1500;
 el('trackingHealth').hidden=!active||!delayed;text('trackingHealth',delayed?'Location updates delayed. The map stays at the latest confirmed position; check the phone status.':'');
}
function startAnimation():void{
 if(animationFrame!==undefined||!window.requestAnimationFrame)return;
 let lastUiRefresh=0;const frame=(now:number)=>{animationFrame=undefined;if(!paired||!connected)return;const location=freshPhoneLocation();current=location?motionTrack.at(now)??location.point:undefined;if(mapMode==='online'||offlineArchiveName){world.setCurrent(current);followCurrentPhone();}if(now-lastUiRefresh>=250){lastUiRefresh=now;refreshPhoneLocation();refreshMotion();if(mapMode==='offline'&&!offlineArchiveName)draw();}animationFrame=window.requestAnimationFrame(frame);};
 animationFrame=window.requestAnimationFrame(frame);
}
function refreshPhoneLocation():void{const location=freshPhoneLocation();current=location?motionTrack.at(performance.now())??location.point:undefined;text('phoneLocationStatus',connected?phoneLocationLabel(location):'Phone location unavailable. Connect and approve your phone.');}
function copyPhoneOrigin():void{const location=freshPhoneLocation();if(!paired||!connected||!location||location.ageMs>30000)return;input('originLat').value=location.point.lat.toFixed(6);input('originLon').value=location.point.lon.toFixed(6);text('originAddress',location.source==='DEVICE'?'Captured device location':'Captured simulated location');originAutoEligible=false;edit();}
button('usePhoneOrigin').addEventListener('click',copyPhoneOrigin);
function inputsPlan(){if(speedError&&!(mode==='FLIGHT'&&el<HTMLSelectElement>('departureGroundMode').value==='INSTANT'&&el<HTMLSelectElement>('arrivalGroundMode').value==='INSTANT'))throw new UiError(speedError);const plan=planFromInputs({mode,originLat:input('originLat').value,originLon:input('originLon').value,destinationLat:input('destinationLat').value,destinationLon:input('destinationLon').value,speed:String(selectedSpeedMps),dwell:input('dwell').value,flightDuration:input('flightDuration').value,radioPolicy:el<HTMLSelectElement>('radioPolicy').value,departureGroundMode:el<HTMLSelectElement>('departureGroundMode').value||'DRIVE',arrivalGroundMode:el<HTMLSelectElement>('arrivalGroundMode').value||'DRIVE',flightTransitMode:el<HTMLSelectElement>('flightTransitMode').value||'TIMED'});return {...plan,...(mode==='FLIGHT'?{flightSpeedMps:planeSpeedMps}:{}),...(['DRIVE','FLIGHT'].includes(mode)?drivingPayload(false):{}),...(['WALK','DRIVE'].includes(mode)&&draftStops.length?{stops:structuredClone(draftStops)}:{}),customPath:['WALK','DRIVE'].includes(mode)&&input('customPath').checked,departureAirportId:el<HTMLSelectElement>('departureAirport').value||null,arrivalAirportId:el<HTMLSelectElement>('arrivalAirport').value||null};}
async function loadAirports():Promise<void>{
 const catalog=await loadAirportCatalog();
 for(const [id,label] of [['departureAirport','Nearest major airport to origin'],['arrivalAirport','Nearest major airport to destination']]){
  const select=el<HTMLSelectElement>(id!);const selected=select.value;select.replaceChildren();const automatic=document.createElement('option');automatic.value='';automatic.textContent=label!;select.append(automatic);
  for(const airport of catalog){const option=document.createElement('option');option.value=airport.id;option.textContent=`${airport.id} · ${airport.name}`;select.append(option);}select.value=selected;
 }
}

function renderSpeed():void{
  input('planeSpeed').value=displaySpeed(planeSpeedMps);text('liveUnit',speedUnitLabel(preferences.speedUnit));text('planeSpeedText',`Plane speed · ${speedUnitLabel(preferences.speedUnit)}`);
  input('speed').value=displaySpeed(selectedSpeedMps);
  el<HTMLSelectElement>('drivingSpeedMode').value=drivingControls.mode;input('driveMin').value=displaySpeed(drivingControls.driveMinMps);input('driveMax').value=displaySpeed(drivingControls.driveMaxMps);input('followRoadLimits').checked=drivingControls.followRoadLimits;for(const id of ['driveMin','driveMax','liveDriveMin','liveDriveMax']){input(id).min=String(toDisplaySpeed(0.1,preferences.speedUnit));input(id).max=String(toDisplaySpeed(56,preferences.speedUnit));}
  input('speed').min=String(toDisplaySpeed(0.1,preferences.speedUnit));input('speed').max=String(toDisplaySpeed(maximumSpeed(mode),preferences.speedUnit));
  el<HTMLSelectElement>('speedUnit').value=preferences.speedUnit;
  text('speedHint',`Range ${formatSpeedValue(0.1,preferences.speedUnit)}–${formatSpeedValue(maximumSpeed(mode),preferences.speedUnit)} ${speedUnitLabel(preferences.speedUnit)}. Preview again after changing speed.`);
}
function renderSettings():void{
  el<HTMLSelectElement>('settingsSpeedUnit').value=preferences.speedUnit;el<HTMLSelectElement>('distanceUnit').value=preferences.distanceUnit;
  input('defaultWalkSpeed').value=displaySpeed(preferences.walkSpeedMps);input('defaultDriveSpeed').value=displaySpeed(preferences.driveSpeedMps);
  text('walkDefaultLabel',`Default walking speed · ${speedUnitLabel(preferences.speedUnit)}`);text('driveDefaultLabel',`Default driving speed · ${speedUnitLabel(preferences.speedUnit)}`);
  input('defaultWalkSpeed').min=String(toDisplaySpeed(0.1,preferences.speedUnit));input('defaultWalkSpeed').max=String(toDisplaySpeed(3,preferences.speedUnit));
  input('defaultDriveSpeed').min=String(toDisplaySpeed(0.1,preferences.speedUnit));input('defaultDriveSpeed').max=String(toDisplaySpeed(56,preferences.speedUnit));
  input('autoFitRoute').checked=preferences.autoFitRoute;input('followPhone').checked=preferences.followPhone;
}
function changeSpeedUnit(unit:SpeedUnit):void{
  if(!['MPH','KMH','MPS'].includes(unit)){el<HTMLSelectElement>('speedUnit').value=preferences.speedUnit;el<HTMLSelectElement>('settingsSpeedUnit').value=preferences.speedUnit;return;}
  if(speedError||walkDefaultError||driveDefaultError){el<HTMLSelectElement>('speedUnit').value=preferences.speedUnit;el<HTMLSelectElement>('settingsSpeedUnit').value=preferences.speedUnit;text('settingsStatus','Enter valid speeds before changing units.');status('Enter valid speeds before changing units.');return;}
  preferences.speedUnit=unit;renderSpeed();renderSettings();renderLiveSpeeds();text('settingsStatus','Speed units changed. The actual speed is unchanged.');
}
input('speed').addEventListener('input',()=>{try{selectedSpeedMps=parseDisplaySpeed(input('speed').value,preferences.speedUnit,mode);speedError=undefined;}catch(error){speedError=error instanceof Error?error.message:'Enter a valid speed.';}edit();if(speedError)status(speedError);});
for(const id of ['speedUnit','settingsSpeedUnit'])el(id).addEventListener('change',()=>changeSpeedUnit(el<HTMLSelectElement>(id).value as SpeedUnit));
el('distanceUnit').addEventListener('change',()=>{preferences.distanceUnit=el<HTMLSelectElement>('distanceUnit').value==='KM'?'KM':'MI';if(lastSummary)previewSummary(lastSummary.preview,lastSummary.custom);});
for(const [id,presetMode] of [['defaultWalkSpeed','WALK'],['defaultDriveSpeed','DRIVE']] as const)input(id).addEventListener('input',()=>{
  try{const value=parseDisplaySpeed(input(id).value,preferences.speedUnit,presetMode);if(presetMode==='WALK'){preferences.walkSpeedMps=value;walkDefaultError=undefined;}else{preferences.driveSpeedMps=value;driveDefaultError=undefined;}text('settingsStatus','Mode default updated for this window.');if(mode===presetMode||(presetMode==='DRIVE'&&mode==='FLIGHT')){selectedSpeedMps=value;speedError=undefined;renderSpeed();edit();}}
  catch(error){const message=error instanceof Error?error.message:'Enter a valid mode default.';if(presetMode==='WALK')walkDefaultError=message;else driveDefaultError=message;text('settingsStatus',message);}
});
input('autoFitRoute').addEventListener('change',()=>{preferences.autoFitRoute=input('autoFitRoute').checked;});
function followCurrentPhone():void{if(!preferences.followPhone||snapshot?.state!=='ACTIVE'||!current)return;if(mapMode==='online'||offlineArchiveName)world.follow(current);else center={...current};}
input('mapPerspective').addEventListener('change',()=>world.setPerspective(input('mapPerspective').checked));
input('followPhone').addEventListener('change',()=>{preferences.followPhone=input('followPhone').checked;button('followLocation').setAttribute('aria-pressed',String(preferences.followPhone));followCurrentPhone();draw();});
button('fitJourney').addEventListener('click',()=>{if(!route.length)return;preferences.followPhone=false;input('followPhone').checked=false;button('followLocation').setAttribute('aria-pressed','false');if(mapMode==='online'||offlineArchiveName)world.fit(route);else fitOfflineRoute();});
button('followLocation').addEventListener('click',()=>{preferences.followPhone=!preferences.followPhone;input('followPhone').checked=preferences.followPhone;button('followLocation').setAttribute('aria-pressed',String(preferences.followPhone));followCurrentPhone();draw();});
button('settingsToggle').addEventListener('click',()=>{button('settingsToggle').focus();el<HTMLDialogElement>('settingsDialog').showModal();});
button('closeSettings').addEventListener('click',()=>el<HTMLDialogElement>('settingsDialog').close());el('settingsDialog').addEventListener('close',()=>button('settingsToggle').focus());
button('resetSettings').addEventListener('click',()=>{preferences=defaultPreferences();speedError=undefined;walkDefaultError=undefined;driveDefaultError=undefined;selectedSpeedMps=mode==='WALK'||mode==='INSTANT'?preferences.walkSpeedMps:preferences.driveSpeedMps;renderSettings();renderSpeed();edit();text('settingsStatus','Default preferences restored for this window.');});
function fitOfflineRoute():void{if(!route.length)return;const first=route[0]!,lons=route.map(p=>first.lon+longitudeDelta(p.lon,first.lon)),south=Math.min(...route.map(p=>p.lat)),north=Math.max(...route.map(p=>p.lat)),west=Math.min(...lons),east=Math.max(...lons);center={lat:(south+north)/2,lon:longitudeDelta((west+east)/2,0)};const span=Math.max(east-west,(north-south)*mapWidth/mapHeight,0.0001);zoom=Math.max(1,Math.min(20000,280/span));draw();}
function edit():void{for(const role of ['origin','destination'] as const){if(!(el(`${role}Address`).textContent??'').trim())try{const p=point(input(`${role}Lat`).value,input(`${role}Lon`).value);text(`${role}Address`,`${p.lat.toFixed(4)}, ${p.lon.toFixed(4)}`);}catch{}}draftTouched=true;originAutoEligible=false;lastSummary=undefined;previewEpoch++;previewAbort?.abort();previewAbort=undefined;route=[];segments=[];preparedId=undefined;refreshPhoneLocation();let suggestion=false;try{const plan=inputsPlan();suggestion=flightSuggested(distance(plan.origin,plan.destination));}catch{/* Invalid inputs remain editable. */}el('flightSuggestion').hidden=!suggestion||mode==='FLIGHT';text('eta','ETA —');text('routeSummary',connected?'Preview the updated journey on the phone.':'Preview the updated journey.');refreshControls();draw();}
for(const element of document.querySelectorAll<HTMLButtonElement>('[data-mode]'))element.addEventListener('click',()=>{mode=element.dataset.mode as Mode;for(const other of document.querySelectorAll<HTMLButtonElement>('[data-mode]')){const selected=other===element;other.classList.toggle('selected',selected);other.setAttribute('aria-pressed',String(selected));}selectedSpeedMps=mode==='WALK'?preferences.walkSpeedMps:mode==='INSTANT'?preferences.walkSpeedMps:preferences.driveSpeedMps;speedError=undefined;renderSpeed();edit();});
for(const id of ['originLat','originLon','destinationLat','destinationLon','dwell','flightDuration','radioPolicy','departureAirport','arrivalAirport','departureGroundMode','arrivalGroundMode','flightTransitMode','customPath'])el(id).addEventListener('input',()=>{if(['originLat','originLon','destinationLat','destinationLon'].includes(id))text(id.startsWith('origin')?'originAddress':'destinationAddress','');edit();});
for(const endpoint of ['origin','destination'] as const)button(endpoint==='origin'?'chooseOrigin':'chooseDestination').addEventListener('click',()=>{selection=endpoint;button('chooseOrigin').setAttribute('aria-pressed',String(selection==='origin'));button('chooseDestination').setAttribute('aria-pressed',String(selection==='destination'));el<HTMLSelectElement>('searchRole').value=selection;cancelSearch();input('placeSearch').focus();text('selectionHint',`Click to choose ${selection}`);});
button('setupToggle').addEventListener('click',()=>{button('setupToggle').focus();el<HTMLDialogElement>('setup').showModal();});el('setup').addEventListener('close',()=>button('setupToggle').focus());button('closeSetup').addEventListener('click',()=>el<HTMLDialogElement>('setup').close());el<HTMLFormElement>('setupForm').addEventListener('submit',event=>event.preventDefault());
async function fillLocalTools():Promise<{adbAvailable:boolean;apkAvailable:boolean}>{
  const result=await invoke<{adbPath:string|null;apkPath:string|null}>('discover_local_tools');
  if(typeof result.adbPath==='string'){try{const path=validateAdbPath(result.adbPath);if(!input('adbPath').value.trim())input('adbPath').value=path;}catch{/* Preserve a user-selected path. */}}
  const apkPath=typeof result.apkPath==='string'?result.apkPath.replace(/^\\\\\?\\(?=[A-Za-z]:[\\/])/,''):undefined;
  if(apkPath!==undefined&&apkPath.length<=4096&&!/[\u0000-\u001f]/.test(apkPath)&&/\.apk$/i.test(apkPath)&&(/^[A-Za-z]:[\\/]/.test(apkPath)||(apkPath.startsWith('/')&&!apkPath.startsWith('//')))){if(!input('apkPath').value.trim())input('apkPath').value=apkPath;}
  let adbAvailable=false;try{validateAdbPath(input('adbPath').value);adbAvailable=true;}catch{}
  const apkAvailable=/\.apk$/i.test(input('apkPath').value.trim());return {adbAvailable,apkAvailable};
}
button('findTools').addEventListener('click',()=>void run(async()=>{
  const paths=await fillLocalTools();
  const message=paths.adbAvailable?(paths.apkAvailable?'Installed tools found. Existing manual paths were preserved. Scan USB next.':'ADB path is ready. Scan USB next. If installation is needed, enter the signed companion APK path.'):(paths.apkAvailable?'Companion APK found, but ADB is missing. Install Android SDK Platform-Tools using Android Studio SDK Manager, then select its adb.exe.':'ADB was not found. Install Android SDK Platform-Tools using Android Studio SDK Manager, then enter its adb.exe path.');
  text('deviceStatus',message);setupFeedback(message,!paths.adbAvailable);
},'Finding installed ADB and the verified companion APK…'));
button('scanDevices').addEventListener('click',()=>void run(async()=>{retryTargetAvailable=false;
  const select=el<HTMLSelectElement>('devices');select.replaceChildren();select.value='';
  if(!input('adbPath').value.trim())await fillLocalTools();
  const all=await invoke<Device[]>('list_devices',{adbPath:adbExecutable()}),devices=eligibleDevices(all);
  const unauthorized=all.filter(d=>d.state==='unauthorized'&&!d.serial.includes(':')).length,offline=all.filter(d=>d.state==='offline'&&!d.serial.includes(':')).length;
  if(!devices.length){const option=document.createElement('option');option.value='';option.textContent='No authorized USB phone';select.append(option);}
  for(const device of devices){const option=document.createElement('option');option.value=device.serial;option.textContent=device.model??'Android phone';select.append(option);}
  const notes:string[]=[];if(devices.length)notes.push(`${devices.length} authorized USB phone${devices.length===1?'':'s'}.`);
  if(unauthorized)notes.push('Unlock the phone and approve this computer’s USB debugging prompt.');if(offline)notes.push('An offline phone was found; reconnect USB and unlock it.');
  if(!all.length)notes.push('No phone was found. Use a data-capable USB cable, enable USB debugging, and check the Windows phone driver.');
  if(!devices.length&&!unauthorized&&!offline&&all.length)notes.push('Choose a direct USB connection. Wireless or unavailable devices cannot connect.');
  const message=notes.join(' ');text('deviceStatus',message);setupFeedback(message,!devices.length);
},'Scanning USB phones…'));
el('devices').addEventListener('change',()=>{if(!paired&&!pendingPair)retryTargetAvailable=false;refreshControls();});input('apkPath').addEventListener('input',refreshControls);input('adbPath').addEventListener('input',()=>{if(!paired&&!pendingPair)retryTargetAvailable=false;refreshControls();});
button('installApk').addEventListener('click',()=>void run(async()=>{await invoke('install_apk',{adbPath:adbExecutable(),serial:el<HTMLSelectElement>('devices').value,apkPath:input('apkPath').value.trim()});input('apkPath').value='';setupFeedback('Companion APK installed. Open LocalSpoof on your phone and tap Enable USB desktop control.');},'Installing the verified companion APK…'));
button('connect').addEventListener('click',()=>void run(async()=>{const epoch=activityEpoch;const result=await invoke<{connected:boolean;usbAirplaneAvailable?:boolean}>('connect_phone',{adbPath:adbExecutable(),serial:el<HTMLSelectElement>('devices').value});if(epoch!==activityEpoch)return;if(result.connected!==true)throw new UiError('The phone did not confirm desktop control. Open LocalSpoof, tap Enable USB desktop control, and connect again.');usbAirplaneAvailable=result.usbAirplaneAvailable===true;socketOpen=true;retryTargetAvailable=true;originAutoEligible=!draftTouched;await beginPairing();},'Connecting and requesting phone approval…'));
button('pair').addEventListener('click',()=>{if(pendingPair||paired||!retryTargetAvailable)return;void run(beginPairing,'Requesting fresh phone approval…');});
button('disconnect').addEventListener('click',()=>void (async()=>{if(stopping||(!socketOpen&&!retryTargetAvailable))return;stopping=true;el('setup').setAttribute('aria-busy','true');setupFeedback('Disconnecting the desktop controller…');try{await loseConnection();setupFeedback('Desktop disconnected. Phone playback may continue until you stop it on the phone.');}finally{busy=false;stopping=false;el('setup').setAttribute('aria-busy','false');refreshControls();}})());
function previewSummary(preview:Summary,custom:boolean):void{
  lastSummary={preview,custom};
  text('eta',`Preview · ${durationLabel(preview.durationSeconds*1000)}`);
  const parts=[distanceLabel(preview.distanceM,preferences.distanceUnit)];
  if(preview.departureAirportName&&preview.arrivalAirportName)parts.push(`${preview.departureAirportName} → ${preview.arrivalAirportName}`);
  text('previewDetails',`${preview.description??''}${preview.simplified?' · Simplified preview':''}`);if(custom)parts.push('Custom straight-line path');else if(!preview.departureAirportName&&preview.description)parts.push(preview.description);text('routeSummary',parts.join(' · '));
}
button('prepare').addEventListener('click',()=>void run(async()=>{
  const epoch=activityEpoch,viewEpoch=++previewEpoch;previewAbort?.abort();previewAbort=new AbortController();preparedId=undefined;route=[];segments=[];let plan;
  try{plan=inputsPlan();}catch(error){status(error instanceof Error?error.message:'Check the plan fields.');return;}
  if(paired&&connected){
    let phonePlan:Record<string,unknown>=plan;
    if(mapMode==='online'&&!plan.customPath&&['WALK','DRIVE','FLIGHT'].includes(plan.mode)){
      const planningOwner={epoch};onlinePlanningOwner=planningOwner;try{const online=await previewJourney(plan,{mode:mapMode,signal:previewAbort.signal});if(epoch!==activityEpoch||viewEpoch!==previewEpoch)return;phonePlan={...plan,routeSource:online.routeSource,onlineRoute:online.onlineRoute,onlineFlightRoutes:online.onlineFlightRoutes,...(online.stopPointIndices?{stopPointIndices:online.stopPointIndices}:{})};}
      catch(error){if(epoch!==activityEpoch||viewEpoch!==previewEpoch)return;status(error instanceof Error?error.message:'The online route could not load.');return;}finally{if(onlinePlanningOwner===planningOwner)onlinePlanningOwner=undefined;const heartbeat=heartbeatWork;if(epoch===activityEpoch&&heartbeat?.epoch===epoch)await heartbeat.done;}
      if(epoch!==activityEpoch||viewEpoch!==previewEpoch||!paired||!connected)return;
    }
    const result=await request('PreparePlan',phonePlan);if(epoch!==activityEpoch||viewEpoch!==previewEpoch)return;
    const payload=result.payload??{};if(typeof payload.preparedId!=='string')throw new Error('Incomplete preview.');
    const preview=decodePreview(payload.preview);preparedId=payload.preparedId;route=preview.points;segments=preview.segments;if(preview.departureAirportName&&preview.arrivalAirportName)text('airportChoiceSummary',`Departure: ${preview.departureAirportName}. Arrival: ${preview.arrivalAirportName}.`);const description=phonePlan.routeSource==='FLIGHT_JOURNEY'?'Airport journey · separate road, air and instant legs':phonePlan.routeSource==='ONLINE_ROUTER'?'Online road route':plan.mode==='INSTANT'?'Hold selected location':plan.customPath?undefined:'Offline road route';previewSummary({...preview,description},plan.customPath);
    status('Phone preview ready. Start requires the phone foreground service to be armed.');
  }else{
    preparedId=undefined;status(mapMode==='online'?'Finding a route online…':'Finding an offline route…');
    try{const preview=await previewJourney(plan,{mode:mapMode,pack,signal:previewAbort.signal});if(epoch!==activityEpoch||viewEpoch!==previewEpoch)return;route=preview.points;segments=preview.segments??[];if(preview.departureAirportName&&preview.arrivalAirportName)text('airportChoiceSummary',`Departure: ${preview.departureAirportName}. Arrival: ${preview.arrivalAirportName}.`);previewSummary(preview,plan.customPath);status('Desktop preview ready. Connect a phone to prepare and start playback.');}
    catch(error){if(epoch!==activityEpoch||viewEpoch!==previewEpoch)return;route=[];status(error instanceof Error?error.message:'The route could not load. Try another destination or choose Offline.');}
  }
  draw();if(preferences.autoFitRoute&&route.length){if(mapMode==='online'||offlineArchiveName)world.fit(route);else fitOfflineRoute();}
}));
button('start').addEventListener('click',()=>void run(async()=>{const epoch=activityEpoch;if(!preparedId)return;await request('CommitPlan',{preparedId});if(epoch!==activityEpoch)return;const response=await request('Start');if(epoch!==activityEpoch)return;applySnapshot(response);status(response.payload?.phoneStartRequired===true?'Open LocalSpoof on your phone and start the pending plan.':'Plan started on phone.');}));
button('pause').addEventListener('click',()=>void run(async()=>{const epoch=activityEpoch,response=await request(snapshot?.state==='PAUSED'?'Resume':'Pause');if(epoch===activityEpoch)applySnapshot(response);}));
button('stop').addEventListener('click',()=>void (async()=>{if(stopping||!paired)return;stopping=true;clearNearby();activityEpoch++;localIOOwner=undefined;onlinePlanningOwner=undefined;refreshControls();try{const result=await request('Stop');const restoration=result.payload?.message;await loseConnection();discardActivity();text('sessionState','Simulation stopped');status(typeof restoration==='string'&&restoration.length<=300?restoration:'Simulation stopped. Entered activity discarded. Pair again to begin another session.');}catch(error){status(error instanceof UiError?error.message:'Stop could not be confirmed. Use Stop in LocalSpoof on your phone.');}finally{busy=false;stopping=false;refreshControls();}})());

button('importPack').addEventListener('click',()=>input('packFile').click());
input('packFile').addEventListener('change',()=>void run(async()=>{
  const epoch=activityEpoch,viewEpoch=++previewEpoch,valid=()=>epoch===activityEpoch&&viewEpoch===previewEpoch&&!stopping;
  const file=input('packFile').files?.[0];input('packFile').value='';if(!file)return;
  if(file.size>16*1024*1024){status('GeoPack exceeds the 16 MiB import limit.');return;}
  let candidate:GeoPack;try{candidate=validateGeoPack(JSON.parse(await localIO(()=>file.text())));}catch{if(valid())status('Invalid GeoPack. Use a supported bounded version 1 pack.');return;}
  if(!valid())return;
  if(paired){const data=JSON.stringify(candidate),bytes=new TextEncoder().encode(data);if(bytes.length>16*1024*1024){status('Normalized GeoPack exceeds the import limit.');return;}
    if(bytes.length<600000)await request('ImportGeoPack',{pack:candidate});else{const digest=await localIO(()=>crypto.subtle.digest('SHA-256',bytes));if(!valid())return;const sha256=Array.from(new Uint8Array(digest)).map(value=>value.toString(16).padStart(2,'0')).join('');const chunks=packChunks(data),totalChunks=chunks.length;const begin=await request('BeginGeoPack',{sizeBytes:bytes.length,sha256,totalChunks});const transferId=begin.payload?.transferId;if(typeof transferId!=='string')throw new Error('Invalid transfer.');for(let index=0;index<totalChunks;index++){if(!valid())return;await request('GeoPackChunk',{transferId,index,data:chunks[index]});}if(!valid())return;await request('CommitGeoPack',{transferId});}
  }
  if(!valid())return;
  offlineArchiveName=undefined;world.clear();pack=candidate;route=[];preparedId=undefined;input('placeSearch').disabled=false;input('placeSearch').placeholder='Search places in this pack';text('coverage',candidate.name);text('attribution',candidate.attribution);center={lat:(candidate.bounds.south+candidate.bounds.north)/2,lon:candidate.bounds.west+longitudeDelta(candidate.bounds.east,candidate.bounds.west)/2};const span=Math.max(candidate.bounds.north-candidate.bounds.south,Math.abs(longitudeDelta(candidate.bounds.east,candidate.bounds.west)),1);zoom=Math.min(2000,260/span);el<HTMLSelectElement>('mapMode').value='offline';updateMapMode();status(paired?'Pack loaded locally and validated by phone.':'Pack loaded for local map preview. Import again after pairing to use phone routing.');draw();
}));
input('placeSearch').addEventListener('input',()=>{searchEpoch++;searchAbort?.abort();searchAbort=undefined;button('searchPlaces').disabled=!searchAvailable();el('searchResults').replaceChildren();});
async function findPlaces():Promise<void>{
  if(!searchAvailable())return;clearNearby();
  const role=el<HTMLSelectElement>('searchRole').value==='origin'?'origin':el<HTMLSelectElement>('searchRole').value==='stop'?'stop':'destination';
  const query=input('placeSearch').value.trim(),epoch=++searchEpoch;searchAbort?.abort();searchAbort=undefined;
  const results=el('searchResults');results.replaceChildren();if(query.length<2){status('Enter at least two characters, then search.');return;}
  const show=(places:{name:string;lat:number;lon:number}[])=>{if(epoch!==searchEpoch)return;for(const place of places.slice(0,8)){const result=document.createElement('button');result.type='button';result.textContent=place.name;result.addEventListener('click',()=>{searchEpoch++;searchAbort?.abort();setEndpoint(place,role,place.name);center={lat:place.lat,lon:place.lon};zoom=Math.max(zoom,200);if(mapMode==='online'||offlineArchiveName)world.center(place);input('placeSearch').value='';results.replaceChildren();draw();});results.append(result);}if(!places.length)status('No places found. Try another name or enter coordinates.');};
  if(mapMode==='offline'){if(pack)show(pack.places.filter(p=>p.name.toLocaleLowerCase().includes(query.toLocaleLowerCase())));return;}
  searchAbort=new AbortController();button('searchPlaces').disabled=true;
  try{show(await searchPlaces(query,{signal:searchAbort.signal,provider:el<HTMLSelectElement>('searchProvider').value as 'PHOTON'|'NOMINATIM'|'DISABLED'}));}catch{if(epoch===searchEpoch)status('Place search could not load. Enter coordinates or choose Offline.');}
  finally{if(epoch===searchEpoch)button('searchPlaces').disabled=false;}
}
function cancelSearch():void{invalidateNearbyPending();if(poiResults.length)text('nearbyStatus','Select a numbered place for the current From / To / Add stop field.');searchEpoch++;searchAbort?.abort();searchAbort=undefined;el('searchResults').replaceChildren();button('searchPlaces').disabled=!searchAvailable();}
el('searchRole').addEventListener('change',()=>{cancelSearch();selection=el<HTMLSelectElement>('searchRole').value as 'origin'|'destination'|'stop';text('selectionHint',selection==='stop'?'Click a point to add a stop':`Click to choose ${selection}`);});
el('searchProvider').addEventListener('change',()=>{clearNearby();searchEpoch++;searchAbort?.abort();searchAbort=undefined;input('placeSearch').value='';el('searchResults').replaceChildren();button('searchPlaces').disabled=!searchAvailable();input('placeSearch').disabled=!searchAvailable();});
button('searchPlaces').addEventListener('click',()=>{void findPlaces();});input('placeSearch').addEventListener('keydown',event=>{if(event.key==='Enter'){event.preventDefault();void findPlaces();}});
button('importArchive').addEventListener('click',()=>input('archiveFile').click());
input('archiveFile').addEventListener('change',()=>void run(async()=>{const file=input('archiveFile').files?.[0];input('archiveFile').value='';if(!file)return;const epoch=activityEpoch,viewEpoch=++previewEpoch;try{await localIO(()=>world.setOfflineArchive(file));if(epoch!==activityEpoch||viewEpoch!==previewEpoch)return;offlineArchiveName=file.name;pack=undefined;el<HTMLSelectElement>('mapMode').value='offline';updateMapMode();status('Offline map opened locally. Road routes still require imported GeoPack coverage.');}catch{if(epoch===activityEpoch&&viewEpoch===previewEpoch)status('The offline map could not open. Choose a supported PMTiles archive.');}}));
function updateMapMode():void{
  cancelPreview();mapMode=el<HTMLSelectElement>('mapMode').value==='offline'?'offline':'online';clearNearby();world.setMode(mapMode);
  el('map').hidden=mapMode==='offline'&&!!pack;canvas.hidden=mapMode!=='offline'||!pack;button('searchPlaces').disabled=!searchAvailable();input('placeSearch').disabled=!searchAvailable();
  input('placeSearch').placeholder=mapMode==='online'?'City, address, or landmark':pack?'Search places in this pack':'Import a GeoPack to search places';
  input('placeSearch').value='';el('searchResults').replaceChildren();text('coverage',mapMode==='online'?'Online · worldwide map':pack?.name??(offlineArchiveName?`Offline · ${offlineArchiveName}`:'Offline · import a map or road coverage'));
  text('attribution',mapMode==='online'?'© OpenStreetMap contributors · OpenFreeMap':pack?.attribution??'Imported map · routes need GeoPack coverage');
  text('providerDisclosure',mapMode==='online'?'Online providers receive endpoints and map/search requests; Valhalla also receives road geometry for mapped limits. Providers may log requests. LocalSpoof keeps no activity history.':'Offline mode makes no map, search, or route requests. Import local coverage to follow roads. Unsaved coordinates stay in memory.');
  edit();world.resize();resize();
}
el('mapMode').addEventListener('change',updateMapMode);
button('clearActivity').addEventListener('click',()=>{
  clearNearby();cancelPreview();lastSummary=undefined;route=[];segments=[];current=undefined;preparedId=undefined;pack=undefined;offlineArchiveName=undefined;input('placeSearch').value='';el('searchResults').replaceChildren();input('packFile').value='';
  for(const name of Object.keys(initial))input(name).value='';text('originAddress','');text('destinationAddress','');draftStops=[];renderStops();el<HTMLSelectElement>('departureGroundMode').value='DRIVE';el<HTMLSelectElement>('arrivalGroundMode').value='DRIVE';el<HTMLSelectElement>('flightTransitMode').value='TIMED';text('airportChoiceSummary','Airports are chosen automatically from the worldwide catalog. You can override either airport.');
  input('dwell').value='30';input('flightDuration').value='3600';input('customPath').checked=false;
  el<HTMLSelectElement>('departureAirport').value='';el<HTMLSelectElement>('arrivalAirport').value='';el<HTMLSelectElement>('radioPolicy').value='MANUAL';
  selectedSpeedMps=mode==='WALK'||mode==='INSTANT'?preferences.walkSpeedMps:preferences.driveSpeedMps;speedError=undefined;renderSpeed();world.clear();center={lat:28,lon:-30};zoom=1.15;
  updateMapMode();text('routeSummary','Choose coordinates to preview a new journey.');
  status(['ACTIVE','PAUSED','HOLDING'].includes(snapshot?.state??'')?'Desktop preview cleared. Phone playback continues; use Stop simulation to end it.':'Entered preview activity cleared from memory.');refreshControls();draw();
});
function renderLiveSpeeds():void{const controls=decodeSpeedControls(snapshot?.payload?.speedControls);if(controls)renderLiveDriving(controls);const progress=decodeProgress(snapshot?.payload);if(!progress)return;input('liveWalkSpeed').value=displaySpeed(progress.speeds.walkMps);input('liveDriveSpeed').value=displaySpeed(progress.speeds.driveMps);input('livePlaneSpeed').value=displaySpeed(progress.speeds.flightMps);}
function preservedLiveSpeed(id:string,canonical:number,parse:()=>number):number{return input(id).value===displaySpeed(canonical)?canonical:parse();}
function displaySpeed(value:number):string{return String(Number(toDisplaySpeed(value,preferences.speedUnit).toFixed(1)));}
function renderLiveDriving(controls:SpeedControls):void{
 el<HTMLSelectElement>('liveDrivingMode').value=controls.mode;input('liveDriveMin').value=displaySpeed(controls.driveMinMps);input('liveDriveMax').value=displaySpeed(controls.driveMaxMps);input('liveFollowLimits').checked=controls.followRoadLimits;
 el('liveCruiseRange').hidden=controls.mode!=='VARIABLE';
}
function drivingPayload(live:boolean):{drivingSpeedMode:SpeedControls['mode'];driveMinMps:number;driveMaxMps:number;followRoadLimits:boolean}{
 const canonical=live?decodeSpeedControls(snapshot?.payload?.speedControls):drivingControls,prefix=live?'liveDrive':'drive',modeValue=el<HTMLSelectElement>(live?'liveDrivingMode':'drivingSpeedMode').value;
 if(modeValue!=='FIXED'&&modeValue!=='VARIABLE')throw new UiError('Choose fixed speed or a cruising range.');
 const parse=(id:string,physical:number)=>preservedLiveSpeed(id,physical,()=>parseDisplaySpeed(input(id).value,preferences.speedUnit,'DRIVE'));
 const minimum=parse(`${prefix}Min`,canonical?.driveMinMps??13.9),maximum=parse(`${prefix}Max`,canonical?.driveMaxMps??13.9);
 if(minimum>maximum)throw new UiError('Minimum cruising speed must not exceed maximum cruising speed.');
 return {drivingSpeedMode:modeValue,driveMinMps:minimum,driveMaxMps:maximum,followRoadLimits:input(live?'liveFollowLimits':'followRoadLimits').checked};
}
for(const id of ['drivingSpeedMode','driveMin','driveMax','followRoadLimits'])el(id).addEventListener(id==='driveMin'||id==='driveMax'?'input':'change',()=>{
 try{const controls=drivingPayload(false);drivingControls={mode:controls.drivingSpeedMode,driveMinMps:controls.driveMinMps,driveMaxMps:controls.driveMaxMps,followRoadLimits:controls.followRoadLimits};speedError=undefined;}catch(error){speedError=error instanceof Error?error.message:'Choose valid cruising speeds.';}
 edit();if(speedError)status(speedError);
});
el('liveDrivingMode').addEventListener('change',()=>{el('liveCruiseRange').hidden=el<HTMLSelectElement>('liveDrivingMode').value!=='VARIABLE';});
function planeSpeedInput(value:string):number{const numeric=value.trim()===''?NaN:Number(value),mps=numeric*(preferences.speedUnit==='MPH'?0.44704:preferences.speedUnit==='KMH'?1/3.6:1);if(!Number.isFinite(mps)||mps<30||mps>350)throw new UiError(`Plane speed must be ${displaySpeed(30)}–${displaySpeed(350)} ${speedUnitLabel(preferences.speedUnit)}.`);return mps;}
function renderStops():void{const host=el('stopList');host.replaceChildren();draftStops.forEach((stop,index)=>{const row=document.createElement('div');row.className='stop-row';const label=document.createElement('span');label.textContent=`${index+1}. ${stop.label??`${stop.point.lat.toFixed(4)}, ${stop.point.lon.toFixed(4)}`}`;row.append(label);const dwell=document.createElement('input');dwell.type='number';dwell.min='0';dwell.max='3600';dwell.value=String(stop.dwellSeconds);dwell.autocomplete='off';dwell.setAttribute('aria-label',`Stop ${index+1} duration in seconds`);dwell.addEventListener('input',()=>{const value=dwell.value.trim()===''?NaN:Number(dwell.value);if(!Number.isFinite(value)||value<0||value>3600){status('Stop duration must be 0–3600 seconds.');return;}stop.dwellSeconds=value;edit();});row.append(dwell);for(const [symbol,name,operation] of [['↑','Move stop earlier',()=>{if(index){[draftStops[index-1]!,draftStops[index]!]=[draftStops[index]!,draftStops[index-1]!];}}],['↓','Move stop later',()=>{if(index<draftStops.length-1){[draftStops[index+1]!,draftStops[index]!]=[draftStops[index]!,draftStops[index+1]!];}}],['×','Remove stop',()=>{draftStops.splice(index,1);}]] as const){const control=document.createElement('button');control.type='button';control.textContent=symbol;control.setAttribute('aria-label',`${name}: ${stop.label??index+1}`);control.addEventListener('click',()=>{operation();renderStops();edit();});row.append(control);}host.append(row);});}
function addStop(p:Point,name=''):void{if(!['WALK','DRIVE'].includes(mode)){status('Choose Walk or Drive to add custom route stops.');return;}if(draftStops.length>=32){status('This route supports up to 32 stops.');return;}const dwell=Number(input('stopDwell').value);if(!Number.isFinite(dwell)||dwell<0||dwell>3600){status('Stop duration must be 0–3600 seconds.');return;}draftStops.push({point:point(p.lat,p.lon),dwellSeconds:dwell,...(name?{label:name.slice(0,120)}:{})});renderStops();edit();status(`Stop ${draftStops.length} added. Preview to calculate its road route.`);}
button('routeTab').addEventListener('click',()=>{if(mode!=='INSTANT')return;document.querySelectorAll<HTMLButtonElement>('[data-mode]').forEach(control=>{if(control.dataset.mode==='DRIVE')control.click();});});
button('mapHome').addEventListener('click',()=>{button('centerMap').click();});
button('addMapStop').addEventListener('click',()=>{selection='stop';el<HTMLSelectElement>('searchRole').value='stop';cancelSearch();input('placeSearch').focus();text('selectionHint','Click a point or search to add a stop');});
input('planeSpeed').addEventListener('input',()=>{try{planeSpeedMps=planeSpeedInput(input('planeSpeed').value);speedError=undefined;edit();}catch(error){speedError=error instanceof Error?error.message:'Choose a valid plane speed.';status(speedError);}});
button('applySpeeds').addEventListener('click',()=>void run(async()=>{const epoch=activityEpoch;const canonical=decodeProgress(snapshot?.payload)?.speeds;if(!canonical)throw new UiError('Refresh the phone state before changing live speeds.');const speeds={walkMps:preservedLiveSpeed('liveWalkSpeed',canonical.walkMps,()=>parseDisplaySpeed(input('liveWalkSpeed').value,preferences.speedUnit,'WALK')),driveMps:preservedLiveSpeed('liveDriveSpeed',canonical.driveMps,()=>parseDisplaySpeed(input('liveDriveSpeed').value,preferences.speedUnit,'DRIVE')),flightMps:preservedLiveSpeed('livePlaneSpeed',canonical.flightMps,()=>planeSpeedInput(input('livePlaneSpeed').value))};await request('SetSpeeds',{...speeds,...(decodeSpeedControls(snapshot?.payload?.speedControls)?drivingPayload(true):{})});if(epoch!==activityEpoch)return;applySnapshot(await request('Snapshot'));status('Live speeds updated. The phone continues from its current position.');}));
button('jumpNextStop').addEventListener('click',()=>void run(async()=>{const progress=decodeProgress(snapshot?.payload);if(snapshot?.state!=='ACTIVE'||!progress?.nextStop||progress.waitingFor||snapshot?.phase&&!['ORIGIN_GROUND','FLIGHT_INTERVAL','DESTINATION_GROUND'].includes(snapshot.phase))return;const epoch=activityEpoch;await request('JumpToNextStop');motionTrack.clear();if(epoch!==activityEpoch)return;applySnapshot(await request('Snapshot'));status('Next-stop placement requested. Required radio steps stay in place.');}));
function savedFeedback(message:string):void{text('savedStatus',message);}
async function loadSaved():Promise<void>{if(!native){savedFeedback('Saved items are available in the desktop app.');return;}const epoch=++savedEpoch;try{const loaded=decodeSavedStore(await invoke('saved_load')).items;if(epoch!==savedEpoch)return;savedItems=loaded;renderSaved();savedFeedback(savedItems.length?`${savedItems.length} saved item${savedItems.length===1?'':'s'} on this computer.`:'No saved places or routes yet.');}catch{savedFeedback('Saved items could not be read. Your existing items were not changed.');}}
function renderSaved():void{const host=el('savedList');host.replaceChildren();for(const item of filterSaved(savedItems,input('savedFilter').value)){const row=document.createElement('div');row.className='saved-item';const title=document.createElement('strong');title.textContent=item.name;const detail=document.createElement('p');detail.textContent=savedLabel(item);const actions=document.createElement('div');actions.className='button-row';const load=document.createElement('button');load.type='button';load.textContent=item.kind==='PLACE'?'Use this place':'Load route';load.addEventListener('click',()=>{loadSavedDraft(item);el<HTMLDialogElement>('savedDialog').close();});const remove=document.createElement('button');remove.type='button';remove.textContent='Delete';remove.addEventListener('click',()=>void savedMutation(async()=>await invoke('saved_delete',{id:item.id})));const rename=document.createElement('button');rename.type='button';rename.textContent='Rename / organize';rename.addEventListener('click',()=>{editingSaved=item;saveKind=item.kind;text('saveTitle','Edit saved item');input('savedName').value=item.name;input('savedGroup').value=item.group;el<HTMLDialogElement>('saveDialog').showModal();});actions.append(load);actions.append(rename);actions.append(remove);row.append(title);row.append(detail);row.append(actions);host.append(row);}}
let editingSaved:SavedItem|undefined;
async function savedMutation(action:()=>Promise<unknown>):Promise<void>{const epoch=++savedEpoch;try{const updated=decodeSavedStore(await action()).items;if(epoch!==savedEpoch)return;savedItems=updated;renderSaved();savedFeedback('Saved items updated on this computer.');}catch{savedFeedback('The saved item could not be changed.');}}
function loadSavedDraft(item:SavedItem):void{if(item.kind==='PLACE'){setEndpoint(item.destination,'destination',item.name);world.center(item.destination);status('Saved place loaded. Preview before applying it to your phone.');return;}if(!item.origin||!item.mode)return;for(const control of document.querySelectorAll<HTMLButtonElement>('[data-mode]'))if(control.dataset.mode===item.mode)control.click();input('originLat').value=String(item.origin.lat);input('originLon').value=String(item.origin.lon);input('destinationLat').value=String(item.destination.lat);input('destinationLon').value=String(item.destination.lon);selectedSpeedMps=item.speedMps??13.9;planeSpeedMps=item.flightSpeedMps??230;drivingControls={mode:item.drivingSpeedMode??'FIXED',driveMinMps:item.driveMinMps??selectedSpeedMps,driveMaxMps:item.driveMaxMps??selectedSpeedMps,followRoadLimits:item.followRoadLimits??true};draftStops=structuredClone(item.stops??[]);input('customPath').checked=item.customPath??false;input('dwell').value=String(item.dwellSeconds??30);input('flightDuration').value=String(item.flightDurationSeconds??3600);el<HTMLSelectElement>('radioPolicy').value=item.radioPolicy??'MANUAL';el<HTMLSelectElement>('departureGroundMode').value=item.departureGroundMode??'DRIVE';el<HTMLSelectElement>('arrivalGroundMode').value=item.arrivalGroundMode??'DRIVE';el<HTMLSelectElement>('flightTransitMode').value=item.flightTransitMode??'TIMED';el<HTMLSelectElement>('departureAirport').value=item.departureAirportId??'';el<HTMLSelectElement>('arrivalAirport').value=item.arrivalAirportId??'';text('originAddress','Saved route origin');text('destinationAddress',item.name);renderSpeed();renderStops();edit();world.fit([item.origin,item.destination]);status('Saved route loaded. Preview to calculate current routes before starting.');}
function beginSave(kind:'PLACE'|'ROUTE'):void{if(!native){status('Open the desktop app to save items on this computer.');return;}try{if(kind==='ROUTE')inputsPlan();else point(input('destinationLat').value,input('destinationLon').value);}catch{status('Choose a valid destination before saving.');return;}editingSaved=undefined;saveKind=kind;text('saveTitle',kind==='PLACE'?'Save place':'Save route');input('savedName').value=el('destinationAddress').textContent??'';input('savedGroup').value='';text('saveStatus','Only this item will be kept on this computer.');el<HTMLDialogElement>('saveDialog').showModal();}
button('savePlace').addEventListener('click',()=>beginSave('PLACE'));button('saveRoute').addEventListener('click',()=>beginSave('ROUTE'));
button('savedToggle').addEventListener('click',()=>{el<HTMLDialogElement>('savedDialog').showModal();void loadSaved();});button('closeSaved').addEventListener('click',()=>el<HTMLDialogElement>('savedDialog').close());el('savedDialog').addEventListener('close',()=>button('savedToggle').focus());input('savedFilter').addEventListener('input',renderSaved);button('closeSave').addEventListener('click',()=>el<HTMLDialogElement>('saveDialog').close());
button('confirmSave').addEventListener('click',()=>void (async()=>{const name=input('savedName').value.trim(),group=input('savedGroup').value.trim();if(!name||name.length>120||group.length>80){text('saveStatus','Enter a name up to 120 characters and collection up to 80 characters.');return;}button('confirmSave').disabled=true;try{let item:SavedItem;if(editingSaved)item={...editingSaved,name,group};else{const common={id:crypto.randomUUID(),name,group,kind:saveKind,destination:point(input('destinationLat').value,input('destinationLon').value)};if(saveKind==='PLACE')item=common;else{const plan=inputsPlan();const {waypoints,...draft}=plan;item={...common,...draft,...(waypoints.length?{waypoints}:{}),kind:'ROUTE'};}}const epoch=++savedEpoch,updated=decodeSavedStore(await invoke('saved_put',{item})).items;if(epoch!==savedEpoch)return;savedItems=updated;renderSaved();el<HTMLDialogElement>('saveDialog').close();status('Saved on this computer. No cloud storage.');}catch{text('saveStatus','This item could not be saved. Existing items were not changed.');}finally{button('confirmSave').disabled=false;}})());
let deleteAllArmed=false;button('clearSaved').addEventListener('click',()=>{if(!deleteAllArmed){deleteAllArmed=true;button('clearSaved').textContent='Confirm delete all saved items';savedFeedback('Click again to permanently delete your saved places and routes.');return;}deleteAllArmed=false;button('clearSaved').textContent='Delete all saved items';void savedMutation(async()=>await invoke('saved_clear'));});

button('exportSaved').addEventListener('click',()=>{if(!savedItems.length){savedFeedback('There are no saved items to export.');return;}const bytes=JSON.stringify({version:1,items:savedItems},null,2);const url=URL.createObjectURL(new Blob([bytes],{type:'application/json'})),link=document.createElement('a');link.href=url;link.download='localspoof-saved-items.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);savedFeedback('Saved items exported to the file you chose.');});
button('importSaved').addEventListener('click',()=>input('savedFile').click());input('savedFile').addEventListener('change',()=>void (async()=>{const file=input('savedFile').files?.[0];input('savedFile').value='';if(!file)return;if(file.size>512*1024){savedFeedback('Saved-item files must be 512 KiB or smaller.');return;}try{const store=decodeSavedStore(JSON.parse(await file.text()));await savedMutation(()=>invoke('saved_import',{items:store.items}));}catch{savedFeedback('Import rejected. Existing items were not changed.');}})());
el('saveDialog').addEventListener('close',()=>{(editingSaved?button('savedToggle'):button(saveKind==='PLACE'?'savePlace':'saveRoute')).focus();});

function setEndpoint(p:Point,role:'origin'|'destination'|'stop'=selection,name=''):void{if(role==='stop'){addStop(p,name);return;}input(`${role}Lat`).value=p.lat.toFixed(6);input(`${role}Lon`).value=p.lon.toFixed(6);text(`${role}Address`,name||`${p.lat.toFixed(4)}, ${p.lon.toFixed(4)}`);edit();}
function draw():void {
  refreshPhoneLocation();
  let origin:Point|undefined,destination:Point|undefined;try{origin=point(input('originLat').value,input('originLon').value);}catch{}try{destination=point(input('destinationLat').value,input('destinationLon').value);}catch{}
  world.setState({origin:mode==='INSTANT'?undefined:origin,destination,current,route,segments,pack,stops:draftStops.map(s=>s.point)});
  if(mapMode==='online'||!pack||!context)return;const ctx=context;ctx.clearRect(0,0,mapWidth,mapHeight);ctx.fillStyle='#172124';ctx.fillRect(0,0,mapWidth,mapHeight);ctx.strokeStyle='#293638';ctx.lineWidth=1;
  const spacing=zoom>300?0.01:zoom>50?0.1:zoom>10?1:zoom>3?5:30;
  const top=unproject(0,0,center,zoom,mapWidth,mapHeight).lat,bottom=unproject(0,mapHeight,center,zoom,mapWidth,mapHeight).lat;
  for(let lat=Math.ceil(bottom/spacing)*spacing;lat<=top;lat+=spacing){const p=project({lat,lon:center.lon},center,zoom,mapWidth,mapHeight);ctx.beginPath();ctx.moveTo(0,p.y);ctx.lineTo(mapWidth,p.y);ctx.stroke();}
  const half=180/zoom;for(let lon=Math.ceil((center.lon-half)/spacing)*spacing;lon<=center.lon+half;lon+=spacing){const p=project({lat:center.lat,lon},center,zoom,mapWidth,mapHeight);ctx.beginPath();ctx.moveTo(p.x,0);ctx.lineTo(p.x,mapHeight);ctx.stroke();}
  ctx.fillStyle='#243331';if(pack){for(const polygon of pack.polygons){ctx.beginPath();polygon.forEach((p,i)=>{const xy=project(p,center,zoom,mapWidth,mapHeight);if(i===0)ctx.moveTo(xy.x,xy.y);else ctx.lineTo(xy.x,xy.y);});ctx.closePath();ctx.fill();}const nodes=new Map(pack.nodes.map(n=>[n.id,n]));ctx.strokeStyle='#56726b';ctx.lineWidth=1.6;for(const edge of pack.edges){const a=nodes.get(edge.from),b=nodes.get(edge.to);if(a&&b)line(a,b);} }
  function line(a:Point,b:Point):void{const pa=project(a,center,zoom,mapWidth,mapHeight),pb=project(b,center,zoom,mapWidth,mapHeight);if(Math.abs(pa.x-pb.x)>mapWidth*zoom/2)return;ctx.beginPath();ctx.moveTo(pa.x,pa.y);ctx.lineTo(pb.x,pb.y);ctx.stroke();}
  for(const feature of routeFeatures(route,segments).features){if(feature.properties.kind==='INSTANT')continue;ctx.strokeStyle=feature.properties.kind==='FLIGHT'?'#9850d8':'#3478ed';ctx.lineWidth=3;ctx.setLineDash(feature.properties.kind==='FLIGHT'?[8,6]:[]);const coords=feature.geometry.coordinates;for(let i=1;i<coords.length;i++)line({lat:coords[i-1]![1]!,lon:coords[i-1]![0]!},{lat:coords[i]![1]!,lon:coords[i]![0]!});}ctx.setLineDash([]);
  function marker(p:Point,color:string,label:string,labelOffset=4):void{const xy=project(p,center,zoom,mapWidth,mapHeight);ctx.beginPath();ctx.arc(xy.x,xy.y,12,0,Math.PI*2);ctx.fillStyle=`${color}22`;ctx.fill();ctx.beginPath();ctx.arc(xy.x,xy.y,5,0,Math.PI*2);ctx.fillStyle=color;ctx.fill();ctx.fillStyle='#d9e8df';ctx.font='10px system-ui';ctx.fillText(label,xy.x+15,xy.y+labelOffset);}
  try{marker(point(input('originLat').value,input('originLon').value),'#a3e3cb','ORIGIN',-12);}catch{/* Empty input. */}
  try{marker(point(input('destinationLat').value,input('destinationLon').value),'#eabd81','DESTINATION',22);}catch{/* Empty input. */}
  for(const segment of segments)if(segment.kind==='INSTANT')marker(segment.points.at(-1)!,'#b678ef','PLACE');
  if(current)marker(current,'#4b73ed','PHONE');
}
function resize():void{const rect=canvas.getBoundingClientRect();mapWidth=Math.max(1,rect.width);mapHeight=Math.max(1,rect.height);const dpr=Math.min(window.devicePixelRatio||1,2);canvas.width=Math.round(mapWidth*dpr);canvas.height=Math.round(mapHeight*dpr);context?.setTransform(dpr,0,0,dpr,0,0);draw();}
let drag:{x:number;y:number;center:Point;distance:number}|undefined;
canvas.addEventListener('pointerdown',event=>{drag={x:event.clientX,y:event.clientY,center:{...center},distance:0};canvas.setPointerCapture(event.pointerId);});
canvas.addEventListener('pointermove',event=>{if(!drag)return;const dx=event.clientX-drag.x,dy=event.clientY-drag.y;drag.distance=Math.max(drag.distance,Math.hypot(dx,dy));center=unproject(mapWidth/2-dx,mapHeight/2-dy,drag.center,zoom,mapWidth,mapHeight);draw();});
canvas.addEventListener('pointerup',event=>{if(drag&&drag.distance<5){const rect=canvas.getBoundingClientRect();setEndpoint(unproject(event.clientX-rect.left,event.clientY-rect.top,center,zoom,mapWidth,mapHeight));}drag=undefined;});canvas.addEventListener('pointercancel',()=>{drag=undefined;});
function changeZoom(factor:number):void{zoom=Math.max(1,Math.min(20000,zoom*factor));draw();}
canvas.addEventListener('wheel',event=>{event.preventDefault();changeZoom(event.deltaY<0?1.2:1/1.2);},{passive:false});button('zoomIn').addEventListener('click',()=>mapMode==='online'||offlineArchiveName?world.zoomBy(1):changeZoom(1.5));button('zoomOut').addEventListener('click',()=>mapMode==='online'||offlineArchiveName?world.zoomBy(-1):changeZoom(1/1.5));button('centerMap').addEventListener('click',()=>{try{center=point(input('originLat').value,input('originLon').value);zoom=Math.max(zoom,100);if(mapMode==='online'||offlineArchiveName)world.center(center);draw();}catch{status('Enter an origin coordinate to center the map.');}});
canvas.addEventListener('keydown',event=>{if(['+','=','-'].includes(event.key)){event.preventDefault();changeZoom(event.key==='-'?1/1.5:1.5);}else if(event.key.startsWith('Arrow')){event.preventDefault();const dx=event.key==='ArrowLeft'?-60:event.key==='ArrowRight'?60:0,dy=event.key==='ArrowUp'?-60:event.key==='ArrowDown'?60:0;center=unproject(mapWidth/2+dx,mapHeight/2+dy,center,zoom,mapWidth,mapHeight);draw();}});
window.addEventListener('resize',()=>{resize();world.resize();});window.addEventListener('pagehide',()=>{if(pollTimer!==undefined)clearInterval(pollTimer);clearNearby();cancelPreview();if(animationFrame!==undefined)window.cancelAnimationFrame?.(animationFrame);world.dispose();clearPairingComparison();});
if(!native){text('deviceStatus','Browser design preview. USB control requires the desktop app.');status('Preview journeys worldwide. Connect a phone to change its mock location.');}
el<HTMLSelectElement>('departureGroundMode').value='DRIVE';el<HTMLSelectElement>('arrivalGroundMode').value='DRIVE';el<HTMLSelectElement>('flightTransitMode').value='TIMED';el<HTMLSelectElement>('searchRole').value='destination';
renderSettings();renderSpeed();refreshControls();updateMapMode();resize();draftTouched=false;originAutoEligible=true;void loadAirports().catch(()=>text('airportChoiceSummary','Airport catalog unavailable. Retry Preview to load it.')); 
