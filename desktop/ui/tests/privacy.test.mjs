import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

test('UI keeps no history, analytics, logging, or HTML insertion sinks; remote requests stay in provider adapters',async()=>{
  const code=await readFile(new URL('../src/app.ts',import.meta.url),'utf8');
  assert.doesNotMatch(code,/\b(?:localStorage|sessionStorage|indexedDB|console|XMLHttpRequest|WebSocket)\b|innerHTML|outerHTML|document\.cookie|serviceWorker|sendBeacon|\bfetch\s*\(/);
  const html=await readFile(new URL('../index.html',import.meta.url),'utf8');
  assert.doesNotMatch(html,/<script[^>]*src=["'][^"']*https?:\/\//);
  assert.match(html,/form-action 'none'/);
  for(const input of html.matchAll(/<input\b([^>]+)>/g)){
    if(!/type="file"/.test(input[1]))assert.match(input[1],/autocomplete="off"/);
  }
});
test('connection loss and explicit stop both clear activity',async()=>{
  const code=await readFile(new URL('../src/app.ts',import.meta.url),'utf8');
  assert.match(code,/async function loseConnection\(\)[\s\S]*?discardActivity\(\)/);
  assert.match(code,/button\('stop'\)[\s\S]*?discardActivity\(\)/);
  assert.doesNotMatch(code,/input\('pairSecret'\)/);assert.match(code,/function clearPairingComparison\(\)/);assert.match(code,/invoke<PhoneResponse>\('pair_phone'\)/);assert.match(code,/button\('clearActivity'\)[\s\S]*?cancelPreview\(\)/);
});
