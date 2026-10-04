import test from 'node:test';
import assert from 'node:assert/strict';
import {decodeMotion,decodeSpeedControls,motionLabel,radioGuidance,ConfirmedMotion} from '../src/motion.ts';

const motion={speedMps:50,sampleAgeMs:100,sampleSequence:7,placementGeneration:1,roadLimitMps:25,roadLimitSource:'MAPPED',health:'LIVE'};
test('gauge accepts actual fresh telemetry, supports 125mph, and hides stale rather than displaying a target',()=>{
 assert.equal(decodeMotion(motion).speedMps,50);
 assert.match(motionLabel(decodeMotion(motion),'MPH',0),/^112 mph/);
 assert.match(motionLabel(decodeMotion(motion),'MPH',1600),/— mph.*delayed/i);
 assert.equal(decodeMotion({...motion,speedMps:NaN}),undefined);
 assert.equal(decodeMotion({...motion,sampleSequence:-1}),undefined);
 assert.equal(decodeMotion({...motion,roadLimitSource:'ESTIMATE'}),undefined);
 assert.equal(decodeSpeedControls({mode:'VARIABLE',driveMinMps:40.2336,driveMaxMps:55.88,followRoadLimits:true}).driveMaxMps,55.88);
 assert.equal(decodeSpeedControls({mode:'VARIABLE',driveMinMps:56,driveMaxMps:40,followRoadLimits:true}),undefined);
 assert.match(motionLabel({...motion,roadLimitSource:'UNKNOWN',roadLimitMps:null},'MPH',0),/limit unknown/i);
});
test('radio guidance is driven by real pending request and explicit wait, never guessed from distance',()=>{
 assert.match(radioGuidance('AIRPLANE_MODE',true),/Enable airplane mode/);
 assert.match(radioGuidance('RESTORE_CONNECTIVITY',false),/Turn airplane mode off/);
 assert.match(radioGuidance('RESTORE_CONNECTIVITY',true),/original.*enable airplane mode/i);
 assert.equal(radioGuidance('AIRPLANE_MODE',true,true),undefined);
 assert.equal(radioGuidance(null,undefined),undefined);
 assert.equal(radioGuidance('PAUSED',undefined),undefined);
});
test('confirmed map smoothing follows road bends, stops at last submitted point, and rejects older or duplicate sequences',()=>{
 const track=new ConfirmedMotion();const a={lat:0,lon:0},bend={lat:0,lon:0.001},b={lat:0.001,lon:0.001};
 track.accept(a,1,0,false,[a,bend,b]);track.accept(b,2,500,true,[a,bend,b]);
 assert.ok(Math.abs(track.at(750).lat-bend.lat)<1e-10&&Math.abs(track.at(750).lon-bend.lon)<1e-10);assert.deepEqual(track.at(1200),b);assert.deepEqual(track.at(5000),b);
 track.accept(a,2,1300,true,[a,bend,b]);assert.deepEqual(track.at(2000),b);
 track.accept(a,1,1400,true,[a,bend,b]);assert.deepEqual(track.at(2000),b);
 track.clear();assert.equal(track.at(3000),undefined);
});
test('smoothing crosses the date line safely and pause or deliberate large jumps settle immediately',()=>{
 const track=new ConfirmedMotion();track.accept({lat:0,lon:179.999},1,0,false);track.accept({lat:0,lon:-179.999},2,500,true);
 assert.ok(Math.abs(track.at(750).lon)>179.9);
 track.accept({lat:50,lon:20},3,1000,true);assert.deepEqual(track.at(1000),{lat:50,lon:20});
 track.accept({lat:50,lon:20.001},4,1500,false);assert.deepEqual(track.at(1500),{lat:50,lon:20.001});
});
test('pause cancels an in-progress interpolation even when the last submitted sequence repeats',()=>{
 const track=new ConfirmedMotion(),a={lat:0,lon:0},b={lat:0,lon:0.001};track.accept(a,1,0,false);track.accept(b,2,500,true);assert.notDeepEqual(track.at(600),b);track.accept(b,2,600,false);assert.deepEqual(track.at(600),b);
});
test('placement generations and journey phase changes snap even a nearby jump instead of inventing travel',()=>{
 const track=new ConfirmedMotion(),a={lat:0,lon:0},b={lat:0,lon:0.001},c={lat:0,lon:0.002};track.accept(a,1,0,false,[],'DRIVE:GROUND:1');track.accept(b,2,500,true,[],'DRIVE:GROUND:1');assert.notDeepEqual(track.at(600),b);track.accept(c,3,600,true,[],'DRIVE:GROUND:2');assert.deepEqual(track.at(600),c);track.accept(b,4,700,true,[],'FLIGHT:FLIGHT_INTERVAL:2');assert.deepEqual(track.at(700),b);
});
test('ambiguous nearby fixes cannot animate a long route loop or unbounded vertex sequence',()=>{
 const track=new ConfirmedMotion(),a={lat:0,lon:0},b={lat:0,lon:0.001},loop=[a,{lat:0.1,lon:0},{lat:0.1,lon:0.1},{lat:0,lon:0.1},b];track.accept(a,1,0,false,loop);track.accept(b,2,500,true,loop);assert.deepEqual(track.at(500),b);
 const dense=Array.from({length:300},(_,i)=>({lat:0,lon:i*0.000003}));track.clear();track.accept(dense[0],1,0,false,dense);track.accept(dense.at(-1),2,500,true,dense);assert.deepEqual(track.at(500),dense.at(-1));
});
