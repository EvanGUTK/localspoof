import {distance,point} from './model.ts';
import type {Point,Plan} from './model.ts';
import {previewJourney,roadRoute,routeCooldown} from './geography.ts';
import type {JourneyPreview} from './geography.ts';
import {nearbyPlaces} from './places.ts';
export const US_GALLON_LITERS=3.785411784,MPG_L100=100*US_GALLON_LITERS/1.609344;
export type Vacation={tankLiters:number;startingFuelLiters:number;reserveLiters:number;litersPer100Km:number;fuelGrade:'REGULAR'|'MIDGRADE'|'PREMIUM'|'DIESEL';currency:string;fuelPricePerLiter?:number};
export type TripStop={point:Point;dwellSeconds:number;label?:string;id?:string;kind?:'PLACE'|'FUEL'|'HOTEL';fuel?:{refillTargetLiters:number;pricePerLiter?:number;currency?:string};hotel?:{checkInSeconds:number;staySeconds:number;checkOutSeconds:number;nights:number;nightlyPrice?:number;fees?:number;currency:string}};
export function vacationInput(input:{tankGallons:number;mpg:number;startMode:'PERCENT'|'GALLONS'|'MILES'|'KM';starting:number;reservePercent:number;fuelGrade:Vacation['fuelGrade'];pricePerGallon?:number;tankUnit?:'US_GAL'|'L';consumptionUnit?:'US_MPG'|'IMP_MPG'|'L100KM';currency?:string}):Vacation{
 const values=[input.tankGallons,input.mpg,input.starting,input.reservePercent];if(values.some(v=>!Number.isFinite(v))||input.tankGallons<=0||input.tankGallons>300||input.mpg<1||input.mpg>300||input.starting<0||input.reservePercent<0||input.reservePercent>=100||!['REGULAR','MIDGRADE','PREMIUM','DIESEL'].includes(input.fuelGrade))throw new Error('Enter valid tank size, MPG, starting fuel and reserve.');
 const amountFactor=input.tankUnit==='L'?1:US_GALLON_LITERS,litersPer100Km=input.consumptionUnit==='L100KM'?input.mpg:(input.consumptionUnit==='IMP_MPG'?282.480936:MPG_L100)/input.mpg;const tankLiters=input.tankGallons*amountFactor,startingFuelLiters=input.startMode==='PERCENT'?tankLiters*input.starting/100:input.startMode==='GALLONS'?input.starting*amountFactor:input.starting*(input.startMode==='KM'?1000:1609.344)/100000*litersPer100Km;
 if(startingFuelLiters>tankLiters+1e-8)throw new Error('Starting fuel or range exceeds tank capacity.');
 const reserveLiters=tankLiters*input.reservePercent/100;if(startingFuelLiters<reserveLiters)throw new Error('Starting fuel is below the selected reserve. Add a nearby refill or lower the reserve.');
 if(input.pricePerGallon!==undefined&&(!Number.isFinite(input.pricePerGallon)||input.pricePerGallon<0||input.pricePerGallon>1000))throw new Error('Enter an estimated fuel price or leave it unknown.');
 if(!/^[A-Z]{3}$/.test(input.currency??'USD'))throw new Error('Currency must be a three-letter code such as USD, CAD or EUR.');return {tankLiters,startingFuelLiters,reserveLiters,litersPer100Km,fuelGrade:input.fuelGrade,currency:input.currency??'USD',...(input.pricePerGallon===undefined?{}:{fuelPricePerLiter:input.pricePerGallon/amountFactor})};
}
export const fuelForMeters=(meters:number,v:Vacation)=>meters/100000*v.litersPer100Km;
export function pointAlong(points:Point[],meters:number):Point{
 if(!points.length)throw new Error('A road route is required.');let remaining=Math.max(0,meters);
 for(let i=1;i<points.length;i++){const a=points[i-1]!,b=points[i]!,length=distance(a,b);if(remaining<=length){const f=length?remaining/length:0;const delta=((b.lon-a.lon+540)%360)-180;return point(a.lat+(b.lat-a.lat)*f,((a.lon+delta*f+540)%360)-180);}remaining-=length;}return points.at(-1)!;
}
export function tripBudget(preview:JourneyPreview,stops:TripStop[],v:Vacation):{consumedLiters:number;fuelCost?:number;hotelCost?:number;total?:number;refillCost?:number;remainingLiters:number;reserveValid:boolean}{
 let fuel=v.startingFuelLiters,consumed=0,refillCost=0,refillKnown=true,cursor=0,reserveValid=true;const indices=preview.stopPointIndices??[];
 const visit=(index:number)=>{while(indices[cursor]===index){const stop=stops[cursor++];if(stop?.kind==='FUEL'&&stop.fuel){const amount=Math.max(0,stop.fuel.refillTargetLiters-fuel);const price=stop.fuel.currency&&stop.fuel.currency!==v.currency?undefined:stop.fuel.pricePerLiter??v.fuelPricePerLiter;if(price===undefined)refillKnown=false;else refillCost+=amount*price;fuel=Math.max(fuel,stop.fuel.refillTargetLiters);}}};
 visit(0);for(let i=1;i<preview.points.length;i++){const liters=fuelForMeters(distance(preview.points[i-1]!,preview.points[i]!),v);fuel-=liters;consumed+=liters;if(fuel<v.reserveLiters-1e-6)reserveValid=false;visit(i);}

 const hotels=stops.filter(s=>s.kind==='HOTEL');const hotelsKnown=hotels.every(s=>s.hotel?.nightlyPrice!==undefined&&s.hotel.currency===v.currency);const hotelCost=hotelsKnown?hotels.reduce((sum,s)=>sum+s.hotel!.nights*s.hotel!.nightlyPrice!+(s.hotel!.fees??0),0):undefined;const fuelCost=v.fuelPricePerLiter===undefined?undefined:consumed*v.fuelPricePerLiter;return {consumedLiters:consumed,remainingLiters:fuel,reserveValid,...(fuelCost===undefined?{}:{fuelCost}),...(hotelCost===undefined?{}:{hotelCost}),...(fuelCost===undefined||hotelCost===undefined||hotels.some(s=>s.hotel?.fees===undefined)?{}:{total:fuelCost+hotelCost}),...(refillKnown?{refillCost}:{})};
}
/** Road access is routed before a stop is accepted. Unknown grade availability stays explicit. */
export async function planFuelStops(plan:Plan&{stops?:TripStop[];vacation:Vacation},options:{signal?:AbortSignal;refillSeconds:number}):Promise<{plan:typeof plan;preview:JourneyPreview}>{
 if(plan.mode!=='DRIVE')throw new Error('Vacation fuel planning requires a driving road route.');
 if(!Number.isFinite(options.refillSeconds)||options.refillSeconds<1||options.refillSeconds>3600)throw new Error('Refill stop must be 1–3600 seconds.');const controller=new AbortController(),abort=()=>controller.abort();options.signal?.addEventListener('abort',abort,{once:true});if(options.signal?.aborted)abort();const deadline=setTimeout(abort,90000);const originalSignal=options.signal;options={...options,signal:controller.signal};try{const v=plan.vacation;let origin=plan.origin,fuel=v.startingFuelLiters;const stops:TripStop[]=[];const manual=(plan.stops??[]).filter(s=>!s.id?.startsWith('auto_fuel_'));const endpoints=[...manual,{point:plan.destination,dwellSeconds:0}];let requests=0;const bounded=()=>{if(options.signal?.aborted)throw new Error('Fuel planning canceled or exceeded its 90-second limit. Add manual stops or shorten the journey.');if(++requests>60)throw new Error('Fuel planning reached its request limit. Add manual stops or split the journey.');};
 for(let legIndex=0;legIndex<endpoints.length;legIndex++){const endpoint=endpoints[legIndex]!;
  for(let refill=0;;refill++){
   if(options.signal?.aborted)throw new DOMException('Cancelled','AbortError');if(stops.length>=32)throw new Error('Fuel planning reached its limit. Add a manual station or split this journey.');
   bounded();await routeCooldown(options.signal);const road=await roadRoute(origin,endpoint.point,'DRIVE',options.signal);const reachable=(fuel-v.reserveLiters)/v.litersPer100Km*100000;
   if(road.distanceMeters<=reachable){fuel-=fuelForMeters(road.distanceMeters,v);origin=endpoint.point;if(legIndex<manual.length){stops.push(endpoint);if(endpoint.kind==='FUEL'&&endpoint.fuel)fuel=Math.max(fuel,endpoint.fuel.refillTargetLiters);}break;}
   if(reachable<1000)throw new Error('Insufficient fuel to find a reachable station while preserving reserve. Add a nearby manual refill.');
   let selected:TripStop|undefined,spent=0;
   for(const fraction of [.8,.6,.4]){const center=pointAlong(road.points,reachable*fraction);bounded();const candidates=await nearbyPlaces(center,'FUEL',options.signal);
    for(const station of candidates){if(distance(origin,station)<100)continue;bounded();await routeCooldown(options.signal);let access;try{access=await roadRoute(origin,station,'DRIVE',options.signal);}catch{continue;}const amount=fuelForMeters(access.distanceMeters,v);if(amount>fuel-v.reserveLiters)continue;
     bounded();await routeCooldown(options.signal);let exit;try{exit=await roadRoute(station,endpoint.point,'DRIVE',options.signal);}catch{continue;}
     // Exclude stations that send the vehicle backwards or don't shorten the remaining road journey.
     if(exit.distanceMeters>=road.distanceMeters||access.distanceMeters+exit.distanceMeters>road.distanceMeters+20000)continue;
     selected={id:`auto_fuel_${crypto.randomUUID().replaceAll('-','')}`,point:station,dwellSeconds:options.refillSeconds,label:`${station.name} · verify ${v.fuelGrade.toLowerCase()} availability`,kind:'FUEL',fuel:{refillTargetLiters:v.tankLiters,...(v.fuelPricePerLiter===undefined?{}:{pricePerLiter:v.fuelPricePerLiter}),currency:v.currency}};spent=amount;break;
    }if(selected)break;
   }
   if(!selected)throw new Error('No reachable station with confirmed road access was found. Station coverage is incomplete; choose a manual fuel stop.');
   fuel-=spent;stops.push(selected);fuel=Math.max(fuel,v.tankLiters);origin=selected.point;
  }
 }
 bounded();if(requests+stops.length+1>60)throw new Error('The final multi-stop route exceeds the request budget. Add manual stops or split the journey.');const result={...plan,stops};await routeCooldown(options.signal);const preview=await previewJourney(result,{mode:'online',signal:options.signal});const budget=tripBudget(preview,stops,v);if(!budget.reserveValid)throw new Error('The final road route exceeds the fuel reserve. Choose a closer station or adjust starting fuel.');return {plan:result,preview};}finally{clearTimeout(deadline);originalSignal?.removeEventListener('abort',abort);}
}
export function decodeVacation(value:unknown):Vacation{
 if(!value||typeof value!=='object'||Array.isArray(value))throw new Error('Invalid saved vacation profile.');const v=value as Vacation;if(Object.keys(v).some(k=>!['tankLiters','startingFuelLiters','reserveLiters','litersPer100Km','fuelGrade','currency','fuelPricePerLiter'].includes(k))||!Number.isFinite(v.tankLiters)||v.tankLiters<.1||v.tankLiters>2000||!Number.isFinite(v.startingFuelLiters)||v.startingFuelLiters<0||v.startingFuelLiters>v.tankLiters||!Number.isFinite(v.reserveLiters)||v.reserveLiters<0||v.reserveLiters>v.tankLiters||!Number.isFinite(v.litersPer100Km)||v.litersPer100Km<.1||v.litersPer100Km>200||!['REGULAR','MIDGRADE','PREMIUM','DIESEL'].includes(v.fuelGrade)||!/^[A-Z]{3}$/.test(v.currency)||v.fuelPricePerLiter!==undefined&&(!Number.isFinite(v.fuelPricePerLiter)||v.fuelPricePerLiter<0||v.fuelPricePerLiter>1000))throw new Error('Invalid saved vacation profile.');return structuredClone(v);
}
export function decodeTripStop(value:unknown):TripStop{
 if(!value||typeof value!=='object'||Array.isArray(value))throw new Error('Invalid saved stop.');const s=value as TripStop;const numeric=(v:unknown,min:number,max:number)=>typeof v==='number'&&Number.isFinite(v)&&v>=min&&v<=max;const clean=(v:unknown,max:number)=>typeof v==='string'&&v.length<=max&&!/[\u0000-\u001f\u007f]/.test(v);
 if(Object.keys(s).some(k=>!['point','dwellSeconds','label','id','kind','fuel','hotel'].includes(k))||!s.point||Object.keys(s.point).sort().join(',')!=='lat,lon'||!numeric(s.dwellSeconds,0,2_764_800)||s.label!==undefined&&!clean(s.label,120)||s.id!==undefined&&(!clean(s.id,128)||!/^[A-Za-z0-9_-]+$/.test(s.id))||s.kind!==undefined&&!['PLACE','FUEL','HOTEL'].includes(s.kind))throw new Error('Invalid saved stop.');point(s.point.lat,s.point.lon);
 if(s.kind==='FUEL'){const f=s.fuel;if(!f||s.hotel!==undefined||Object.keys(f).some(k=>!['refillTargetLiters','pricePerLiter','currency'].includes(k))||!numeric(f.refillTargetLiters,.1,2000)||f.pricePerLiter!==undefined&&!numeric(f.pricePerLiter,0,1000)||f.currency!==undefined&&!/^[A-Z]{3}$/.test(f.currency)||s.dwellSeconds>3600)throw new Error('Invalid saved fuel stop.');}
 else if(s.kind==='HOTEL'){const h=s.hotel;if(!h||s.fuel!==undefined||Object.keys(h).some(k=>!['checkInSeconds','staySeconds','checkOutSeconds','nights','nightlyPrice','fees','currency'].includes(k))||!numeric(h.checkInSeconds,0,86400)||!numeric(h.staySeconds,0,2592000)||!numeric(h.checkOutSeconds,0,86400)||!numeric(h.nights,0,365)||!Number.isInteger(h.nights)||h.nightlyPrice!==undefined&&!numeric(h.nightlyPrice,0,1000000)||h.fees!==undefined&&!numeric(h.fees,0,1000000)||!/^[A-Z]{3}$/.test(h.currency)||Math.abs(s.dwellSeconds-h.checkInSeconds-h.staySeconds-h.checkOutSeconds)>1e-6)throw new Error('Invalid saved hotel stop.');}
 else if(s.fuel!==undefined||s.hotel!==undefined||s.dwellSeconds>3600)throw new Error('Invalid saved ordinary stop.');return structuredClone(s);
}
