export type LiveProgress={speeds:{walkMps:number;driveMps:number;flightMps:number};remainingMs:number|null;remainingMeters:number;waitingFor:string|null;nextStop?:{point:{lat:number;lon:number};label:string;index:number}};
export function decodeProgress(payload:Record<string,unknown>|undefined):LiveProgress|undefined{
 if(!payload)return;const speeds=payload.speeds as LiveProgress['speeds'];if(!speeds||!Number.isFinite(speeds.walkMps)||speeds.walkMps<0.1||speeds.walkMps>3||!Number.isFinite(speeds.driveMps)||speeds.driveMps<0.1||speeds.driveMps>56||!Number.isFinite(speeds.flightMps)||speeds.flightMps<30||speeds.flightMps>350)return;
 const remainingMs=payload.remainingMs,remainingMeters=payload.remainingMeters,waitingFor=payload.waitingFor;
 if(remainingMs!==null&&(typeof remainingMs!=='number'||!Number.isFinite(remainingMs)||remainingMs<0))return;
 if(typeof remainingMeters!=='number'||!Number.isFinite(remainingMeters)||remainingMeters<0||waitingFor!==null&&typeof waitingFor!=='string')return;
 const next=payload.nextStop as LiveProgress['nextStop'];
 if(next&&(!Number.isInteger(next.index)||next.index<0||typeof next.label!=='string'||!next.label||next.label.length>200||!Number.isFinite(next.point?.lat)||Math.abs(next.point.lat)>90||!Number.isFinite(next.point?.lon)||Math.abs(next.point.lon)>180))return;
 return {speeds,remainingMs:remainingMs as number|null,remainingMeters,waitingFor:waitingFor as string|null,...(next?{nextStop:next}:{})};
}
export function durationLabel(ms:number):string{const seconds=Math.ceil(ms/1000);if(seconds<60)return `${seconds} sec`;const minutes=Math.ceil(seconds/60);return minutes<60?`${minutes} min`:`${Math.floor(minutes/60)} hr ${minutes%60} min`;}
export function etaLabel(progress:Pick<LiveProgress,'remainingMs'|'waitingFor'>,now=new Date()):string{
 if(progress.waitingFor){const labels:Record<string,string>={PAUSED:'Paused',PROVIDER_ACK:'Waiting for location',AIRPLANE_MODE:'Waiting for airplane mode',RESTORE_CONNECTIVITY:'Waiting for connectivity',STOP_DWELL:'Stopped briefly',SCHEDULING_GAP:'Playback interrupted · resume on phone'};return labels[progress.waitingFor]??'Waiting on phone';}
 if(progress.remainingMs===null)return 'ETA unavailable';if(progress.remainingMs===0)return 'Arrived';const arrival=new Date(now.getTime()+progress.remainingMs);return `${durationLabel(progress.remainingMs)} · arrives ${arrival.toLocaleTimeString([], {hour:'numeric',minute:'2-digit'})}`;
}
