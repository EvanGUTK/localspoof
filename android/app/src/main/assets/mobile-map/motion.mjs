// Display-only motion between two acknowledged samples. No provider or command authority.
const distance=(a,b)=>Math.hypot((b.lat-a.lat)*111195,((((b.lon-a.lon)+180)%360+360)%360-180)*111195*Math.cos((a.lat+b.lat)*Math.PI/360));
const lerp=(a,b,t)=>({lat:a.lat+(b.lat-a.lat)*t,lon:(((a.lon+((((b.lon-a.lon)+180)%360+360)%360-180)*t)+180)%360+360)%360-180});
// Same spherical interpolation and antipodal fallback as the phone domain.
export function sphericalPoint(a,b,t){
 if(t<=0)return a;if(t>=1)return b;
 const rad=Math.PI/180,p1=a.lat*rad,p2=b.lat*rad,dl=((((b.lon-a.lon)+180)%360+360)%360-180)*rad;
 const h=Math.sin((p2-p1)/2)**2+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)**2;
 const omega=2*Math.asin(Math.min(1,Math.sqrt(h)));
 if(omega<1e-10)return a;if(Math.abs(Math.sin(omega))<1e-8)return lerp(a,b,t);
 const u=Math.sin((1-t)*omega)/Math.sin(omega),v=Math.sin(t*omega)/Math.sin(omega),l1=a.lon*rad,l2=b.lon*rad;
 const x=u*Math.cos(p1)*Math.cos(l1)+v*Math.cos(p2)*Math.cos(l2),y=u*Math.cos(p1)*Math.sin(l1)+v*Math.cos(p2)*Math.sin(l2),z=u*Math.sin(p1)+v*Math.sin(p2);
 return {lat:Math.atan2(z,Math.hypot(x,y))/rad,lon:(((Math.atan2(y,x)/rad)+180)%360+360)%360-180};
}
/** Adaptive Mercator tessellation keeps the spherical line within half a pixel at the current zoom. */
export function flightCoordinates(a,b,zoom=12){
 const rad=Math.PI/180,project=p=>({x:p.lon/360,y:Math.log(Math.tan(Math.PI/4+Math.max(-85.051129,Math.min(85.051129,p.lat))*rad/2))/(2*Math.PI)});
 const tolerance=.5/(512*2**Math.max(0,Math.min(22,zoom))),output=[[a.lon,a.lat]];let budget=8192;
 function visit(start,end,t0,t1,depth){const middle=sphericalPoint(a,b,(t0+t1)/2),A=project(start),B=project(end),M=project(middle);while(B.x-A.x>.5)B.x--;while(B.x-A.x<-.5)B.x++;while(M.x-A.x>.5)M.x--;while(M.x-A.x<-.5)M.x++;const dx=B.x-A.x,dy=B.y-A.y,f=Math.max(0,Math.min(1,((M.x-A.x)*dx+(M.y-A.y)*dy)/(dx*dx+dy*dy||1))),error=Math.hypot(M.x-A.x-dx*f,M.y-A.y-dy*f);
  if(depth<15&&budget>0&&(error>tolerance||t1-t0>1/64)){budget--;visit(start,middle,t0,(t0+t1)/2,depth+1);visit(middle,end,(t0+t1)/2,t1,depth+1);}else output.push([end.lon,end.lat]);
 }
 visit(a,b,0,1,0);return output;
}
const valid=p=>p&&Number.isFinite(p.lat)&&Number.isFinite(p.lon)&&Math.abs(p.lat)<=90&&Math.abs(p.lon)<=180;
function projected(point,path){let best=null,cumulative=0;for(let i=0;i<path.length-1;i++){const a=path[i],b=path[i+1],len=distance(a,b);if(len<.001)continue;const scale=Math.cos(point.lat*Math.PI/180),ax=((((a.lon-point.lon)+180)%360+360)%360-180)*scale,ay=a.lat-point.lat,dx=((((b.lon-a.lon)+180)%360+360)%360-180)*scale,dy=b.lat-a.lat;const t=Math.max(0,Math.min(1,-(ax*dx+ay*dy)/(dx*dx+dy*dy))),p=lerp(a,b,t),gap=distance(point,p);if(!best||gap<best.gap)best={index:i,progress:cumulative+len*t,gap};cumulative+=len;}return best;}
function roadPath(a,b,segments){let best=null;for(const segment of segments??[]){if(!['WALK','DRIVE'].includes(segment.kind)||segment.points?.length<2||segment.points.length>20000)continue;const start=projected(a,segment.points),end=projected(b,segment.points);if(!start||!end||start.gap>15||end.gap>15||end.progress<start.progress)continue;const points=[a,...segment.points.slice(start.index+1,end.index+1),b];if(points.length>1000)continue;const length=points.slice(1).reduce((sum,p,i)=>sum+distance(points[i],p),0);if(!best||start.gap+end.gap<best.gap)best={points,length,gap:start.gap+end.gap};}return best;}
function along(path,t){let remaining=path.length*t;for(let i=1;i<path.points.length;i++){const len=distance(path.points[i-1],path.points[i]);if(remaining<=len)return (path.spherical?sphericalPoint:lerp)(path.points[i-1],path.points[i],len?remaining/len:1);remaining-=len;}return path.points.at(-1);}
export function createMotionTracker({now,requestFrame,cancelFrame,draw}){
 let previous=null,target=null,animation=null,frame=0,disposed=false,visible=true,lastStale=null;
 const clearFrame=()=>{if(frame)cancelFrame(frame);frame=0;};
 function paint(point,stale){lastStale=stale;draw(point,stale);}
 function tick(){frame=0;if(disposed||!visible||!target)return;const clock=now(),stale=target.source==='SIMULATION'&&(target.health!=='LIVE'||clock-target.received+target.ageMs>1500);if(stale){animation=null;if(lastStale!==true)paint(target.point,true);return;}if(animation){const fraction=Math.max(0,Math.min(1,(clock-animation.started)/animation.duration));paint(fraction>=1?target.point:along(animation.path,fraction),false);if(fraction>=1)animation=null;}if(target.source==='SIMULATION')frame=requestFrame(tick);}
 function update(next,segments=[]){if(disposed)return;if(!valid(next.point)){clearFrame();previous=null;target=null;animation=null;paint(null,false);return;}
 if(target&&target.source==='SIMULATION'&&next.source==='SIMULATION'&&target.owner===next.owner&&(next.sequence<target.sequence||(next.sequence===target.sequence&&(next.point.lat!==target.point.lat||next.point.lon!==target.point.lon))))return;
 const clock=now(),fresh=next.source==='SIMULATION'&&next.health==='LIVE'&&Number.isFinite(next.ageMs)&&next.ageMs>=0&&next.ageMs<=1500;
 if(target&&next.sequence===target.sequence&&next.owner===target.owner&&next.point.lat===target.point.lat&&next.point.lon===target.point.lon&&next.health===target.health){target.ageMs=Math.max(target.ageMs+clock-target.received,next.ageMs??0);target.received=clock;if(target.source==='SIMULATION'&&target.ageMs>1500){clearFrame();animation=null;paint(target.point,true);}return;}
 clearFrame();previous=target;target={...next,ageMs:Number.isFinite(next.ageMs)?next.ageMs:Infinity,received:clock};animation=null;
 const elapsed=previous?clock-previous.received:0,stale=next.source==='SIMULATION'&&!fresh;
 const continuous=fresh&&previous&&previous.health==='LIVE'&&previous.source===next.source&&previous.owner===next.owner&&Number.isSafeInteger(next.sequence)&&next.sequence>previous.sequence&&elapsed>0&&elapsed<=1500&&previous.ageMs+elapsed<=1500&&distance(previous.point,next.point)<=Math.max(30,(next.speedMps??0)*elapsed/1000*2);
 if(continuous){const path=roadPath(previous.point,next.point,segments);if(path&&path.length<=Math.max(30,(next.speedMps??0)*elapsed/1000*2))animation={path,started:clock,duration:Math.min(300,elapsed)};else if(String(next.owner).split(':')[1]==='FLIGHT_INTERVAL'||!segments.some(s=>['WALK','DRIVE'].includes(s.kind)))animation={path:{points:[previous.point,next.point],length:distance(previous.point,next.point),spherical:String(next.owner).split(':')[1]==='FLIGHT_INTERVAL'},started:clock,duration:Math.min(300,elapsed)};}
 if(animation)paint(previous.point,false);else paint(next.point,stale);
 if(fresh&&visible)frame=requestFrame(tick);
 }
 return {update,setVisible(value){visible=!!value;clearFrame();animation=null;if(visible&&target){paint(target.point,target.source==='SIMULATION'&&(target.health!=='LIVE'||now()-target.received+target.ageMs>1500));if(target.source==='SIMULATION')frame=requestFrame(tick);}},dispose(){disposed=true;clearFrame();previous=null;target=null;animation=null;}};
}
