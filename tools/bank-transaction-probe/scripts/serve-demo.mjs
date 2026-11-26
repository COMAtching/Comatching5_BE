import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const mime = { '.html': 'text/html; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8' };
const server = createServer(async (req, res) => {
  try {
    if (req.method !== 'GET') { res.writeHead(405); res.end(); return; }
    const url = new URL(req.url, 'http://127.0.0.1');
    if (url.pathname === '/') { res.writeHead(302, { Location: '/extension/popup.html?demo=1' }); res.end(); return; }
    // Explicit public files only; never expose captured bank exports or the repo.
    const files = new Set(['extension/popup.html', 'extension/popup.mjs', 'extension/popup.css', 'extension/parser.mjs', 'extension/extractor.mjs', 'fixtures/bank.html', 'fixtures/data.mjs']);
    const filename = url.pathname.slice(1);
    if (!files.has(filename)) { res.writeHead(404); res.end('Not found'); return; }
    const body = await readFile(path.join(root, filename));
    res.writeHead(200, { 'Content-Type': mime[path.extname(filename)], 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' }); res.end(body);
  } catch { res.writeHead(500); res.end('Unable to load fixture'); }
});
server.on('error', (error) => { console.error(`Demo server: ${error.code}. Try another PROBE_DEMO_PORT or check local network permissions.`); process.exitCode = 1; });
server.listen(Number(process.env.PROBE_DEMO_PORT ?? 48765), '127.0.0.1', () => {
  console.log(`Synthetic demo: http://127.0.0.1:${server.address().port}/`);
  console.log('Ctrl+C to stop. No bank connection or upload.');
});
