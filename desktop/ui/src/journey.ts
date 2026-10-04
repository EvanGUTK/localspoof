import {point,distance,longitudeDelta} from './model.ts';
import type {Point} from './model.ts';

export type PhoneLocation={point:Point;source:'DEVICE'|'SIMULATION';accuracyMeters?:number;ageMs:number;speedMps?:number;bearingDegrees?:number;sampleSequence?:number;placementGeneration?:number};
export type JourneySegment={role:'DEPARTURE'|'AIR'|'ARRIVAL';kind:'DRIVE'|'INSTANT'|'FLIGHT';points:Point[];durationMs:number;routeSource:'ONLINE_ROUTER'|'OFFLINE_GEOPACK'|'INSTANT_LOCATION'|'FLIGHT_JOURNEY';routeProvider?:string};
export function decodePhoneLocation(value:unknown,state:string,authenticated:boolean):PhoneLocation|undefined {
  if(!authenticated||value===null||typeof value!=='object'||Array.isArray(value))return;
  const v=value as Record<string,unknown>;
  if(v.source!=='DEVICE'&&v.source!=='SIMULATION')return;
  if(typeof v.ageMs!=='number'||!Number.isFinite(v.ageMs)||v.ageMs<0||v.ageMs>30000)return;
  if(v.accuracyMeters!==undefined&&(typeof v.accuracyMeters!=='number'||!Number.isFinite(v.accuracyMeters)||v.accuracyMeters<=0||v.accuracyMeters>10000))return;
  if(v.speedMps!==undefined&&(typeof v.speedMps!=='number'||!Number.isFinite(v.speedMps)||v.speedMps<0||v.speedMps>350)||v.bearingDegrees!==undefined&&(typeof v.bearingDegrees!=='number'||!Number.isFinite(v.bearingDegrees)||v.bearingDegrees<0||v.bearingDegrees>=360)||v.sampleSequence!==undefined&&(!Number.isSafeInteger(v.sampleSequence)||Number(v.sampleSequence)<0)||v.placementGeneration!==undefined&&(!Number.isSafeInteger(v.placementGeneration)||Number(v.placementGeneration)<0))return;
  if(v.source==='DEVICE'&&(v.ageMs>30000||['ACTIVE','PAUSED','HOLDING','ERROR'].includes(state)))return;
  if(v.source==='SIMULATION'&&!['ACTIVE','PAUSED','HOLDING','ERROR'].includes(state))return;
  try {
    const p=v.point as Record<string,unknown>;
    if(!p||typeof p.lat!=='number'||typeof p.lon!=='number')return;
    return {point:point(p.lat as number,p.lon as number),source:v.source,ageMs:v.ageMs,...(v.speedMps===undefined?{}:{speedMps:v.speedMps as number}),...(v.bearingDegrees===undefined?{}:{bearingDegrees:v.bearingDegrees as number}),...(v.sampleSequence===undefined?{}:{sampleSequence:v.sampleSequence as number}),...(v.placementGeneration===undefined?{}:{placementGeneration:v.placementGeneration as number}),...(v.accuracyMeters===undefined?{}:{accuracyMeters:v.accuracyMeters as number})};
  }catch{return;}
}
export function phoneLocationAt(location:PhoneLocation|undefined,receivedMs:number,nowMs:number):PhoneLocation|undefined {
  if(!location)return;
  const ageMs=location.ageMs+Math.max(0,nowMs-receivedMs);
  if(ageMs>30000)return;
  return {...location,ageMs};
}
export function phoneLocationLabel(location:PhoneLocation|undefined):string {
  if(!location)return 'Phone location unavailable. Unlock the phone and grant location access before simulation.';
  const source=location.source==='DEVICE'?'Device location':'Simulated location';
  return `${source} · ${Math.floor(location.ageMs/1000)} s old${location.accuracyMeters===undefined?'':` · accuracy ${Math.round(location.accuracyMeters)} m`}${location.ageMs>30000?' · last submitted sample':''}`;
}
export function decodeSegments(value:unknown,remaining=false):JourneySegment[] {
  if(value===undefined||value===null)return [];
  if(!Array.isArray(value)||!([3,...(remaining?[1,2]:[])].includes(value.length)))throw new Error('Phone returned invalid journey segments.');
  let total=0;const roles=value.length===3?['DEPARTURE','AIR','ARRIVAL']:value.length===2?['AIR','ARRIVAL']:['ARRIVAL'];
  const segments:JourneySegment[]=value.map((item,index)=>{
    if(!item||item.role!==roles[index]||!['DRIVE','INSTANT','FLIGHT'].includes(item.kind)||(item.role==='AIR')!==(item.kind==='FLIGHT'))throw new Error('Phone returned invalid journey segments.');
    if(!Array.isArray(item.points)||item.points.length<1||item.points.length>4097||(total+=item.points.length)>12291||typeof item.durationMs!=='number'||!Number.isFinite(item.durationMs)||item.durationMs<0||item.durationMs>Number.MAX_SAFE_INTEGER||(item.kind==='INSTANT'&&item.durationMs!==0))throw new Error('Phone returned invalid journey segments.');
    const points=item.points.map((p:Point)=>{if(!p||typeof p.lat!=='number'||typeof p.lon!=='number')throw new Error('Phone returned invalid segment coordinates.');return point(p.lat,p.lon);});
    if(item.kind!=='DRIVE'&&points.length!==2)throw new Error('Phone returned invalid journey segments.');
    const allowed=item.kind==='DRIVE'?['ONLINE_ROUTER','OFFLINE_GEOPACK']:item.kind==='INSTANT'?['INSTANT_LOCATION']:['FLIGHT_JOURNEY'];
    if(!allowed.includes(item.routeSource)||item.routeProvider!==undefined&&(item.routeSource!=='ONLINE_ROUTER'||typeof item.routeProvider!=='string'||!item.routeProvider||item.routeProvider.length>120))throw new Error('Phone returned invalid segment provenance.');
    return {role:item.role,kind:item.kind,points,durationMs:item.durationMs,routeSource:item.routeSource,...(item.routeProvider===undefined?{}:{routeProvider:item.routeProvider})};
  });
  for(let index=1;index<segments.length;index++)if(distance(segments[index-1]!.points.at(-1)!,segments[index]!.points[0]!)>0.1)throw new Error('Phone returned discontinuous journey segments.');
  return segments;
}
/** Each leg is a separate feature, so an instant placement never becomes a road. */
export function routeFeatures(route:Point[],segments:JourneySegment[]=[],zoom=12) {
  const lines=segments.length?segments:route.length>1?[{kind:'DRIVE',points:route}]:[];
  return {type:'FeatureCollection' as const,features:lines.filter(s=>s.points.length>1).map(s=>{
    let previous=s.points[0]!.lon;
    const path=s.kind==='FLIGHT'?flightPath(s.points[0]!,s.points.at(-1)!,zoom):s.points;
    const coordinates=path.map(p=>{let lon=p.lon;while(lon-previous>180)lon-=360;while(lon-previous< -180)lon+=360;previous=lon;return [lon,p.lat];});
    return {type:'Feature' as const,properties:{kind:s.kind},geometry:{type:'LineString' as const,coordinates}};
  })};
}
/** Same spherical interpolation as the phone engine; never animates the phone marker. */
export function sphericalPoint(a:Point,b:Point,fraction:number):Point {
 if(fraction<=0)return a;if(fraction>=1)return b;
 const radians=Math.PI/180,omega=distance(a,b)/6371008.8;
 if(omega<1e-10)return a;
 if(Math.abs(Math.sin(omega))<1e-8)return {lat:a.lat+(b.lat-a.lat)*fraction,lon:longitudeDelta(a.lon+longitudeDelta(b.lon,a.lon)*fraction,0)};
 const u=Math.sin((1-fraction)*omega)/Math.sin(omega),v=Math.sin(fraction*omega)/Math.sin(omega);
 const x=u*Math.cos(a.lat*radians)*Math.cos(a.lon*radians)+v*Math.cos(b.lat*radians)*Math.cos(b.lon*radians),y=u*Math.cos(a.lat*radians)*Math.sin(a.lon*radians)+v*Math.cos(b.lat*radians)*Math.sin(b.lon*radians),z=u*Math.sin(a.lat*radians)+v*Math.sin(b.lat*radians);
 return {lat:Math.atan2(z,Math.hypot(x,y))/radians,lon:longitudeDelta(Math.atan2(y,x)/radians,0)};
}
/** Display-only adaptive Mercator geometry; confirmed phone fixes are never snapped to it. */
export function flightPath(a:Point,b:Point,zoom=12):Point[]{
 const rad=Math.PI/180,projected=(p:Point)=>({x:p.lon/360,y:Math.log(Math.tan(Math.PI/4+Math.max(-85.051129,Math.min(85.051129,p.lat))*rad/2))/(2*Math.PI)});
 const tolerance=.5/(512*2**Math.max(0,Math.min(22,zoom))),output:Point[]=[a];let budget=8190;
 const visit=(start:Point,end:Point,t0:number,t1:number,depth:number):void=>{const middle=sphericalPoint(a,b,(t0+t1)/2),A=projected(start),B=projected(end),M=projected(middle);while(B.x-A.x>.5)B.x--;while(B.x-A.x<-.5)B.x++;while(M.x-A.x>.5)M.x--;while(M.x-A.x<-.5)M.x++;const dx=B.x-A.x,dy=B.y-A.y,f=Math.max(0,Math.min(1,((M.x-A.x)*dx+(M.y-A.y)*dy)/(dx*dx+dy*dy||1))),error=Math.hypot(M.x-A.x-dx*f,M.y-A.y-dy*f);
  if(depth<15&&budget>0&&(error>tolerance||t1-t0>1/64)){budget--;visit(start,middle,t0,(t0+t1)/2,depth+1);visit(middle,end,(t0+t1)/2,t1,depth+1);}else output.push(end);
 };visit(a,b,0,1,0);return output;
}
