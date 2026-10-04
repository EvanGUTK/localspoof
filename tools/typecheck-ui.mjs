import { createRequire } from 'node:module';
import { resolve } from 'node:path';
const require = createRequire(import.meta.url);
let ts;
try { ts = process.env.LOCALSPOOF_TYPESCRIPT_PATH ? require(resolve(process.env.LOCALSPOOF_TYPESCRIPT_PATH)) : require('typescript'); }
catch { process.stderr.write('Install TypeScript 5.9+ for developer typechecking, or set LOCALSPOOF_TYPESCRIPT_PATH to its typescript.js.\n'); process.exit(1); }
const path = resolve('desktop/ui/tsconfig.json');
const config = ts.readConfigFile(path, ts.sys.readFile);
const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, resolve('desktop/ui'));
const program = ts.createProgram(parsed.fileNames, { ...parsed.options, noEmit: true });
const errors = [...(config.error ? [config.error] : []), ...parsed.errors, ...ts.getPreEmitDiagnostics(program)];
if (errors.length) {
  process.stderr.write(ts.formatDiagnosticsWithColorAndContext(errors, { getCanonicalFileName: path => path, getCurrentDirectory: ts.sys.getCurrentDirectory, getNewLine: () => '\n' }));
  process.exitCode = 1;
} else process.stdout.write(`Desktop TypeScript check passed (${ts.version}).\n`);
