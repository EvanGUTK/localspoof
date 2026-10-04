import { readFile, readdir } from 'node:fs/promises';
import { dirname, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const forbidden = [
  [/\b(?:localStorage|sessionStorage|indexedDB)\b/, 'browser persistence'],
  [/\bconsole\.(?:log|warn|error|debug|info)\s*\(/, 'browser logs'],
  [/\bLog\.(?:v|d|i|w|e|wtf)\s*\(/, 'Android logs'],
  [/\b(?:println|eprintln)!\s*\(/, 'native runtime logs'],
  [/\b(?:SharedPreferences|SavedStateHandle|rememberSaveable|RoomDatabase)\b/, 'activity persistence'],
  [/\b(?:FirebaseAnalytics|Crashlytics|Sentry|Amplitude|AdMob)\b/, 'remote telemetry'],
];
const findings = [];
async function scan(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (['dist', 'target', 'build', 'tests', 'test', 'androidTest', 'node_modules'].includes(entry.name)) continue;
    const path = resolve(dir, entry.name);
    if (entry.isDirectory()) await scan(path);
    else if (/\.(kt|rs|ts|js|mjs|html)$/.test(path) && !path.endsWith('build.rs')) {
      const data = await readFile(path, 'utf8');
      for (const [pattern, reason] of forbidden) if (pattern.test(data)) findings.push(`${relative(root, path)}: ${reason}`);
    }
  }
}
for (const sub of ['android', 'desktop']) {
  try { await scan(resolve(root, sub)); } catch (error) { if (error.code !== 'ENOENT') throw error; }
}
try {
  const manifest = await readFile(resolve(root, 'android/app/src/main/AndroidManifest.xml'), 'utf8');
  // 0.6 explicitly permits map/search networking and user-selected local saves.
  // Background/boot collection and browser activity persistence remain forbidden.
  if (/android\.permission\.(?:ACCESS_BACKGROUND_LOCATION|RECEIVE_BOOT_COMPLETED)/.test(manifest)) findings.push('Android manifest: unexpected background/boot permission');
  if (!/allowBackup="false"/.test(manifest)) findings.push('Android manifest: backup must be explicitly disabled');
} catch (error) { if (error.code !== 'ENOENT') throw error; }
if (findings.length) { process.stderr.write(findings.join('\n') + '\n'); process.exitCode = 1; }
else process.stdout.write('Source privacy scan passed: no automatic activity persistence/logging; explicit native saved items and map/search network are authorized. Runtime filesystem/network/device audits still required.\n');
