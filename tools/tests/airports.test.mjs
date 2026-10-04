import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

const header = 'ident,type,name,latitude_deg,longitude_deg,scheduled_service,iata_code\n';
function withConversion(csv, callback) {
  const dir = mkdtempSync(join(tmpdir(), 'localspoof-airports-test-'));
  try {
    const input = join(dir, 'synthetic.csv');
    const output = join(dir, 'catalog.json');
    writeFileSync(input, csv);
    const run = () => spawnSync('python3', ['tools/convert-airports.py', input, output], { encoding: 'utf8' });
    callback({ run, output });
  } finally { rmSync(dir, { recursive: true }); }
}

test('airport conversion selects scheduled facilities and deduplicates catalog IDs', () => {
  withConversion(header + 'AAA,large_airport,Synthetic North,10,20,yes,SYN\nBBB,medium_airport,Duplicate,11,21,yes,SYN\nCCC,small_airport,Small,12,22,yes,SM\nDDD,large_airport,Unscheduled,13,23,no,NO\nEEE,medium_airport,Synthetic South,-10,-20,yes,\n', ({ run, output }) => {
    assert.equal(run().status, 0);
    const airports = JSON.parse(readFileSync(output, 'utf8'));
    assert.deepEqual(airports.map(airport => airport.id), ['SYN', 'EEE']);
    assert.ok(airports.every(airport => airport.scheduledService && !airport.closed));
  });
});

test('invalid geographic airport catalog cannot create an output', () => {
  withConversion(header + 'AAA,large_airport,Invalid,NaN,20,yes,SYN\nBBB,medium_airport,Outside,91,21,yes,OUT\n', ({ run, output }) => {
    assert.notEqual(run().status, 0);
    assert.equal(existsSync(output), false);
  });
});

test('airport converter refuses to overwrite existing resources', () => {
  withConversion(header + 'AAA,large_airport,Synthetic,10,20,yes,SYN\n', ({ run, output }) => {
    writeFileSync(output, 'existing static resource');
    assert.notEqual(run().status, 0);
    assert.equal(readFileSync(output, 'utf8'), 'existing static resource');
  });
});
