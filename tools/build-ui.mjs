import { stripTypeScriptTypes } from 'node:module';
import { readdir, readFile, writeFile, mkdir, copyFile } from 'node:fs/promises';
import { resolve, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = resolve(root, 'desktop/ui');
const target = resolve(source, 'dist');

async function compileDirectory(from, to) {
  await mkdir(to, { recursive: true });
  for (const entry of await readdir(from, { withFileTypes: true })) {
    if (['dist', 'tests', 'node_modules'].includes(entry.name)) continue;
    const input = resolve(from, entry.name);
    const output = resolve(to, entry.name.endsWith('.ts') ? entry.name.slice(0, -3) + '.js' : entry.name);
    if (entry.isDirectory()) await compileDirectory(input, output);
    else if (entry.name.endsWith('.ts') && !entry.name.endsWith('.d.ts')) {
      const js = stripTypeScriptTypes(await readFile(input, 'utf8'), { mode: 'strip' });
      await writeFile(output, js.replace(/(from\s+['"][^'"]+)\.ts(['"])/g, '$1.js$2'));
    } else if (/\.(html|css|json|svg|png|webp|woff2|js|mjs|txt)$/.test(entry.name) && entry.name !== 'tsconfig.json') {
      if (entry.name.endsWith('.html')) {
        const html = await readFile(input, 'utf8');
        await writeFile(output, html.replace(/(src=['"][^'"]+)\.ts(['"])/g, '$1.js$2'));
      } else await copyFile(input, output);
    }
  }
}

await compileDirectory(source, target);
process.stdout.write(`Built local desktop assets: ${relative(root, target)}\n`);
