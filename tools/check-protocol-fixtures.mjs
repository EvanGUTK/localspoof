import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';

const root = new URL('../', import.meta.url);
const read = async path => readFile(new URL(path, root), 'utf8');
const fixture = JSON.parse(await read('protocol/fixtures/responses.json'));
const examples = fixture.examples;
assert.equal(fixture.protocolMajor, 1);
assert.equal(fixture.protocolMinor, 6);
assert.equal(examples.hello.payload.protocolMinor, 6);
assert.equal(examples.hello.payload.approvalPairing, true);
assert.equal(examples.hello.payload.livePhoneLocation, true);
assert.equal(examples.hello.payload.flightLegOptions, true);
assert.equal(examples.hello.payload.liveJourneyControls, true);
assert.equal(examples.hello.payload.routeStops, true);
for (const flag of ['motionTelemetry','variableCruise','mappedRoadLimits','remainingJourneyEdits','timedPause','vacationStops','vacationFuelStops','hotelStages','vacationCorrections']) assert.equal(examples.hello.payload[flag],true);
const schema = JSON.parse(await read('protocol/schema.json'));
assert.equal(schema.properties.protocolMinor.minimum, 6);
const pairId = new RegExp(schema.$defs.pairId.pattern);
const comparison = new RegExp(schema.$defs.comparisonCode.pattern);
assert.equal(schema.$defs.pairId.minLength, 32);
assert.equal(schema.$defs.pairId.maxLength, 32);
assert.equal(schema.$defs.comparisonCode.minLength, 6);
assert.equal(schema.$defs.comparisonCode.maxLength, 6);
const validId = value => value.length === 32 && pairId.test(value);
for (const vector of fixture.comparisonVectors) {
  assert.match(vector.clientNonce, pairId);
  assert.match(vector.pairingId, pairId);
  assert.match(vector.comparisonCode, comparison);
  const digest = createHash('sha256').update(`localspoof-pair-v2:${vector.clientNonce}:${vector.pairingId}`, 'utf8').digest();
  assert.equal(String(digest.readUInt32BE(0) % 1_000_000).padStart(6, '0'), vector.comparisonCode, 'Cross-language comparison vector is inconsistent');
}
assert.ok(fixture.comparisonVectors.some(vector => vector.comparisonCode.startsWith('0')), 'Include a leading-zero comparison vector');
for (const invalid of ['', 'a'.repeat(31), 'a'.repeat(33), 'A'.repeat(32), 'g'.repeat(32), 'a'.repeat(31) + '\n', 'a'.repeat(32) + '\n']) assert.equal(validId(invalid), false);
for (const kind of ['pairRequest', 'pollPair']) {
  assert.equal(schema.$defs[kind].additionalProperties, false);
  assert.ok(!('secret' in schema.$defs[kind].properties));
}
assert.deepEqual(schema.$defs.pairRequest.required, ['clientNonce']);
assert.deepEqual(schema.$defs.pollPair.required, ['pairingId', 'clientNonce']);
const activityFields = ['point', 'revision', 'state', 'phase', 'preparedPreview', 'geometry', 'route'];
for (const name of ['hello', 'pairRequested', 'pairPending', 'pairApproved']) {
  const response = examples[name];
  assert.equal(typeof response.ok, 'boolean');
  for (const sensitive of activityFields) {
    assert.ok(!(sensitive in response), `${name} discloses ${sensitive}`);
    assert.ok(!(sensitive in response.payload), `${name} payload discloses ${sensitive}`);
  }
}
function assertRendererPairing(response) {
  assert.equal(typeof response.ok, 'boolean');
  for (const [key, value] of Object.entries(response)) {
    assert.ok(['ok', 'error', 'payload'].includes(key), `Renderer pairing exposes ${key}`);
    if (key === 'payload') for (const field of Object.keys(value)) assert.ok(['pairingId', 'comparisonCode', 'pending', 'paired', 'usbAirplaneAvailable'].includes(field), `Renderer pairing exposes ${field}`);
  }
}
for (const response of Object.values(fixture.rendererExamples)) assertRendererPairing(response);
for (const field of ['token', 'clientNonce', 'secret', 'sessionId', ...activityFields]) {
  assert.throws(() => assertRendererPairing({ok:false,error:'PAIRING_REJECTED',payload:{[field]:'synthetic'}}));
  assert.throws(() => assertRendererPairing({ok:true,[field]:'synthetic'}));
}
assert.deepEqual(fixture.rendererExamples.pairApproved.payload, {paired:true,usbAirplaneAvailable:false});
function fixturePoint(value) {
  assert.deepEqual(Object.keys(value).sort(), ['lat', 'lon']);
  assert.ok(Number.isFinite(value.lat) && value.lat >= -90 && value.lat <= 90);
  assert.ok(Number.isFinite(value.lon) && value.lon >= -180 && value.lon <= 180);
}
function fixturePhoneLocation(value, state) {
  if (value === null) return;
  assert.ok(value && typeof value === 'object');
  for (const key of Object.keys(value)) assert.ok(['point', 'source', 'accuracyMeters', 'ageMs','speedMps','bearingDegrees','sampleSequence','placementGeneration'].includes(key));
  fixturePoint(value.point);
  assert.ok(['DEVICE', 'SIMULATION'].includes(value.source));
  assert.ok(Number.isInteger(value.ageMs) && value.ageMs >= 0 && value.ageMs <= 30000);
  if ('accuracyMeters' in value) assert.ok(Number.isFinite(value.accuracyMeters) && value.accuracyMeters > 0 && value.accuracyMeters <= 10000);
  if ('speedMps' in value) assert.ok(Number.isFinite(value.speedMps)&&value.speedMps>=0&&value.speedMps<=350&&value.source==='SIMULATION');
  if ('bearingDegrees' in value) assert.ok(Number.isFinite(value.bearingDegrees)&&value.bearingDegrees>=0&&value.bearingDegrees<360);
  if ('placementGeneration' in value) assert.ok(Number.isSafeInteger(value.placementGeneration)&&value.placementGeneration>=0);
  if ('sampleSequence' in value) assert.ok(Number.isSafeInteger(value.sampleSequence)&&value.sampleSequence>=0);
  if (['ACTIVE', 'PAUSED', 'HOLDING'].includes(state)) assert.equal(value.source, 'SIMULATION');
}
for (const name of ['snapshotReady', 'snapshotFlightAwait']) fixturePhoneLocation(examples[name].payload.phoneLocation, examples[name].state);
assert.equal(examples.snapshotReady.payload.phoneLocation.source, 'DEVICE', 'Prepared origin must not silently become a simulated device reading');
for (const invalid of [{...examples.snapshotReady.payload.phoneLocation, ageMs:30001}, {...examples.snapshotReady.payload.phoneLocation, ageMs:-1}, {...examples.snapshotReady.payload.phoneLocation, accuracyMeters:0}, {...examples.snapshotReady.payload.phoneLocation, accuracyMeters:10001}, {...examples.snapshotReady.payload.phoneLocation, accuracyMeters:Infinity}, {...examples.snapshotReady.payload.phoneLocation, source:'GPS'}, {...examples.snapshotReady.payload.phoneLocation, token:'synthetic'}]) assert.throws(() => fixturePhoneLocation(invalid, 'READY'));
assert.throws(() => fixturePhoneLocation(examples.snapshotReady.payload.phoneLocation, 'ACTIVE'));
fixturePhoneLocation(null, 'IDLE');
function fixtureFlight(preview) {
  assert.equal(preview.routeSource, 'FLIGHT_JOURNEY');
  assert.ok(['DRIVE', 'INSTANT'].includes(preview.departureGroundMode));
  assert.ok(['DRIVE', 'INSTANT'].includes(preview.arrivalGroundMode));
  assert.ok(['TIMED', 'JUMP'].includes(preview.flightTransitMode));
  assert.equal(preview.segments.length, 3);
  for (const [index, segment] of preview.segments.entries()) {
    assert.equal(segment.role, ['DEPARTURE', 'AIR', 'ARRIVAL'][index]);
    for (const key of Object.keys(segment)) assert.ok(['role', 'kind', 'points', 'durationMs', 'routeSource', 'routeProvider'].includes(key));
    assert.ok(Array.isArray(segment.points) && segment.points.length >= 2 && segment.points.length <= 4097);
    segment.points.forEach(fixturePoint);
    assert.ok(Number.isSafeInteger(segment.durationMs) && segment.durationMs >= 0);
    if (index === 1) {
      assert.equal(segment.kind, 'FLIGHT'); assert.equal(segment.routeSource, 'FLIGHT_JOURNEY');
      if (preview.flightTransitMode === 'JUMP') assert.equal(segment.durationMs, 0);
    } else {
      assert.equal(segment.kind, index === 0 ? preview.departureGroundMode : preview.arrivalGroundMode);
      if (segment.kind === 'INSTANT') { assert.equal(segment.durationMs, 0); assert.equal(segment.routeSource, 'INSTANT_LOCATION'); }
      else assert.ok(['ONLINE_ROUTER', 'OFFLINE_GEOPACK'].includes(segment.routeSource));
    }
    if (index) assert.deepEqual(preview.segments[index-1].points.at(-1), segment.points[0], 'Flight leg endpoints must agree');
  }
}
fixtureFlight(examples.preparedFlight.payload.preview);
for (const key of ['departureGroundMode', 'arrivalGroundMode', 'flightTransitMode', 'onlineFlightRoutes']) assert.ok(!(key in fixture.requestExamples.drive), 'Nonflight request contains flight-only fields');
assert.equal(fixture.requestExamples.flightInstantJump.routeSource, 'FLIGHT_JOURNEY');
assert.equal(fixture.requestExamples.flightInstantJump.flightTransitMode, 'JUMP');
assert.deepEqual(Object.keys(fixture.requestExamples.flightInstantJump.onlineFlightRoutes).sort(), ['arrivalAirportId', 'departureAirportId']);
for (const change of [preview => preview.segments.reverse(), preview => preview.segments[0].routeSource='ONLINE_ROUTER', preview => preview.segments[0].durationMs=1, preview => preview.segments[1].kind='DRIVE', preview => preview.segments[2].points[0].lat=NaN, preview => preview.segments[1].points[0].lon=3]) {
  const altered=structuredClone(examples.preparedFlight.payload.preview); change(altered); assert.throws(() => fixtureFlight(altered));
}
assert.equal(examples.pairPending.ok, false);
assert.equal(examples.pairPending.error, 'PAIRING_PENDING');
assert.equal(examples.pairApproved.payload.paired, true);
assert.match(examples.pairApproved.payload.token, /^[a-f0-9]{32}$/);
const wire = await read('android/app/src/main/kotlin/dev/localspoof/android/WireJson.kt');
const runtime = await read('android/app/src/main/kotlin/dev/localspoof/android/RuntimeModel.kt');
const preview = examples.prepared.payload.preview;
for (const field of Object.keys(preview)) assert.ok(wire.includes(`put("${field}"`), `Android preview does not emit ${field}`);
for (const field of Object.keys(examples.preparedFlight.payload.preview)) assert.ok(wire.includes(`put("${field}"`), `Android flight preview does not emit ${field}`);
for (const field of Object.keys(examples.snapshotReady.payload)) assert.ok(runtime.includes(`put("${field}"`), `Android snapshot does not emit ${field}`);
assert.equal(examples.snapshotFlightAwait.payload.radioRequest.enabled, true);
assert.equal(examples.snapshotFlightAwait.payload.remainingMs, null);
assert.ok(Array.isArray(preview.geometry));
assert.equal(preview.geometry.length, 2);
assert.ok(!['points', 'distanceM', 'durationSeconds', 'departureAirport', 'arrivalAirport'].some(key => key in preview));
const service = await read('android/app/src/main/kotlin/dev/localspoof/android/SimulationService.kt');
const activity = await read('android/app/src/main/kotlin/dev/localspoof/android/MainActivity.kt');
const stopMethod = service.slice(service.indexOf('internal fun stopFromPhone()'), service.indexOf('override fun onDestroy()'));
for (const operation of ['RuntimeModel.stop()', 'scope.cancel()', 'desktopControl.close()', 'stopForeground(STOP_FOREGROUND_REMOVE)', 'RuntimeModel.detach()', 'stopSelf()']) assert.ok(stopMethod.includes(operation), `Explicit service Stop is missing ${operation}`);
assert.ok(service.includes('DesktopControlHost(') && service.includes('RuntimeModel::enableDesktop') && service.includes('isServerHealthy =') && service.includes('activateServer =') && service.includes('.also { it.bind() }'), 'USB listener creation must use the idempotent service-owned host');
assert.ok(!service.includes('socket?.close()'), 'Enable must not revive the old unconditional socket-close pairing failure');
assert.ok(activity.includes('service.stopFromPhone()'));
assert.ok(activity.includes('finally { unbindIfNeeded() }'));
assert.ok(activity.includes('if (!phone.serviceRunning) unbindIfNeeded()'));
const notificationBlock = service.slice(service.indexOf('// Only notification publication uses the lifecycle lock'), service.indexOf('// Schedule from monotonic elapsed time'));
assert.ok(notificationBlock.includes('synchronized(lifecycleLock)') && notificationBlock.includes('if (!stopped)') && notificationBlock.includes('notifications.notify(NOTIFICATION, notification())'), 'A stopped service must not publish successor notifications');
assert.ok(service.includes('synchronized(lifecycleLock) { stopForeground(STOP_FOREGROUND_REMOVE) }'));
assert.ok(stopMethod.includes('if (!firstStop) return'));
assert.ok(service.includes('if (isStopped())'));
assert.ok(activity.includes('if (boundService?.isStopped() == true) unbindIfNeeded()'));
const mobileSession = await read('android/app/src/main/kotlin/dev/localspoof/android/MobileSessionState.kt');
const currentOrigin = activity.slice(activity.indexOf('MobileUiEvent.CurrentOrigin ->'), activity.indexOf('MobileUiEvent.ResetPreferences ->'));
assert.ok(currentOrigin.includes('model.invalidate()'), 'Using a current origin must discard local preview readiness');
assert.ok(currentOrigin.includes('originLon = it.lon.toString()'), 'Current origin longitude must be independent from latitude');
const selectedPlace = activity.slice(activity.indexOf('is MobileUiEvent.PlaceSelected ->'), activity.indexOf('MobileUiEvent.CurrentOrigin ->'));
assert.ok(selectedPlace.includes('model.invalidate()'), 'Selecting an imported place must discard local preview readiness');
assert.ok(selectedPlace.includes('destinationLon = event.point.lon.toString()') && selectedPlace.includes('placeQuery = ""'));
assert.ok(mobileSession.includes('draft = draft.copy(preparedId = null)'), 'Invalidating a draft must revoke its local prepared identifier');
assert.ok(activity.includes('RuntimeModel.prepare(request, revision)'), 'Background preparation must use a captured coordinator revision');
assert.ok(activity.includes('if (model.acceptsPreparation(token))'), 'Background results must verify draft and operation ownership before restoring readiness');
assert.ok(service.includes('RuntimeModel.tick(radioEnabled, ownedProvider)'));
assert.ok(service.includes('RuntimeModel.failOwner(ownedProvider, "DEVICE_SETTINGS_UNAVAILABLE")'));
const ticking = runtime.slice(runtime.indexOf('fun tick('), runtime.indexOf('fun failOwner('));
assert.ok(ticking.includes('if (!serviceRunning || provider !== expectedOwner) return@synchronized'));
assert.ok(ticking.indexOf('provider !== expectedOwner') < ticking.indexOf('lease.expire('));
const failingOwner = runtime.slice(runtime.indexOf('fun failOwner('), runtime.indexOf('fun stop()'));
assert.ok(failingOwner.indexOf('provider !== expectedOwner') < failingOwner.indexOf('coordinator.fail('));
assert.ok(failingOwner.indexOf('provider !== expectedOwner') < failingOwner.indexOf('expectedOwner.cleanup()'));
assert.ok(runtime.includes('stoppingSnapshot.mode == Mode.FLIGHT && activeRadioPolicy == "USB"'));
assert.ok(runtime.includes('Check airplane mode manually in Android settings; an in-flight USB command may still finish.'));
console.log('PASS: minor-6 controls, motion, approval vectors, authenticated phone freshness, typed flight segments, response privacy and Android field names agree. Fixture/source checks only; transport was not executed.');

assert.ok(schema.properties.type.enum.includes('SetSpeeds'));
assert.ok(schema.properties.type.enum.includes('JumpToNextStop'));
assert.equal(schema.$defs.plan.properties.stops.maxItems, 32);
assert.equal(schema.$defs.speeds.additionalProperties, false);
function validateSpeeds(value) {
  assert.ok(value && Object.keys(value).length >= 1);
  for (const [key, amount] of Object.entries(value)) {
    const rule=schema.$defs.speeds.properties[key]; assert.ok(rule);
    if(rule.enum) assert.ok(rule.enum.includes(amount));
    else if(rule.type==='boolean') assert.equal(typeof amount,'boolean');
    else assert.ok(Number.isFinite(amount) && amount>=rule.minimum && amount<=rule.maximum);
  }
  if('driveMinMps' in value && 'driveMaxMps' in value) assert.ok(value.driveMinMps<=value.driveMaxMps);
}
validateSpeeds(fixture.requestExamples.setSpeeds);
validateSpeeds(fixture.requestExamples.setDrivingControls);
for (const invalid of [{walkMps:3.1},{driveMps:56.1},{flightMps:350.1},{driveMps:NaN},{flightMps:29},{driveMps:20,token:'synthetic'},{}]) assert.throws(()=>validateSpeeds(invalid));
assert.deepEqual(fixture.requestExamples.driveWithStops.stopPointIndices,[1]);
assert.equal(fixture.requestExamples.driveWithStops.stops[0].dwellSeconds,20);
for (const name of ['snapshotReady','snapshotFlightAwait']) {
 const payload=examples[name].payload;
 validateSpeeds(payload.speeds);
 assert.ok(Number.isFinite(payload.remainingMeters)&&payload.remainingMeters>=0);
 fixturePoint(payload.nextStop.point);
 assert.ok(payload.waitingFor===null || ['PAUSED','PROVIDER_ACK','AIRPLANE_MODE','RESTORE_CONNECTIVITY','STOP_DWELL','SCHEDULING_GAP'].includes(payload.waitingFor));
}

for(const name of ['snapshotReady','snapshotFlightAwait']) {
 const motion=examples[name].payload.motion;
 assert.equal(schema.$defs.motion.additionalProperties,false);
 assert.equal(motion.speedMps===null||Number.isFinite(motion.speedMps)&&motion.speedMps>=0&&motion.speedMps<=350,true);
 assert.ok(schema.$defs.motion.properties.health.enum.includes(motion.health));
 if(motion.roadLimitSource==='UNKNOWN') assert.equal(motion.roadLimitMps,null);
 assert.equal(typeof examples[name].payload.speedControls.followRoadLimits,'boolean');
}
assert.equal(schema.$defs.speeds.properties.driveMps.maximum,56);
assert.equal(schema.$defs.geopack.properties.edges.items.properties.speedLimitSource.enum.includes('ESTIMATED'),true);
assert.ok(service.includes('const val TICK_MS = 200L'));
assert.ok(service.includes('wake.acquire(60_000L)'));
assert.ok(stopMethod.includes('playbackWakeLock = null'));

// New control fixtures describe authenticated, revision-bound payloads only.
// Runtime authority and cross-field feasibility remain covered by phone/native tests.
function strictFields(value, definition) {
 assert.ok(value && typeof value==='object' && !Array.isArray(value));
 assert.equal(definition.additionalProperties,false);
 for (const key of definition.required??[]) assert.ok(key in value,`Missing ${key}`);
 assert.ok(Object.keys(value).length >= (definition.minProperties??0));
 for (const [key,field] of Object.entries(value)) {
  let rule=definition.properties[key]; assert.ok(rule,`Unexpected ${key}`);
  if(rule.$ref) rule=schema.$defs[rule.$ref.split('/').at(-1)];
  if(rule.enum) assert.ok(rule.enum.includes(field));
  if(rule.type==='number') {
   assert.ok(Number.isFinite(field)); assert.ok(field>=rule.minimum&&field<=rule.maximum);
  }
  if(rule.type==='string') {
   assert.equal(typeof field,'string');
   if(rule.pattern) assert.match(field,new RegExp(rule.pattern));
   if(rule.minLength) assert.ok(field.length>=rule.minLength);
   if(rule.maxLength) assert.ok(field.length<=rule.maxLength);
  }
  if(rule.type==='boolean') assert.equal(typeof field,'boolean');
 }
}
for(const name of ['beginRemainingEdit','prepareRemainingPlan','applyRemainingPlan','cancelRemainingEdit','timedPause','leaveStop','updateVacation']) {
 strictFields(fixture.requestExamples[name],schema.$defs[name]);
 const command=name[0].toUpperCase()+name.slice(1);
 assert.ok(schema.properties.type.enum.includes(command));
 const server=await read('android/app/src/main/kotlin/dev/localspoof/android/PhoneControlServer.kt');
 const native=await read('desktop/native/src/bridge.rs');
 assert.ok(server.includes(`"${command}"`));assert.ok(native.includes(`"${command}"`));
 assert.throws(()=>strictFields({...fixture.requestExamples[name],token:'synthetic'},schema.$defs[name]));
}
for(const invalid of [{},{remainingFuelLiters:-1},{remainingFuelLiters:Infinity},{litersPer100Km:0},{reserveLiters:2001}]) assert.throws(()=>strictFields(invalid,schema.$defs.updateVacation));
for(const durationSeconds of [0,59,61,301,901]) assert.throws(()=>strictFields({durationSeconds},schema.$defs.timedPause));
strictFields(fixture.requestExamples.vacationDrive.vacation,schema.$defs.vacation);
strictFields(fixture.requestExamples.hotelStop.hotel,schema.$defs.hotelStop);
assert.equal(fixture.requestExamples.vacationDrive.vacation.startingFuelLiters,24,'Partial starting fuel must remain explicit');
assert.equal(fixture.requestExamples.hotelStop.hotel.checkInSeconds+fixture.requestExamples.hotelStop.hotel.staySeconds+fixture.requestExamples.hotelStop.hotel.checkOutSeconds,fixture.requestExamples.hotelStop.dwellSeconds);
assert.ok(!('remainingFuelLiters' in schema.$defs.vacation.properties),'Runtime fuel ledger cannot become saved plan authority');
assert.equal(schema.$defs.prepareRemainingPlan.properties.request.$ref,'#/$defs/remainingPlan');
assert.deepEqual(schema.$defs.remainingPreview.properties.segments.oneOf.map(rule=>rule.minItems),[3,2,1]);
