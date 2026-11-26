// Temporary, single-page server for opening the Toss reference check on a phone.
import { createServer } from 'node:http';
import { networkInterfaces } from 'node:os';
import { isIPv4 } from 'node:net';
import { randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';

const localAddresses = Object.entries(networkInterfaces()).flatMap(([name, addresses]) =>
  addresses.filter(a => a.family === 'IPv4' && !a.internal).map(a => ({ name, ip: a.address })));
const isPrivate = ip => isIPv4(ip) && /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(ip);
const usable = localAddresses.filter(a => isPrivate(a.ip));
const ip = process.argv[2] ?? (usable.find(a => a.name === 'en0') ?? usable[0])?.ip;
if (!usable.some(a => a.ip === ip)) throw Error('Mac과 휴대폰을 같은 Wi-Fi에 연결한 후 다시 실행하세요.');

const page = await readFile(new URL('../toss-reference-check.html', import.meta.url));
const route = '/check/' + randomBytes(12).toString('hex');
const server = createServer((req, res) => {
  const localHost = ip + ':' + server.address().port;
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'no-referrer');
  if (req.headers.host !== localHost || req.url !== route) {
    res.writeHead(404).end(); return;
  }
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { Allow: 'GET, HEAD' }).end(); return;
  }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(req.method === 'HEAD' ? undefined : page);
});
server.once('error', e => { console.error('테스트 페이지를 열 수 없습니다.', e.code); process.exitCode = 1; });
server.listen(0, ip, () => {
  console.log('휴대폰에서 열 주소: http://' + ip + ':' + server.address().port + route);
  console.log('같은 Wi-Fi 또는 핫스팟에서 1시간 동안 사용할 수 있습니다. Ctrl+C로 종료합니다.');
  setTimeout(() => { server.close(); server.closeAllConnections(); }, 60 * 60_000).unref();
});
