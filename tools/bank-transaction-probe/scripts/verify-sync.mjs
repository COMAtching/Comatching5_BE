import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { SyntheticJournal, PAGE_SIZE } from '../sync/journal.mjs';
import { receiverConfig, watch, collect, postPage, POLL_MS } from '../sync/collector.mjs';

const config = receiverConfig();
const dir = await mkdtemp(path.join(os.tmpdir(), 'comatching-synthetic-'));
const auth = { Authorization: 'Bearer ' + config.token };
const json = { ...auth, 'Content-Type': 'application/json' };

async function stored(streamId) {
  const deposits = [];
  let after = 0;
  while (true) {
    const response = await fetch(config.url + '/' + streamId + '?after=' + after, { headers: auth });
    assert.equal(response.status, 200);
    const rows = await response.json();
    deposits.push(...rows);
    if (rows.length < PAGE_SIZE) return deposits;
    after = rows.at(-1).sequence;
  }
}

function expectedNumbers(first, count) {
  return Array.from({ length: count }, (_, index) => first + index);
}

async function verifyRecovery() {
  const filename = path.join(dir, 'recovery.sqlite');
  let journal = new SyntheticJournal(filename);
  let dropNext = true;
  const proxy = createServer(async (req, res) => {
    try {
      const chunks = [];
      for await (const part of req) chunks.push(part);
      const upstream = await fetch(config.url, { method: 'POST', headers: json, body: Buffer.concat(chunks) });
      const body = await upstream.text();
      if (dropNext && upstream.ok) {
        dropNext = false;
        res.destroy(); // Spring has committed; the collector never receives the receipt.
      } else {
        res.writeHead(upstream.status, { 'Content-Type': 'application/json' });
        res.end(body);
      }
    } catch { res.destroy(); }
  });
  await new Promise((resolve, reject) => { proxy.once('error', reject); proxy.listen(0, '127.0.0.1', resolve); });
  const via = { ...config, url: 'http://127.0.0.1:' + proxy.address().port + '/probe/synthetic-deposits' };
  try {
    journal.start(via.url);
    journal.append(350);
    await assert.rejects(collect(journal, via));
    assert.equal(journal.cursor(via.url), 0);
    assert.equal((await stored(journal.streamId)).length, PAGE_SIZE);
    journal.close();
    journal = new SyntheticJournal(filename);
    journal.start(via.url);
    const recovered = await collect(journal, via);
    assert.equal(recovered.deposits, 350);
    const rows = await stored(journal.streamId);
    assert.deepEqual(rows.map(row => row.sequence), expectedNumbers(1, 350));

    // Two collectors replay the same batch concurrently.
    const repeat = { dataKind: 'synthetic_demo', streamId: journal.streamId, deposits: rows.slice(0, PAGE_SIZE) };
    await Promise.all([postPage(config, repeat), postPage(config, repeat)]);
    const conflict = { ...repeat, deposits: [{ ...rows[0], amountKrw: 999 }, { ...rows[0], sequence: 351 }] };
    const bad = await fetch(config.url, { method: 'POST', headers: json, body: JSON.stringify(conflict) });
    assert.equal(bad.status, 409);
    assert.equal((await stored(journal.streamId)).length, 350);
    for (const invalid of [{ ...repeat, dataKind: 'bank_page_observation' },
      { ...repeat, deposits: [{ ...rows[0], direction: 'DEBIT' }] }]) {
      const response = await fetch(config.url, { method: 'POST', headers: json, body: JSON.stringify(invalid) });
      assert.equal(response.status, 400);
    }
    const unauthorized = await fetch(config.url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(repeat) });
    assert.equal(unauthorized.status, 401);
    assert.equal((await stored(journal.streamId)).length, 350);
    console.log('PASS: lost TCP response after commit of 200 rows; reopened collector and recovered all 350; concurrent replay, conflict rollback and input checks OK.');
  } finally {
    journal.close();
    proxy.closeAllConnections();
    await new Promise(resolve => proxy.close(resolve));
  }
}

async function verifyTimer() {
  const filename = path.join(dir, 'timer.sqlite');
  const watcher = new SyntheticJournal(filename);
  const producer = new SyntheticJournal(filename);
  const ticks = [];
  const stop = new AbortController();
  const timeout = setTimeout(() => stop.abort(), POLL_MS * 4);
  try {
    producer.append(2); // Preexisting deposits before first subscription.
    await watch(watcher, config, { signal: stop.signal, report(entry) {
      ticks.push({ ...entry, observedAt: performance.now() });
      console.log(JSON.stringify(entry));
      if (entry.error || ticks.length === 3) stop.abort();
      if (ticks.length === 1 && !entry.error) {
        producer.append(2000);
        producer.append(73, '출금');
      }
    } });
    assert.deepEqual(ticks.map(t => [t.scanned, t.deposits, t.requests]),
      [[0, 0, 0], [2073, 2000, 10], [0, 0, 0]]);
    for (let i = 1; i < ticks.length; i++) {
      // Completion time includes the previous HTTP writes.
      assert.ok(ticks[i].observedAt - ticks[i-1].observedAt >= POLL_MS - 3000);
    }
    const rows = await stored(watcher.streamId);
    assert.deepEqual(rows.map(row => row.sequence), expectedNumbers(3, 2000));
    assert.equal(rows.reduce((total, row) => total + row.amountKrw, 0), 2_000_000);
    assert.equal(new Set(rows.map(row => JSON.stringify([row.occurredAt, row.amountKrw, row.description]))).size, 1);
    const next = producer.highWater() + 1;
    producer.append(210);
    // An arrival during pagination must be picked up on the following poll.
    let first = true;
    await collect(watcher, config, async (receiver, batch) => {
      await postPage(receiver, batch);
      if (first) { first = false; producer.append(1); }
    });
    assert.equal(watcher.cursor(config.url), next + 209);
    assert.equal((await collect(watcher, config)).deposits, 1);
    assert.deepEqual((await stored(watcher.streamId)).map(row => row.sequence),
      [...expectedNumbers(3, 2000), ...expectedNumbers(next, 211)]);
    console.log('PASS: actual 30s schedule; 2,000 identical credits (2,000,000 KRW), 73 debits excluded, idle poll sent 0 requests; 211 later credits reconciled by ID.');
  } finally {
    clearTimeout(timeout);
    producer.close();
    watcher.close();
  }
}

try {
  await verifyRecovery();
  await verifyTimer();
} finally {
  await rm(dir, { recursive: true, force: true });
}
