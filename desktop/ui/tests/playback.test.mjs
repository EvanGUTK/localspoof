import test from 'node:test';
import assert from 'node:assert/strict';
import {decodeProgress,etaLabel,durationLabel} from '../src/playback.ts';
const sample={speeds:{walkMps:1.4,driveMps:13.9,flightMps:230},remainingMs:60000,remainingMeters:1000,waitingFor:null,nextStop:{point:{lat:5,lon:6},label:'Departure airport',index:0}};
test('live ETA uses authenticated phone progress and identifies paused and radio gates',()=>{
 const progress=decodeProgress(sample);assert.equal(progress.nextStop.label,'Departure airport');assert.match(etaLabel(progress,new Date('2026-01-01T12:00:00')),/^1 min · arrives /);
 for(const [gate,label] of [['PAUSED','Paused'],['AIRPLANE_MODE','Waiting for airplane mode'],['RESTORE_CONNECTIVITY','Waiting for connectivity'],['PROVIDER_ACK','Waiting for location']])assert.equal(etaLabel({...progress,remainingMs:null,waitingFor:gate}),label);
 assert.equal(etaLabel({...progress,remainingMs:0}),'Arrived');assert.equal(durationLabel(90100),'2 min');assert.equal(durationLabel(3700000),'1 hr 2 min');
});
test('malformed ETA or next-stop values cannot enable live controls',()=>{
 for(const value of [{},{...sample,remainingMs:NaN},{...sample,remainingMeters:-1},{...sample,speeds:{...sample.speeds,flightMps:351}},{...sample,nextStop:{...sample.nextStop,point:{lat:91,lon:0}}},{...sample,nextStop:{...sample.nextStop,label:''}}])assert.equal(decodeProgress(value),undefined);
});
