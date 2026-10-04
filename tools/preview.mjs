import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { resolve, dirname, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../desktop/ui/dist');
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml' };
const server = createServer(async (req, res) => {
  try {
    if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405).end(); return; }
    const url = new URL(req.url, 'http://127.0.0.1');
    const decoded = decodeURIComponent(url.pathname);
    const path = resolve(root, '.' + (decoded === '/' ? '/index.html' : decoded));
    if (path !== root && !path.startsWith(root + sep)) { res.writeHead(403).end(); return; }
    const info = await stat(path);
    if (!info.isFile()) { res.writeHead(404).end(); return; }
    res.writeHead(200, { 'Content-Type': mime[extname(path)] ?? 'application/octet-stream', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
    res.end(req.method === 'HEAD' ? undefined : await readFile(path));
  } catch { res.writeHead(404).end(); }
});
server.listen(Number(process.env.LOCALSPOOF_PREVIEW_PORT ?? 4173), '127.0.0.1', () => {
  process.stdout.write('LocalSpoof UI preview: http://127.0.0.1:4173 (phone control requires native desktop build)\n');
});
