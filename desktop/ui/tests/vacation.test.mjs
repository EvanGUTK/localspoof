import test from 'node:test';
import assert from 'node:assert/strict';
import {vacationInput,US_GALLON_LITERS,MPG_L100,fuelForMeters,tripBudget,decodeVacation,decodeTripStop,pointAlong} from '../src/vacation.ts';
const input={tankGallons:20,mpg:25,startMode:'PERCENT',starting:50,reservePercent:10,fuelGrade:'REGULAR',pricePerGallon:4};
test('starting percent, amount and remaining miles normalize the same partial tank; invalid level never silently becomes full',()=>{
 const percent=vacationInput(input),amount=vacationInput({...input,startMode:'GALLONS',starting:10}),range=vacationInput({...input,startMode:'MILES',starting:250});
 assert.deepEqual(percent,amount);assert.ok(Math.abs(percent.startingFuelLiters-range.startingFuelLiters)<1e-8);assert.equal(percent.startingFuelLiters,10*US_GALLON_LITERS);
 for(const starting of [101,NaN,-1])assert.throws(()=>vacationInput({...input,starting}));assert.throws(()=>vacationInput({...input,starting:5}),/reserve/);
});
test('US MPG, Imperial MPG and liters/100km preserve consumption and volume; currency remains manual',()=>{
 const us=vacationInput(input),metric=vacationInput({...input,tankGallons:20*US_GALLON_LITERS,tankUnit:'L',mpg:us.litersPer100Km,consumptionUnit:'L100KM',pricePerGallon:4/US_GALLON_LITERS,currency:'CAD'}),imperial=vacationInput({...input,mpg:25*282.480936/MPG_L100,consumptionUnit:'IMP_MPG'});
 assert.equal(metric.startingFuelLiters,us.startingFuelLiters);assert.ok(Math.abs(metric.litersPer100Km-imperial.litersPer100Km)<1e-8);assert.equal(metric.currency,'CAD');assert.throws(()=>vacationInput({...input,currency:'US'}),/Currency/);
});
test('route fuel budget distinguishes consumed value from refill purchases and never combines foreign currencies',()=>{
 const v=vacationInput(input),points=[{lat:0,lon:0},{lat:0,lon:1},{lat:0,lon:2}],stops=[{point:points[1],dwellSeconds:300,kind:'FUEL',fuel:{refillTargetLiters:v.tankLiters,currency:'USD'}}],preview={points,stopPointIndices:[1]};
 const budget=tripBudget(preview,stops,v);assert.equal(budget.reserveValid,true);assert.ok(budget.refillCost>budget.fuelCost);assert.ok(budget.consumedLiters>0);const foreign=tripBudget(preview,[{...stops[0],fuel:{...stops[0].fuel,currency:'CAD',pricePerLiter:2}}],v);assert.equal(foreign.refillCost,undefined);
 const hotel={point:points[1],kind:'HOTEL',dwellSeconds:60,hotel:{checkInSeconds:10,staySeconds:40,checkOutSeconds:10,nights:2,nightlyPrice:100,currency:'EUR'}};assert.equal(tripBudget(preview,[hotel],v).total,undefined);assert.equal(tripBudget(preview,[hotel],v).hotelCost,undefined);
});
test('unknown hotel fees keep a full trip total unknown; long hotels are explicit and malformed imported metadata is rejected',()=>{
 const stop={point:{lat:0,lon:1},dwellSeconds:43200,kind:'HOTEL',id:'hotel-1',hotel:{checkInSeconds:900,staySeconds:41400,checkOutSeconds:900,nights:1,nightlyPrice:100,currency:'USD'}};assert.deepEqual(decodeTripStop(stop),stop);assert.throws(()=>decodeTripStop({...stop,dwellSeconds:100}));assert.throws(()=>decodeTripStop({...stop,hotel:{...stop.hotel,token:'private'}}));assert.throws(()=>decodeVacation({...vacationInput(input),startingFuelLiters:1000}));
 const preview={points:[{lat:0,lon:0},stop.point],stopPointIndices:[1]};const budget=tripBudget(preview,[stop],vacationInput(input));assert.equal(budget.hotelCost,100);assert.equal(budget.total,undefined);
});
test('range calculations use route distance, include detours, and preserve date-line points',()=>{
 const v=vacationInput(input);assert.ok(fuelForMeters(100000,v)>fuelForMeters(90000,v));const midpoint=pointAlong([{lat:0,lon:179},{lat:0,lon:-179}],111195);assert.ok(Math.abs(Math.abs(midpoint.lon)-180)<.001);
});
test('fuel at route origin and co-located stops is counted before travel without blocking later stop processing',()=>{
 const v={tankLiters:40,startingFuelLiters:20,reserveLiters:5,litersPer100Km:10,fuelGrade:'REGULAR',currency:'USD',fuelPricePerLiter:1},points=[{lat:0,lon:0},{lat:0,lon:1},{lat:0,lon:2}],stops=[{point:points[0],kind:'FUEL',dwellSeconds:300,fuel:{refillTargetLiters:40,currency:'USD'}},{point:points[0],kind:'PLACE',dwellSeconds:1}];const budget=tripBudget({points,stopPointIndices:[0,0]},stops,v);assert.equal(budget.reserveValid,true);assert.equal(budget.refillCost,20);assert.ok(budget.remainingLiters>17);
});
