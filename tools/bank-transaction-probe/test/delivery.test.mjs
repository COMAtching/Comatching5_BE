import assert from 'node:assert/strict';
import test from 'node:test';
import { DepositDelivery, deliveryAlarm, depositPath, observationBatches, origins } from '../extension/deposit-delivery.mjs';
import { buildSnapshot } from '../extension/parser.mjs';
import { makeTable } from '../fixtures/data.mjs';

const token = 'x'.repeat(32);
function area() {
  const data = {};
  return {
    async get(keys) {
      const selected = Array.isArray(keys) ? keys : [keys];
      return structuredClone(Object.fromEntries(selected.filter(k => k in data).map(k => [k,data[k]])));
    },
    async set(values) { Object.assign(data, structuredClone(values)); },
    async remove(key) { delete data[key]; },
  };
}
function capture(count) {
  const table = makeTable(['거래일시','입금금액','출금금액','내통장표시내용','잔액'], [
    ...Array.from({length:count}, () => ['2026.11.24 10:15:00','1,000','0','가상입금','333,333']),
    ['2026.11.24 10:15:01','0','1,000','출금','332,333'],
  ]);
  return buildSnapshot({ table, sourceUrl:'https://obank1.kbstar.com/quics?page=C025255',
    accountAlias:'행사', bank:'kb', captureId:crypto.randomUUID(), capturedAt:new Date().toISOString() });
}
test('projection preserves separate identical deposits and omits debits, balance and page metadata', () => {
  const snapshot = capture(201);
  const batches = observationBatches(snapshot);
  assert.deepEqual(batches.map(b => b.length), [100,100,1]);
  const rows = batches.flat();
  assert.equal(new Set(rows.map(r => r.eventId)).size, 201);
  assert.ok(rows.every(row => row.amountKrw === 1000 && row.direction === 'CREDIT'));
  assert.ok(!('balanceKrw' in rows[0]) && !('source' in rows[0]));
  assert.throws(() => observationBatches({...snapshot,dataKind:'synthetic_demo'}));
});
test('response loss, worker restart, missing session token and partial receipt preserve the exact queue', async () => {
  const local = area(), session = area(), scheduled = new Set(), received = new Map();
  const alarms = { create: async id => scheduled.add(id), get:async id => scheduled.has(id), clear:async id => scheduled.delete(id) };
  let mode = 'offline';
  const send = async (url, options) => {
    assert.equal(url, origins[0] + depositPath);
    assert.equal(options.headers.Authorization,'Bearer ' + token);
    assert.equal(options.redirect,'error');
    const { observations } = JSON.parse(options.body);
    for (const row of observations) {
      if (received.has(row.eventId)) assert.deepEqual(received.get(row.eventId), row);
      received.set(row.eventId, row);
    }
    if (mode === 'conflict' && observations[0].eventId === snapshot.transactions[0].observationId) return {status:409};
    if (mode === 'offline') throw Error('lost response');
    const ids = observations.map(row => row.eventId);
    return { status:202, json:async () => ({ acceptedIds:mode === 'partial' ? ids.slice(1) : ids }) };
  };
  let delivery = new DepositDelivery({local,session},alarms,send);
  const snapshot = capture(201);
  await delivery.enqueue(snapshot);
  assert.equal((await local.get('depositQueue')).depositQueue,undefined);
  await delivery.configure(origins[0],token);
  await Promise.all([delivery.enqueue(snapshot),delivery.enqueue(snapshot)]);
  await delivery.drain();
  assert.equal(received.size,100);
  assert.equal((await local.get('depositQueue')).depositQueue.length,3);
  await delivery.pause();
  delivery = new DepositDelivery({local,session:area()},alarms,send);
  await assert.rejects(delivery.configure(origins[1],token));
  await delivery.configure(origins[0],token);
  await delivery.session.remove('depositCredential');
  scheduled.clear();
  await delivery.restore();
  assert.ok(scheduled.has(deliveryAlarm));
  await delivery.drain();
  assert.match((await local.get('deliveryIssue')).deliveryIssue,/토큰/);
  await delivery.configure(origins[0],token);
  mode = 'partial';
  await delivery.drain();
  assert.equal((await local.get('depositQueue')).depositQueue.length,3);
  mode = 'conflict';
  await delivery.drain();
  const {depositHeld,depositQueue} = await local.get(['depositHeld','depositQueue']);
  assert.deepEqual(Object.values(depositHeld),[409]);
  assert.equal(depositQueue.length,0);
  assert.equal((await local.get(Object.keys(depositHeld)))[Object.keys(depositHeld)[0]].length,100);
  await assert.rejects(delivery.configure(origins[1],token));
  mode = 'online';
  await delivery.retryHeld();
  await Promise.all([delivery.drain(),delivery.drain()]);
  assert.equal(received.size,201);
  assert.deepEqual((await local.get('depositQueue')).depositQueue,[]);
});

test('scan checkpoint advances only after a durable matching server receipt, including a lost response', async () => {
  const local=area(),session=area(), sent=[], pages=[capture(2)];
  const accountKey='a'.repeat(64),throughSecond='2026-11-24T10:15:00';
  let fail=true;
  const send=async(url,options)=>{
    const body=JSON.parse(options.body);
    if(url.endsWith('/scans')) {
      sent.push(body.scanId);
      if(fail) throw Error('lost');
      return {status:202,json:async()=>({scanId:body.scanId,checkpoint:body.throughSecond,issue:null})};
    }
    return {status:202,json:async()=>({acceptedIds:body.observations.map(r=>r.eventId)})};
  };
  const alarms={create:async()=>{},get:async()=>null};
  const d=new DepositDelivery({local,session},alarms,send);
  await d.configure(origins[0],token);
  await d.enqueueScan({pages,accountKey,fromSecond:'2026-11-24T00:00:00',throughSecond,
    manifestPages:[{number:1,captureId:pages[0].captureId,totalCredits:2,credits:2,ids:observationBatches(pages[0])[0].map(r=>r.eventId)}]},pages[0].capturedAt);
  await d.drain(); assert.equal(await d.checkpoint(accountKey),null);
  fail=false;
  const restored=new DepositDelivery({local,session},alarms,send);
  await restored.drain();
  assert.equal(await restored.checkpoint(accountKey),throughSecond);
  assert.equal(await restored.checkpoint('b'.repeat(64)),null);
  assert.equal(sent.length,2); assert.equal(sent[0],sent[1]);
});

test('default worker fetch sends health and a queued scan with the global receiver', async t => {
  const local=area(), session=area(), calls=[];
  t.mock.method(globalThis,'fetch',function(url, options) {
    if (this !== globalThis && this !== undefined) throw TypeError('Illegal invocation');
    assert.equal(options.headers.Authorization,'Bearer '+token);
    calls.push(url);
    const body=JSON.parse(options.body);
    if (url.endsWith('/health')) return Response.json({checkpoint:null});
    if (url.endsWith('/scans')) return Response.json({scanId:body.scanId,checkpoint:body.throughSecond},{status:202});
    return Response.json({acceptedIds:body.observations.map(row=>row.eventId)},{status:202});
  });
  const alarms={create:async()=>{}};
  const origin=origins[0], accountKey='a'.repeat(64), throughSecond='2026-11-24T10:15:00';
  const page=capture(2);
  const sender=new DepositDelivery({local,session},alarms);
  await sender.configure(origin,token);
  await sender.enqueueScan({pages:[page],accountKey,fromSecond:'2026-11-24T00:00:00',throughSecond,
    manifestPages:[{number:1,captureId:page.captureId,totalCredits:2,credits:2,ids:observationBatches(page)[0].map(r=>r.eventId)}]},page.capturedAt);
  // An update clears the session credential but leaves both existing queue entries in local storage.
  const freshSession=area();
  const resumed=new DepositDelivery({local,session:freshSession},alarms);
  await resumed.drain();
  assert.equal((await local.get('depositQueue')).depositQueue.length,2);
  await resumed.configure(origin,token);
  assert.deepEqual(await resumed.health(),{checkpoint:null});
  await resumed.drain();
  assert.deepEqual(calls,[origin+depositPath+'/health',origin+depositPath,origin+depositPath+'/scans']);
  assert.deepEqual((await local.get('depositQueue')).depositQueue,[]);
  assert.equal(await resumed.checkpoint(accountKey),throughSecond);
});
