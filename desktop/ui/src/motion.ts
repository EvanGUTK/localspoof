import {distance,longitudeDelta} from './model.ts';
import type {Point} from './model.ts';
import {toDisplaySpeed,speedUnitLabel} from './settings.ts';
import type {SpeedUnit} from './settings.ts';

export type Motion={speedMps:number|null;sampleAgeMs:number|null;sampleSequence:number;placementGeneration:number;roadLimitMps:number|null;roadLimitSource:'MAPPED'|'UNKNOWN';health:'LIVE'|'STALE'|'PAUSED'|'WAITING'|'IDLE'|'ERROR'};
export type SpeedControls={mode:'FIXED'|'VARIABLE';driveMinMps:number;driveMaxMps:number;followRoadLimits:boolean};
const record=(value:unknown):Record<string,unknown>|undefined=>value!==null&&typeof value==='object'&&!Array.isArray(value)?value as Record<string,unknown>:undefined;
const bounded=(value:unknown,min:number,max:number):value is number=>typeof value==='number'&&Number.isFinite(value)&&value>=min&&value<=max;
export function decodeMotion(value:unknown):Motion|undefined{
 const v=record(value);if(!v||v.speedMps!==null&&!bounded(v.speedMps,0,350)||v.sampleAgeMs!==null&&!bounded(v.sampleAgeMs,0,Number.MAX_SAFE_INTEGER)||!Number.isSafeInteger(v.sampleSequence)||!bounded(v.sampleSequence,0,Number.MAX_SAFE_INTEGER)||!Number.isSafeInteger(v.placementGeneration)||!bounded(v.placementGeneration,0,Number.MAX_SAFE_INTEGER)||v.roadLimitMps!==null&&!bounded(v.roadLimitMps,0.1,100)||!['MAPPED','UNKNOWN'].includes(String(v.roadLimitSource))||!['LIVE','STALE','PAUSED','WAITING','IDLE','ERROR'].includes(String(v.health)))return;
 if(v.roadLimitSource==='UNKNOWN'&&v.roadLimitMps!==null||v.roadLimitSource==='MAPPED'&&v.roadLimitMps===null)return;
 return {...v} as Motion;
}
export function decodeSpeedControls(value:unknown):SpeedControls|undefined{
 const v=record(value);if(!v||!['FIXED','VARIABLE'].includes(String(v.mode))||!bounded(v.driveMinMps,0.1,56)||!bounded(v.driveMaxMps,0.1,56)||v.driveMinMps>v.driveMaxMps||typeof v.followRoadLimits!=='boolean')return;
 return {mode:v.mode as SpeedControls['mode'],driveMinMps:v.driveMinMps,driveMaxMps:v.driveMaxMps,followRoadLimits:v.followRoadLimits};
}
export function motionLabel(value:Motion|undefined,unit:SpeedUnit,elapsedMs:number):string{
 const label=speedUnitLabel(unit);if(!value)return `— ${label} · waiting for phone`;
 if(value.health==='ERROR')return `— ${label} · phone needs attention`;
 if(value.health==='IDLE')return `— ${label} · no active journey`;
 if(value.health==='STALE'||value.sampleAgeMs===null||value.sampleAgeMs+Math.max(0,elapsedMs)>1500||value.speedMps===null)return `— ${label} · location updates delayed`;
 const speed=Math.round(toDisplaySpeed(value.speedMps,unit)),limit=value.roadLimitMps===null?'limit unknown':`limit ${Math.round(toDisplaySpeed(value.roadLimitMps,unit))}`;
 return `${speed} ${label} · ${value.health==='PAUSED'?'paused':value.health==='WAITING'?'waiting':limit}`;
}
export function radioGuidance(waitingFor:string|null|undefined,desired:unknown,observed?:unknown):string|undefined{
 if(typeof desired!=='boolean'||typeof observed==='boolean'&&observed===desired)return;
 if(waitingFor==='AIRPLANE_MODE'&&desired)return 'Enable airplane mode on your phone now. The journey waits here until the phone confirms it.';
 if(waitingFor==='RESTORE_CONNECTIVITY')return desired?'Restore your original airplane-mode setting: enable airplane mode on your phone now.':'Turn airplane mode off on your phone now. The journey waits here until the phone confirms its original setting.';
 return;
}
function interpolate(a:Point,b:Point,fraction:number):Point{return {lat:a.lat+(b.lat-a.lat)*fraction,lon:longitudeDelta(a.lon+longitudeDelta(b.lon,a.lon)*fraction,0)};}
/** Animate only toward an already submitted fix. No velocity extrapolation or synthetic future position. */
export class ConfirmedMotion{
 private owner:string|undefined;private sequence=-1;private receivedMs=0;private startMs=0;private duration=0;private path:Point[]=[];private cumulative:number[]=[];
 clear():void{this.owner=undefined;this.sequence=-1;this.path=[];this.cumulative=[];this.duration=0;}
 accept(target:Point,sequence:number,nowMs:number,animate:boolean,road:Point[]=[],owner?:string):void{
  if(owner!==this.owner){this.clear();this.owner=owner;}
  if(sequence<=this.sequence){const last=this.path.at(-1);if(sequence===this.sequence&&!animate&&last&&last.lat===target.lat&&last.lon===target.lon){this.path=[target];this.cumulative=[0];this.duration=0;}return;}
  const from=this.at(nowMs),elapsed=nowMs-this.receivedMs;this.sequence=sequence;this.receivedMs=nowMs;this.startMs=nowMs;
  this.duration=animate&&from&&elapsed>0&&elapsed<=1500&&distance(from,target)<2000?Math.max(100,Math.min(elapsed,700)):0;
  this.path=from&&this.duration?confirmedPath(from,target,road):[target];this.cumulative=[0];
  for(let i=1;i<this.path.length;i++)this.cumulative.push(this.cumulative[i-1]!+distance(this.path[i-1]!,this.path[i]!));
 }
 at(nowMs:number):Point|undefined{
  const last=this.path.at(-1);if(!last)return;if(!this.duration||nowMs>=this.startMs+this.duration)return last;
  const meters=this.cumulative.at(-1)!*Math.max(0,(nowMs-this.startMs)/this.duration);
  for(let i=1;i<this.path.length;i++)if(meters<=this.cumulative[i]!)return interpolate(this.path[i-1]!,this.path[i]!,Math.max(0,Math.min(1,(meters-this.cumulative[i-1]!)/(this.cumulative[i]!-this.cumulative[i-1]!||1))));
  return last;
 }
}
function confirmedPath(a:Point,b:Point,road:Point[]):Point[]{
 if(road.length<2)return [a,b];
 const nearest=(p:Point)=>{let best={index:0,fraction:0,error:Infinity};for(let i=0;i<road.length-1;i++){const x=road[i]!,y=road[i+1]!,scale=Math.cos(p.lat*Math.PI/180),dx=longitudeDelta(y.lon,x.lon)*scale,dy=y.lat-x.lat,px=longitudeDelta(p.lon,x.lon)*scale,py=p.lat-x.lat;const fraction=Math.max(0,Math.min(1,(px*dx+py*dy)/(dx*dx+dy*dy||1))),error=distance(p,interpolate(x,y,fraction));if(error<best.error)best={index:i,fraction,error};}return best;};
 const first=nearest(a),last=nearest(b);if(first.error>30||last.error>30||last.index<first.index||last.index===first.index&&last.fraction<first.fraction)return [a,b];
 const span=[a,...road.slice(first.index+1,last.index+1),b];if(span.length>256)return [b];let length=0;for(let i=1;i<span.length;i++)length+=distance(span[i-1]!,span[i]!);return length>Math.max(100,distance(a,b)*3+20)?[b]:span;
}
