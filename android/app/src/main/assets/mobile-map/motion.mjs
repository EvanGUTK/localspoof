// Display-only motion between two acknowledged samples. No provider or command authority.
const distance=(a,b)=>Math.hypot((b.lat-a.lat)*111195,((((b.lon-a.lon)+180)%360+360)%360-180)*111195*Math.cos((a.lat+b.lat)*Math.PI/360));
const lerp=(a,b,t)=>({lat:a.lat+(b.lat-a.lat)*t,lon:(((a.lon+((((b.lon-a.lon)+180)%360+360)%360-180)*t)+180)%360+360)%360-180});
const valid=p=>p&&Number.isFinite(p.lat)&&Number.isFinite(p.lon)&&Math.abs(p.lat)<=90&&Math.abs(p.lon)<=180;
function projected(point,path){let best=null,cumulative=0;for(let i=0;i<path.length-1;i++){const a=path[i],b=path[i+1],len=distance(a,b);if(len<.001)continue;const scale=Math.cos(point.lat*Math.PI/180),ax=((((a.lon-point.lon)+180)%360+360)%360-180)*scale,ay=a.lat-point.lat,dx=((((b.lon-a.lon)+180)%360+360)%360-180)*scale,dy=b.lat-a.lat;const t=Math.max(0,Math.min(1,-(ax*dx+ay*dy)/(dx*dx+dy*dy))),p=lerp(a,b,t),gap=distance(point,p);if(!best||gap<best.gap)best={index:i,progress:cumulative+len*t,gap};cumulative+=len;}return best;}
function roadPath(a,b,segments){let best=null;for(const segment of segments??[]){if(!['WALK','DRIVE'].includes(segment.kind)||segment.points?.length<2||segment.points.length>20000)continue;const start=projected(a,segment.points),end=projected(b,segment.points);if(!start||!end||start.gap>15||end.gap>15||end.progress<start.progress)continue;const points=[a,...segment.points.slice(start.index+1,end.index+1),b];if(points.length>1000)continue;const length=points.slice(1).reduce((sum,p,i)=>sum+distance(points[i],p),0);if(!best||start.gap+end.gap<best.gap)best={points,length,gap:start.gap+end.gap};}return best;}
function along(path,t){let remaining=path.length*t;for(let i=1;i<path.points.length;i++){const len=distance(path.points[i-1],path.points[i]);if(remaining<=len)return lerp(path.points[i-1],path.points[i],len?remaining/len:1);remaining-=len;}return path.points.at(-1);}
export function createMotionTracker({now,requestFrame,cancelFrame,draw}){
 let previous=null,target=null,animation=null,frame=0,disposed=false,lastStale=null;
 const clearFrame=()=>{if(frame)cancelFrame(frame);frame=0;};
 function paint(point,stale){lastStale=stale;draw(point,stale);}
 function tick(){frame=0;if(disposed||!target)return;const clock=now(),stale=target.source==='SIMULATION'&&(target.health!=='LIVE'||clock-target.received+target.ageMs>1500);if(stale){animation=null;if(lastStale!==true)paint(target.point,true);return;}if(animation){const fraction=Math.max(0,Math.min(1,(clock-animation.started)/animation.duration));paint(fraction>=1?target.point:along(animation.path,fraction),false);if(fraction>=1)animation=null;}if(target.source==='SIMULATION')frame=requestFrame(tick);}
 function update(next,segments=[]){if(disposed)return;if(!valid(next.point)){clearFrame();previous=null;target=null;animation=null;paint(null,false);return;}
 if(target&&target.source==='SIMULATION'&&next.source==='SIMULATION'&&target.owner===next.owner&&(next.sequence<target.sequence||(next.sequence===target.sequence&&(next.point.lat!==target.point.lat||next.point.lon!==target.point.lon))))return;
 const clock=now(),fresh=next.source==='SIMULATION'&&next.health==='LIVE'&&Number.isFinite(next.ageMs)&&next.ageMs>=0&&next.ageMs<=1500;
 if(target&&next.sequence===target.sequence&&next.owner===target.owner&&next.point.lat===target.point.lat&&next.point.lon===target.point.lon&&next.health===target.health){target.ageMs=Math.max(target.ageMs+clock-target.received,next.ageMs??0);target.received=clock;if(target.source==='SIMULATION'&&target.ageMs>1500){clearFrame();animation=null;paint(target.point,true);}return;}
 clearFrame();previous=target;target={...next,ageMs:Number.isFinite(next.ageMs)?next.ageMs:Infinity,received:clock};animation=null;
 const elapsed=previous?clock-previous.received:0,stale=next.source==='SIMULATION'&&!fresh;
 const continuous=fresh&&previous&&previous.health==='LIVE'&&previous.source===next.source&&previous.owner===next.owner&&Number.isSafeInteger(next.sequence)&&next.sequence>previous.sequence&&elapsed>0&&elapsed<=1500&&previous.ageMs+elapsed<=1500&&distance(previous.point,next.point)<=Math.max(30,(next.speedMps??0)*elapsed/1000*2);
 if(continuous){const path=roadPath(previous.point,next.point,segments);if(path&&path.length<=Math.max(30,(next.speedMps??0)*elapsed/1000*2))animation={path,started:clock,duration:Math.min(300,elapsed)};else if(String(next.owner).split(':')[1]==='FLIGHT_INTERVAL'||!segments.some(s=>['WALK','DRIVE'].includes(s.kind)))animation={path:{points:[previous.point,next.point],length:distance(previous.point,next.point)},started:clock,duration:Math.min(300,elapsed)};}
 if(animation)paint(previous.point,false);else paint(next.point,stale);
 if(fresh)frame=requestFrame(tick);
 }
 return {update,dispose(){disposed=true;clearFrame();previous=null;target=null;animation=null;}};
}
