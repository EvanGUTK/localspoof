import {point,distance} from './model.ts';
import type {JourneySegment} from './journey.ts';
import type {Point,Plan,GeoPack} from './model.ts';
export type OnlineRoute={profile:'WALK'|'DRIVE';provider:'FOSSGIS OSRM';points:Point[];edgeSpeedLimits?:(number|null)[]};
export type OnlineFlightRoutes={departureAirportId:string;arrivalAirportId:string;originGround?:OnlineRoute;destinationGround?:OnlineRoute};
export type JourneyPreview={points:Point[];distanceM:number;durationSeconds:number;description?:string;routeSource:'ONLINE_ROUTER'|'OFFLINE_GEOPACK'|'CUSTOM_PATH'|'INSTANT_LOCATION'|'FLIGHT_JOURNEY';segments?:JourneySegment[];departureAirport?:Airport;arrivalAirport?:Airport;onlineRoute?:OnlineRoute;onlineFlightRoutes?:OnlineFlightRoutes;stopPointIndices?:number[];snappedOrigin?:Point;snappedDestination?:Point;departureAirportName?:string;arrivalAirportName?:string};
export type Airport={id:string;name:string;lat:number;lon:number;type:string;scheduledService:boolean;closed:boolean};
type NativeRoute=OnlineRoute&{distanceMeters:number;durationSeconds:number;snapOriginMeters:number;snapDestinationMeters:number};
type JourneyPlan=Plan&{customPath?:boolean;departureAirportId?:string|null;arrivalAirportId?:string|null};
const cancelled=(signal?:AbortSignal)=>{if(signal?.aborted)throw new DOMException('Cancelled','AbortError');};
function checkedPoints(values:unknown):Point[]{if(!Array.isArray(values)||values.length<2||values.length>12000)throw new Error('The route is too complex for this phone build. Choose a shorter road journey.');return values.map(p=>point(p?.lat,p?.lon));}
export function validateRoadResponse(value:unknown,origin:Point,destination:Point,profile:'WALK'|'DRIVE',snapRadii:[number,number]=[250,250]):NativeRoute{
 const r=value as NativeRoute,points=checkedPoints(r?.points);if(r.profile!==profile||r.provider!=='FOSSGIS OSRM'||!Number.isFinite(r.distanceMeters)||r.distanceMeters<0||r.distanceMeters>20_000_000||!Number.isFinite(r.durationSeconds)||r.durationSeconds<0)throw new Error('The routing service returned an invalid road route.');
 if(distance(points[0]!,origin)>snapRadii[0]+0.1||distance(points.at(-1)!,destination)>snapRadii[1]+0.1)throw new Error(snapRadii.every(r=>r===250)?'No suitable road lies within 250 meters of both endpoints.':'No suitable road lies within the supported airport access distance.');
 let length=0;for(let i=1;i<points.length;i++){const segment=distance(points[i-1]!,points[i]!);if(segment>100_000)throw new Error('The routing service returned disconnected geometry.');length+=segment;}if(length>20_000_000)throw new Error('The route exceeds this build’s distance limit.');
 const edgeSpeedLimits=r.edgeSpeedLimits;if(edgeSpeedLimits!==undefined&&(!Array.isArray(edgeSpeedLimits)||edgeSpeedLimits.length!==0&&edgeSpeedLimits.length!==points.length-1||edgeSpeedLimits.some(limit=>limit!==null&&(typeof limit!=='number'||!Number.isFinite(limit)||limit<=0||limit>100))))throw new Error('The routing service returned invalid mapped road limits.');
 return {...r,points,...(edgeSpeedLimits===undefined?{}:{edgeSpeedLimits:[...edgeSpeedLimits]})};
}
let nextRouteAt=0;
/** Provider speed estimates are never interpreted as posted limits. */
function roadDuration(route:NativeRoute,plan:JourneyPlan):number{
 if(plan.mode==='WALK'||plan.followRoadLimits===false||!route.edgeSpeedLimits?.length)return route.distanceMeters/plan.speedMps;
 let meters=0,seconds=0;for(let i=1;i<route.points.length;i++){const length=distance(route.points[i-1]!,route.points[i]!);meters+=length;seconds+=length/Math.min(plan.speedMps,route.edgeSpeedLimits[i-1]??plan.speedMps);}
 return meters>0?seconds*route.distanceMeters/meters:route.distanceMeters/plan.speedMps;
}
async function roadRoute(origin:Point,destination:Point,profile:'WALK'|'DRIVE',signal?:AbortSignal,airportEndpoints:{departureAirportId?:string;arrivalAirportId?:string}={}):Promise<NativeRoute>{origin=point(origin.lat,origin.lon);destination=point(destination.lat,destination.lon);cancelled(signal);const invoke=window.__TAURI__?.core.invoke;let result:unknown;const snapRadii:[number,number]=[airportEndpoints.arrivalAirportId?5000:250,airportEndpoints.departureAirportId?5000:250];
 if(invoke){try{result=await invoke('online_route',{origin,destination,profile,...airportEndpoints});}catch(error){cancelled(signal);const code=typeof error==='string'?error:'';throw new Error(code==='RATE_LIMITED'?'Wait a moment before requesting another route.':code==='NO_ROUTE'?'No road route connects those endpoints.':code==='SNAP_TOO_FAR'?'Choose points within 250 meters of a permitted road.':'The routing service could not calculate this route. Check internet access and endpoint coverage.');}}
 else{if(Date.now()<nextRouteAt)throw new Error('Wait a moment before requesting another route.');nextRouteAt=Date.now()+1100;const prefix=profile==='WALK'?'routed-foot/route/v1/foot':'routed-car/route/v1/driving';const path=`${origin.lon},${origin.lat};${destination.lon},${destination.lat}`;
 const response=await fetch(`https://routing.openstreetmap.de/${prefix}/${path}?overview=full&geometries=geojson&steps=false&alternatives=false&generate_hints=false&radiuses=${snapRadii[0]};${snapRadii[1]}`,{signal,credentials:'omit',referrerPolicy:'no-referrer'});if(!response.ok)throw new Error('Routing service unavailable.');const raw=await response.json();const route=raw.routes?.[0];if(raw.code!=='Ok'||route?.geometry?.type!=='LineString')throw new Error('No road route connects those endpoints.');const points=checkedPoints(route.geometry.coordinates?.map((p:number[])=>({lat:p[1],lon:p[0]})));result={profile,provider:'FOSSGIS OSRM',points,distanceMeters:route.distance,durationSeconds:route.duration,snapOriginMeters:distance(points[0]!,origin),snapDestinationMeters:distance(points.at(-1)!,destination)};}
 cancelled(signal);return validateRoadResponse(result,origin,destination,profile,snapRadii);
}
function offlineJourney(pack:GeoPack,origin:Point,destination:Point,profile:'WALK'|'DRIVE',speedMps:number,followRoadLimits=true):{points:Point[];durationSeconds:number}{
 const contains=(p:Point)=>p.lat>=pack.bounds.south&&p.lat<=pack.bounds.north&&(pack.bounds.west<=pack.bounds.east?p.lon>=pack.bounds.west&&p.lon<=pack.bounds.east:p.lon>=pack.bounds.west||p.lon<=pack.bounds.east);
 if(!contains(origin)||!contains(destination))throw new Error('Endpoints are outside offline routing coverage.');
 if(!Number.isFinite(speedMps)||speedMps<=0)throw new Error('Invalid playback speed.');
 const byId=new Map(pack.nodes.map(n=>[n.id,{lat:n.lat,lon:n.lon}]));const neighbors=new Map<string,{id:string;cost:number}[]>();
 for(const e of pack.edges){if(!e.modes.includes(profile))continue;const a=byId.get(e.from)!,b=byId.get(e.to)!;const cost=distance(a,b)/(profile==='DRIVE'&&followRoadLimits?Math.min(speedMps,e.speedLimitMps):speedMps);const add=(from:string,to:string)=>{const list=neighbors.get(from)??[];list.push({id:to,cost});neighbors.set(from,list);};add(e.from,e.to);if(e.bidirectional)add(e.to,e.from);else if(!neighbors.has(e.to))neighbors.set(e.to,[]);}
 const nearest=(p:Point)=>{let best:string|undefined,meters=250;for(const node of pack.nodes){if(!neighbors.has(node.id))continue;const d=distance(p,node);if(d<=250&&(!best||d<meters)){meters=d;best=node.id;}}if(!best)throw new Error('Offline road coverage is missing near an endpoint.');return best;};
 const start=nearest(origin),end=nearest(destination),costs=new Map([[start,0]]),previous=new Map<string,string>(),queue:{id:string;cost:number}[]=[{id:start,cost:0}];
 function push(v:{id:string;cost:number}){queue.push(v);let i=queue.length-1;while(i){const p=(i-1)>>1;if(queue[p]!.cost<=v.cost)break;queue[i]=queue[p]!;i=p;}queue[i]=v;}
 function pop(){const first=queue[0]!,last=queue.pop()!;if(queue.length){let i=0;while(true){let child=i*2+1;if(child>=queue.length)break;if(child+1<queue.length&&queue[child+1]!.cost<queue[child]!.cost)child++;if(queue[child]!.cost>=last.cost)break;queue[i]=queue[child]!;i=child;}queue[i]=last;}return first;}
 while(queue.length){const current=pop();if(current.cost!==costs.get(current.id))continue;if(current.id===end){const ids=[end];while(ids.at(-1)!==start){const from=previous.get(ids.at(-1)!);if(!from||ids.length>byId.size)throw new Error('Offline route unavailable.');ids.push(from);}ids.reverse();return {points:ids.map(id=>byId.get(id)!),durationSeconds:current.cost};}
 for(const n of neighbors.get(current.id)??[]){const cost=current.cost+n.cost;if(cost<(costs.get(n.id)??Infinity)){costs.set(n.id,cost);previous.set(n.id,current.id);push({id:n.id,cost});}}}
 throw new Error('No permitted offline road connects the points.');
}
export function routeOffline(pack:GeoPack,origin:Point,destination:Point,profile:'WALK'|'DRIVE',speedMps=profile==='WALK'?1.4:13.9):Point[]{return offlineJourney(pack,origin,destination,profile,speedMps).points;}
let airportsPromise:Promise<Airport[]>|undefined;
export async function loadAirports():Promise<Airport[]> {
 airportsPromise??=fetch(new URL('../resources/airports.json',import.meta.url)).then(async r=>{
  if(!r.ok)throw new Error('Airport catalog unavailable.');
  const raw:unknown=await r.json();if(!Array.isArray(raw)||raw.length>10000)throw new Error('Airport catalog invalid.');
  return raw.map((a:Airport)=>{const p=point(a?.lat,a?.lon);if(typeof a.id!=='string'||!a.id||a.id.length>128||typeof a.name!=='string'||!a.name||a.name.length>512)throw new Error('Airport catalog invalid.');return {id:a.id,name:a.name,...p,type:a.type,scheduledService:a.scheduledService,closed:a.closed};}).filter(a=>a.type==='large_airport'&&a.scheduledService===true&&a.closed===false);
 }).catch(error=>{airportsPromise=undefined;throw error;});
 return airportsPromise;
}
export function nearestAirport(catalog:Airport[],location:Point,id?:string|null):Airport|undefined {
 if(id)return catalog.find(a=>a.id===id);
 return catalog.reduce<Airport|undefined>((best,a)=>!best||distance(a,location)<distance(best,location)?a:best,undefined);
}
async function flight(plan:JourneyPlan,options:{mode:'online'|'offline';pack?:GeoPack;signal?:AbortSignal}):Promise<JourneyPreview>{
 const {signal}=options,departureMode=plan.departureGroundMode??'DRIVE',arrivalMode=plan.arrivalGroundMode??'DRIVE',transit=plan.flightTransitMode??'TIMED';
 if(!['DRIVE','INSTANT'].includes(departureMode)||!['DRIVE','INSTANT'].includes(arrivalMode)||!['TIMED','JUMP'].includes(transit))throw new Error('Choose supported airport leg modes.');
 const catalog=await loadAirports();cancelled(signal);
 const departure=nearestAirport(catalog,plan.origin,plan.departureAirportId),arrival=nearestAirport(catalog,plan.destination,plan.arrivalAirportId);
 if(!departure||!arrival||departure.id===arrival.id)throw new Error('Select two different suitable departure and arrival airports.');
 const departurePoint=point(departure.lat,departure.lon),arrivalPoint=point(arrival.lat,arrival.lon);
 let lastOnline=false;
 const ground=async(a:Point,b:Point,mode:'DRIVE'|'INSTANT',role:'DEPARTURE'|'ARRIVAL'):Promise<{segment:JourneySegment;online?:OnlineRoute;distanceM:number}>=>{
  if(mode==='INSTANT')return {segment:{role,kind:'INSTANT',points:[a,b],durationMs:0,routeSource:'INSTANT_LOCATION'},distanceM:distance(a,b)};
  if(options.mode==='offline'){
   if(!options.pack)throw new Error('Driving airport legs need offline road coverage. Choose Instant for both ground legs or import a GeoPack.');
   const r=offlineJourney(options.pack,a,b,'DRIVE',plan.speedMps,plan.followRoadLimits??true),distanceM=r.points.slice(1).reduce((sum,p,i)=>sum+distance(r.points[i]!,p),0);
   return {segment:{role,kind:'DRIVE',points:r.points,durationMs:r.durationSeconds*1000,routeSource:'OFFLINE_GEOPACK'},distanceM};
  }
  if(lastOnline){await new Promise<void>((resolve,reject)=>{const abort=()=>{clearTimeout(timer);reject(new DOMException('Cancelled','AbortError'));};const timer=setTimeout(()=>{signal?.removeEventListener('abort',abort);resolve();},1150);signal?.addEventListener('abort',abort,{once:true});});cancelled(signal);}
  const r=await roadRoute(a,b,'DRIVE',signal,role==='DEPARTURE'?{departureAirportId:departure.id}:{arrivalAirportId:arrival.id});lastOnline=true;
  const online={profile:'DRIVE' as const,provider:r.provider,points:r.points,...(r.edgeSpeedLimits===undefined?{}:{edgeSpeedLimits:r.edgeSpeedLimits})};
  return {segment:{role,kind:'DRIVE',points:r.points,durationMs:roadDuration(r,plan)*1000,routeSource:'ONLINE_ROUTER',routeProvider:r.provider},online,distanceM:r.distanceMeters};
 };
 const first=await ground(plan.origin,departurePoint,departureMode,'DEPARTURE');cancelled(signal);
 const last=await ground(arrivalPoint,plan.destination,arrivalMode,'ARRIVAL');cancelled(signal);
 const flightStart=first.segment.points.at(-1)!,flightEnd=last.segment.points[0]!;
 if((first.online?.points.length??0)+(last.online?.points.length??0)>12000)throw new Error('Airport surface routes exceed the supported geometry size.');
 const air:JourneySegment={role:'AIR',kind:'FLIGHT',points:[flightStart,flightEnd],durationMs:transit==='JUMP'?0:plan.flightSpeedMps?distance(flightStart,flightEnd)/plan.flightSpeedMps*1000:plan.flightDurationSeconds*1000,routeSource:'FLIGHT_JOURNEY'};
 const segments=[first.segment,air,last.segment],points=segments.flatMap(s=>s.points);
 const onlineFlightRoutes=options.mode==='online'?{departureAirportId:departure.id,arrivalAirportId:arrival.id,...(first.online?{originGround:first.online}:{}),...(last.online?{destinationGround:last.online}:{})}:undefined;
 return {points,segments,distanceM:first.distanceM+distance(flightStart,flightEnd)+last.distanceM,durationSeconds:segments.reduce((sum,s)=>sum+s.durationMs/1000,0)+plan.dwellSeconds+3,routeSource:'FLIGHT_JOURNEY',onlineFlightRoutes,departureAirport:departure,arrivalAirport:arrival,departureAirportName:departure.name,arrivalAirportName:arrival.name,description:`${departure.name} → ${arrival.name}. ${departureMode==='DRIVE'?'Drive':'Instant placement'} to departure · ${transit==='JUMP'?'skip flight wait':'timed simulated flight'} · ${arrivalMode==='DRIVE'?'drive':'instant placement'} to destination. Airplane steps require the phone; airport road access may be up to 5 km from its catalog position. Ground access: ${Math.round(distance(departure,flightStart))} m / ${Math.round(distance(arrival,flightEnd))} m.`};
}
async function multiStopJourney(plan:JourneyPlan,options:{mode:'online'|'offline';pack?:GeoPack;signal?:AbortSignal}):Promise<JourneyPreview>{
 const stops=plan.stops??[];if(stops.length>32)throw new Error('A route supports at most 32 stops.');
 const endpoints=[plan.origin,...stops.map(stop=>{if(!Number.isFinite(stop.dwellSeconds)||stop.dwellSeconds<0||stop.dwellSeconds>3600)throw new Error('Stop duration must be 0–3600 seconds.');return point(stop.point.lat,stop.point.lon);}),plan.destination];
 const points:Point[]=[],stopPointIndices:number[]=[],edgeSpeedLimits:(number|null)[]=[];let distanceM=0,durationSeconds=stops.reduce((sum,s)=>sum+s.dwellSeconds,0);
 for(let index=1;index<endpoints.length;index++){
  cancelled(options.signal);const a=endpoints[index-1]!,b=endpoints[index]!;let leg:Point[],duration:number,meters:number,limits:(number|null)[]=[];
  if(options.mode==='offline'){if(!options.pack)throw new Error('Custom stops need offline road coverage or an online map.');const r=offlineJourney(options.pack,a,b,plan.mode as 'WALK'|'DRIVE',plan.speedMps,plan.followRoadLimits??true);leg=r.points;duration=r.durationSeconds;meters=leg.slice(1).reduce((sum,p,i)=>sum+distance(leg[i]!,p),0);}
  else{if(index>1)await routeCooldown(options.signal);const r=await roadRoute(a,b,plan.mode as 'WALK'|'DRIVE',options.signal);leg=r.points;duration=roadDuration(r,plan);meters=r.distanceMeters;limits=r.edgeSpeedLimits??[];}
  if(points.length&&distance(points.at(-1)!,leg[0]!)>0.5)throw new Error('Road legs do not meet at the selected stop. Choose a stop closer to the road.');
  // Reject disconnected stop legs rather than drawing an invented connecting road.
  const merged=points.length&&distance(points.at(-1)!,leg[0]!)<0.1;if(points.length&&!merged){const connector=distance(points.at(-1)!,leg[0]!);edgeSpeedLimits.push(null);distanceM+=connector;durationSeconds+=connector/plan.speedMps;}edgeSpeedLimits.push(...(limits.length?limits:Array(leg.length-1).fill(null)));points.push(...(merged?leg.slice(1):leg));if(points.length>8192)throw new Error('The route is too complex. Use fewer or closer stops.');
  if(index<=stops.length)stopPointIndices.push(points.length-1);distanceM+=meters;durationSeconds+=duration;
 }
 return {points,distanceM,durationSeconds,routeSource:options.mode==='online'?'ONLINE_ROUTER':'OFFLINE_GEOPACK',...(options.mode==='online'?{onlineRoute:{profile:plan.mode as 'WALK'|'DRIVE',provider:'FOSSGIS OSRM',points,edgeSpeedLimits},stopPointIndices}:{}),description:`${stops.length} custom stop${stops.length===1?'':'s'} · stop durations included`};
}
async function routeCooldown(signal?:AbortSignal):Promise<void>{cancelled(signal);await new Promise<void>((resolve,reject)=>{const abort=()=>{clearTimeout(timer);reject(new DOMException('Cancelled','AbortError'));};const timer=setTimeout(()=>{signal?.removeEventListener('abort',abort);resolve();},1150);signal?.addEventListener('abort',abort,{once:true});});cancelled(signal);}
export async function previewJourney(plan:JourneyPlan,options:{mode:'online'|'offline';pack?:GeoPack;signal?:AbortSignal}):Promise<JourneyPreview>{cancelled(options.signal);if(['DRIVE','FLIGHT'].includes(plan.mode)&&plan.drivingSpeedMode==='VARIABLE'){const minimum=plan.driveMinMps??plan.speedMps,maximum=plan.driveMaxMps??plan.speedMps;if(!Number.isFinite(minimum)||!Number.isFinite(maximum)||minimum<0.1||maximum>56||minimum>maximum)throw new Error('Choose a valid cruising speed range.');plan={...plan,speedMps:(minimum+maximum)/2};}if(plan.mode==='INSTANT')return {points:[plan.destination],distanceM:0,durationSeconds:0,routeSource:'INSTANT_LOCATION'};
 if(plan.customPath){const points=[plan.origin,...(plan.stops??[]).map(s=>s.point),plan.destination],distanceM=points.slice(1).reduce((sum,p,i)=>sum+distance(points[i]!,p),0);return {points,distanceM,durationSeconds:distanceM/plan.speedMps+(plan.stops??[]).reduce((sum,s)=>sum+s.dwellSeconds,0),routeSource:'CUSTOM_PATH',description:'Custom straight-line path; does not follow roads.'};}
 if(plan.mode==='FLIGHT')return flight(plan,options);
 if(plan.stops?.length)return multiStopJourney(plan,options);
 if(options.mode==='offline'){if(!options.pack)throw new Error('Offline road routing needs routing data in addition to a map archive.');const {points,durationSeconds}=offlineJourney(options.pack,plan.origin,plan.destination,plan.mode,plan.speedMps,plan.followRoadLimits??true),distanceM=points.slice(1).reduce((sum,p,i)=>sum+distance(points[i]!,p),0);return {points,distanceM,durationSeconds,routeSource:'OFFLINE_GEOPACK'};}
 const route=await roadRoute(plan.origin,plan.destination,plan.mode,options.signal);return {points:route.points,distanceM:route.distanceMeters,durationSeconds:roadDuration(route,plan),routeSource:'ONLINE_ROUTER',onlineRoute:{profile:route.profile,provider:route.provider,points:route.points,...(route.edgeSpeedLimits===undefined?{}:{edgeSpeedLimits:route.edgeSpeedLimits})},snappedOrigin:route.points[0],snappedDestination:route.points.at(-1),description:`Road route · ${route.provider}. Playback duration estimates your selected cruising speed and available mapped limits; endpoints are snapped to the road.`};
}
let nextSearchAt=0;
export async function searchPlaces(query:string,options:{signal?:AbortSignal;provider?:'PHOTON'|'NOMINATIM'|'DISABLED'}={}):Promise<{name:string;lat:number;lon:number}[]>{cancelled(options.signal);const provider=options.provider??'PHOTON';if(provider==='DISABLED')return [];query=query.trim();if(query.length<2||query.length>256)throw new Error('Use a search between 2 and 256 characters.');const invoke=window.__TAURI__?.core.invoke;if(invoke){try{const places=await invoke<{name:string;lat:number;lon:number}[]>('search_places',{query,provider});cancelled(options.signal);return places;}catch{cancelled(options.signal);throw new Error('Search unavailable. Select a point on the map or try a different search provider.');}}
 if(Date.now()<nextSearchAt)throw new Error('Wait a moment before searching again.');nextSearchAt=Date.now()+1100;
 const url=provider==='PHOTON'?`https://photon.komoot.io/api/?q=${encodeURIComponent(query)}&limit=5`:`https://nominatim.openstreetmap.org/search?q=${encodeURIComponent(query)}&format=jsonv2&limit=5`;
 const response=await fetch(url,{signal:options.signal,credentials:'omit',referrerPolicy:'no-referrer'});if(!response.ok)throw new Error('Search service unavailable.');const raw=await response.json();cancelled(options.signal);
 const items=provider==='PHOTON'?raw.features?.map((f:{properties:{name?:string;city?:string;country?:string};geometry:{coordinates:number[]}})=>({name:[f.properties.name,f.properties.city,f.properties.country].filter(Boolean).join(', '),lat:f.geometry.coordinates[1],lon:f.geometry.coordinates[0]})):raw?.map((p:{display_name:string;lat:string;lon:string})=>({name:p.display_name,lat:Number(p.lat),lon:Number(p.lon)}));
 if(!Array.isArray(items))throw new Error('Search returned invalid results.');return items.slice(0,5).map(p=>({...point(p.lat,p.lon),name:String(p.name).slice(0,512)}));
}
