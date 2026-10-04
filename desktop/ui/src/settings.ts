import type {Mode} from './model.ts';
export type SpeedUnit='MPH'|'KMH'|'MPS';
export type DistanceUnit='MI'|'KM';
export type Preferences={speedUnit:SpeedUnit;distanceUnit:DistanceUnit;walkSpeedMps:number;driveSpeedMps:number;autoFitRoute:boolean;followPhone:boolean};
const factors:Record<SpeedUnit,number>={MPH:0.44704,KMH:1/3.6,MPS:1};
export const speedUnitLabel=(unit:SpeedUnit):string=>({MPH:'mph',KMH:'km/h',MPS:'m/s'})[unit];
export const defaultPreferences=():Preferences=>({speedUnit:'MPH',distanceUnit:'MI',walkSpeedMps:1.4,driveSpeedMps:13.9,autoFitRoute:true,followPhone:false});
export const maximumSpeed=(mode:Mode):number=>mode==='WALK'?3:56;
export function toDisplaySpeed(speedMps:number,unit:SpeedUnit):number{return speedMps/factors[unit];}
export function formatSpeedValue(speedMps:number,unit:SpeedUnit):string{return String(Number(toDisplaySpeed(speedMps,unit).toFixed(6)));}
export function parseDisplaySpeed(value:string,unit:SpeedUnit,mode:Mode):number{
  if(!Object.hasOwn(factors,unit))throw new Error('Choose mph, km/h, or m/s.');
  const speed=value.trim()===''?NaN:Number(value)*factors[unit],maximum=maximumSpeed(mode);
  if(!Number.isFinite(speed)||speed<0.1-factors[unit]*0.500001e-6||speed>maximum+factors[unit]*0.500001e-6)throw new Error(`Speed must be ${formatSpeedValue(0.1,unit)}–${formatSpeedValue(maximum,unit)} ${speedUnitLabel(unit)} for this mode.`);
  return Math.max(0.1,Math.min(maximum,speed));
}
export function distanceLabel(meters:number,unit:DistanceUnit):string{return `${(meters/(unit==='MI'?1609.344:1000)).toFixed(1)} ${unit==='MI'?'mi':'km'}`;}
