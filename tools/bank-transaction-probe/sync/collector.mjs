import { setTimeout as sleep } from 'node:timers/promises';

export const POLL_MS = 30_000;
export const RECEIVER_PATH = '/probe/synthetic-deposits';

export function receiverConfig(environment = process.env) {
  const url = new URL(environment.BANK_PROBE_URL ?? ('http://127.0.0.1:48766' + RECEIVER_PATH));
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  if (url.protocol !== 'https:' && !(loopback && url.protocol === 'http:')) throw Error('원격 수신 서버는 HTTPS가 필요합니다.');
  if (url.username || url.password || url.search || url.hash || url.pathname !== RECEIVER_PATH) throw Error('합성 입금 수신 URL을 확인하세요.');
  const token = environment.BANK_PROBE_TOKEN;
  if (!token || token.trim().length < 32) throw Error('BANK_PROBE_TOKEN에 32자 이상의 검증 전용 토큰을 지정하세요.');
  return { url: url.href, token };
}

export async function postPage(config, batch) {
  const response = await fetch(config.url, {
    method: 'POST', redirect: 'error', signal: AbortSignal.timeout(10_000),
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + config.token },
    body: JSON.stringify(batch),
  });
  if (!response.ok) throw Error('수신 서버 HTTP ' + response.status);
  const receipt = await response.json();
  if (receipt.streamId !== batch.streamId || !Array.isArray(receipt.sequences)
      || receipt.sequences.length !== batch.deposits.length
      || !batch.deposits.every((d, i) => receipt.sequences[i] === d.sequence)) {
    throw Error('응답과 전송한 거래번호가 일치하지 않습니다.');
  }
}

export async function collect(journal, config, send = postPage) {
  let after = journal.cursor(config.url);
  const through = journal.highWater();
  let scanned = 0, deposits = 0, requests = 0;
  while (after < through) {
    const page = journal.page(after, through);
    if (!page) throw Error('합성 원장의 마지막 거래를 확인할 수 없습니다.');
    if (page.deposits.length > 0) {
      await send(config, { dataKind: 'synthetic_demo', streamId: journal.streamId, deposits: page.deposits });
      requests++;
    }
    // Lost reply, timeout or partial receipt leaves this page available on restart.
    journal.advance(config.url, page.last);
    after = page.last;
    scanned += page.scanned;
    deposits += page.deposits.length;
  }
  return { scanned, deposits, requests, cursor: after };
}

export async function watch(journal, config, { signal, report = console.log } = {}) {
  journal.start(config.url);
  while (!signal?.aborted) {
    const start = performance.now();
    try {
      const result = await collect(journal, config);
      report({ at: new Date().toISOString(), ...result });
    } catch (error) {
      report({ at: new Date().toISOString(), error: error.message, cursor: journal.cursor(config.url) });
    }
    if (signal?.aborted) break;
    try { await sleep(Math.max(0, POLL_MS - (performance.now() - start)), undefined, { signal }); }
    catch (error) { if (!signal?.aborted) throw error; }
  }
}
