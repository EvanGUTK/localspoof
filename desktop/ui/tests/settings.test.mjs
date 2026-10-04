import test from 'node:test';
import assert from 'node:assert/strict';
import {defaultPreferences,parseDisplaySpeed,toDisplaySpeed,formatSpeedValue,distanceLabel} from '../src/settings.ts';
import {nativeErrorMessage} from '../src/connection-errors.ts';

test('speed units convert to canonical meters per second and preserve physical speed across repeated unit changes',()=>{
  assert.equal(parseDisplaySpeed('60','MPH','DRIVE'),26.8224);
  assert.equal(parseDisplaySpeed('36','KMH','DRIVE'),10);
  assert.equal(parseDisplaySpeed('10','MPS','DRIVE'),10);
  const physical=parseDisplaySpeed('3.1','MPH','WALK');
  for(let i=0;i<100;i++)for(const unit of ['MPH','KMH','MPS'])assert.ok(Math.abs(parseDisplaySpeed(String(toDisplaySpeed(physical,unit)),unit,'WALK')-physical)<1e-12);
  assert.equal(formatSpeedValue(10,'KMH'),'36');
});

test('mode limits apply after conversion and reject blank, malformed, or excessive speeds',()=>{
  for(const [value,unit,mode] of [['','MPH','DRIVE'],['Infinity','KMH','WALK'],['-1','MPS','DRIVE'],['7','MPH','WALK'],['126','MPH','DRIVE'],['0.01','KMH','WALK']])assert.throws(()=>parseDisplaySpeed(value,unit,mode));
  assert.equal(parseDisplaySpeed(String(toDisplaySpeed(3,'MPH')),'MPH','WALK'),3);
  assert.equal(parseDisplaySpeed('55','MPS','DRIVE'),55);
});

test('preferences use mph/miles by default and fresh defaults cannot retain earlier session changes',()=>{
  const prefs=defaultPreferences();assert.equal(prefs.speedUnit,'MPH');assert.equal(prefs.distanceUnit,'MI');
  prefs.walkSpeedMps=2;assert.equal(defaultPreferences().walkSpeedMps,1.4);
  assert.equal(distanceLabel(1609.344,'MI'),'1.0 mi');assert.equal(distanceLabel(1000,'KM'),'1.0 km');
});

test('known native failures have actionable sanitized explanations and unknown errors reveal no subprocess output',()=>{
  assert.match(nativeErrorMessage('ADB_UNAUTHORIZED'),/approve/i);
  assert.match(nativeErrorMessage('ADB_OFFLINE'),/reconnect/i);
  assert.match(nativeErrorMessage('PHONE_CONTROL_UNAVAILABLE'),/Enable USB desktop control/);
  assert.match(nativeErrorMessage('PHONE_PROTOCOL_MISMATCH'),/APK/i);
  assert.match(nativeErrorMessage('APK_SIGNATURE_MISMATCH'),/different signing key/i);assert.match(nativeErrorMessage('UntrustedApk'),/verified signed/i);assert.equal(nativeErrorMessage('AdbServerUnavailable'),nativeErrorMessage('ADB_SERVER_UNAVAILABLE'));
  const message=nativeErrorMessage('secret device serial and subprocess stderr');assert.doesNotMatch(message,/secret device serial|stderr/);
});


test('displayed minimum and maximum speeds remain valid in every unit without permitting meaningful excess',()=>{
  for(const unit of ['MPH','KMH','MPS'])for(const mode of ['WALK','DRIVE']){
    const max=mode==='WALK'?3:56;
    assert.ok(Math.abs(parseDisplaySpeed(formatSpeedValue(0.1,unit),unit,mode)-0.1)<0.000001);
    assert.ok(Math.abs(parseDisplaySpeed(formatSpeedValue(max,unit),unit,mode)-max)<0.000001);
    assert.throws(()=>parseDisplaySpeed(String(toDisplaySpeed(max+0.001,unit)),unit,mode));
  }
});
