import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';

const fixture = `<osm version="0.6"><node id="1" lat="0" lon="0"/><node id="2" lat="0" lon="0.001"/><node id="3" lat="0.001" lon="0.001"/><way id="4"><nd ref="1"/><nd ref="2"/><nd ref="3"/><tag k="highway" v="residential"/><tag k="oneway" v="yes"/><tag k="maxspeed" v="30 mph"/><tag k="name" v="Synthetic Street"/></way></osm>`;

function build(xml) {
  const dir = mkdtempSync(join(tmpdir(), 'localspoof-pack-test-'));
  const input = join(dir, 'synthetic.osm');
  const output = join(dir, 'result.json');
  writeFileSync(input, xml);
  const result = spawnSync('python3', [resolve('tools/build-geopack.py'), input, output, '--id', 'synthetic', '--name', 'Synthetic grid'], { encoding: 'utf8' });
  const pack = result.status === 0 ? JSON.parse(readFileSync(output, 'utf8')) : null;
  rmSync(dir, { recursive: true });
  return { status: result.status, error: result.stderr, pack };
}

test('offline OSM converter preserves one-way driving and two-way walking', () => {
  const { status, pack } = build(fixture);
  assert.equal(status, 0);
  assert.equal(pack.nodes.length, 3);
  assert.equal(pack.edges.length, 4);
  const driving = pack.edges.filter(edge => edge.modes.includes('DRIVE'));
  const walking = pack.edges.filter(edge => edge.modes.includes('WALK'));
  assert.ok(driving.every(edge => !edge.bidirectional));
  assert.ok(walking.every(edge => edge.bidirectional));
  assert.ok(Math.abs(driving[0].speedLimitMps - 13.4112) < 1e-5);
});

test('private access cannot become a public road', () => {
  const result = build(fixture.replace('<tag k="highway"', '<tag k="access" v="private"/><tag k="highway"'));
  assert.notEqual(result.status, 0);
});

test('XML entity definitions and invalid geographic input fail closed', () => {
  assert.notEqual(build('<!DOCTYPE osm [<!ENTITY secret SYSTEM "file:///etc/passwd">]>' + fixture).status, 0);
  assert.notEqual(build(fixture.replace('lat="0"', 'lat="91"')).status, 0);
});

test('UTF-16 cannot bypass entity checks', () => {
  const malicious='<!DOCTYPE osm [<!ENTITY coordinate "0">]>' + fixture.replace('lat="0"', 'lat="&coordinate;"');
  const bytes=Buffer.concat([Buffer.from([0xff,0xfe]),Buffer.from(malicious,'utf16le')]);
  assert.notEqual(build(bytes).status, 0);
});

test('disconnected references do not invent routes', () => {
  assert.notEqual(build(fixture.replace('<node id="2" lat="0" lon="0.001"/>', '')).status, 0);
});

test('pack checksum covers exact file bytes', () => {
  const dir = mkdtempSync(join(tmpdir(), 'localspoof-hash-test-'));
  try {
    const input = join(dir, 'synthetic.osm'); const output = join(dir, 'pack.json');
    writeFileSync(input, fixture);
    assert.equal(spawnSync('python3', ['tools/build-geopack.py', input, output, '--id', 'synthetic', '--name', 'Synthetic grid']).status, 0);
    const sidecar = JSON.parse(readFileSync(output + '.manifest.json', 'utf8'));
    assert.equal(sidecar.sizeBytes, readFileSync(output).byteLength);
    assert.equal(sidecar.sha256.length, 64);
  } finally { rmSync(dir, { recursive: true }); }
});


test('posted numeric limits retain provenance while defaults and conditional limits are estimates', () => {
 const mapped=build(fixture).pack.edges.filter(e=>e.modes.includes('DRIVE'));
 assert.ok(mapped.every(e=>e.speedLimitSource==='MAPPED'));
 for(const xml of [fixture.replace('<tag k="maxspeed" v="30 mph"/>',''),fixture.replace('<tag k="maxspeed" v="30 mph"/>','<tag k="maxspeed" v="30 mph"/><tag k="maxspeed:conditional" v="20 mph @ (school hours)"/>')]) {
  const estimated=build(xml).pack.edges.filter(e=>e.modes.includes('DRIVE'));
  assert.ok(estimated.every(e=>e.speedLimitSource==='ESTIMATED'));
 }
});

test('direction-specific road limits never leak onto the opposite travel direction', () => {
 const xml=fixture.replace('<tag k="oneway" v="yes"/>','').replace('<tag k="maxspeed" v="30 mph"/>','<tag k="maxspeed:forward" v="30 mph"/><tag k="maxspeed:backward" v="50 mph"/>');
 const pack=build(xml).pack;
 const driving=pack.edges.filter(e=>e.modes.includes('DRIVE'));
 assert.ok(driving.every(e=>!e.bidirectional&&e.speedLimitSource==='MAPPED'));
 const forward=driving.find(e=>e.from==='1'&&e.to==='2'),backward=driving.find(e=>e.from==='2'&&e.to==='1');
 assert.ok(Math.abs(forward.speedLimitMps-13.4112)<1e-5);
 assert.ok(Math.abs(backward.speedLimitMps-22.352)<1e-5);
});
