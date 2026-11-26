import assert from 'node:assert/strict';
import test from 'node:test';
import { isQuickInquiry, quickQueryControl } from '../extension/quick-query.mjs';
import { extractRenderedTables } from '../extension/extractor.mjs';
import { makeTable } from '../fixtures/data.mjs';
import { inspectTable } from '../extension/parser.mjs';

const url = 'https://obank1.kbstar.com/quics?page=C025255';
test('automatic clicks are limited to the quick-inquiry menu', () => {
  for (const good of [url, url + '&cc=demo#CP', url.replace('obank1', 'obank')]) assert.ok(isQuickInquiry(good));
  for (const bad of [undefined, url.replace('https:', 'http:'), url.replace('C025255','C019414'),
    url.replace('obank1.kbstar.com','kbstar.com.example.org'), url.replace('/quics','/login')]) assert.equal(isQuickInquiry(bad), false);
});

test('3-minute bank polling restores old schedules, skips overlaps and stops on expiry', async t => {
  const tab = { id: 12, url };
  const store = {}, events = {};
  const local = {delivery:{enabled:true, origin:'http://127.0.0.1:9006'}};
  const listen = key => ({ addListener(fn) { events[key] = fn; } });
  let clicks = 0, scheduled, blocked = false, changedDocument = false;
  let now = Date.now();
  t.mock.method(Date, 'now', () => now);
  const table = makeTable(['거래일시','입금금액','출금금액'], [['2026.11.24 12:07:32','1,000','0']]);
  const { headers, mapping } = inspectTable(table);
  const origin = 'chrome-extension://test/';
  t.after(() => { delete globalThis.chrome; });
  globalThis.chrome = {
    tabs: { query: async () => [tab], get: async () => tab, onRemoved: listen('remove') },
    runtime: { getURL: f => origin + f, onMessage: listen('message') },
    storage: { local: { get: async () => structuredClone(local), set: async values => Object.assign(local, values) }, session: { get: async () => ({ ...store }), set: async data => Object.assign(store,data), remove: async k => { delete store[k]; } } },
    alarms: {
      onAlarm: listen('alarm'), get: async name => name === 'kb-deposit-delivery' ? {name} : scheduled, clear: async () => { scheduled = null; },
      create: async (name, {when, periodInMinutes}) => { scheduled = {name, scheduledTime:when, periodInMinutes}; },
    },
    scripting: { executeScript: async ({ target, func, args }) => {
      assert.equal(target.tabId, tab.id);
      if (func === extractRenderedTables) return [{ result: { tables: [table] } }];
      assert.equal(func, quickQueryControl);
      if (args[0] === 'layout' && !blocked) return [{documentId:changedDocument?'new-doc':'doc',result:{
        tableIndex:table.tableIndex, number:1,next:false,end:true,total:1,accountKey:'a'.repeat(64),startDate:'2026-01-01',endDate:'2099-01-01'}}];
      if (blocked) return [{ result: { error: '인증 필요' } }];
      if (args[0] === 'click') clicks++;
      return [{ documentId: changedDocument && args[0] === 'read' ? 'new-doc' : 'doc',
        result: { tableIndex: table.tableIndex, settled: !changedDocument } }];
    } },
  };
  await import('../extension/refresh-worker.mjs');
  const msg = body => new Promise(resolve => events.message(body,{url:origin+'popup.html'},resolve));
  const start = () => msg({ type:'startRefresh', tabId:tab.id, frameId:0, tableIndex:table.tableIndex,
    accountAlias:'테스트', headers:headers.map(h => h.join(' / ')), headerEnd:0, mapping });
  const advance = async ms => {
    now += ms;
    if (!scheduled || now < scheduled.scheduledTime) return;
    const due = { ...scheduled };
    scheduled.scheduledTime = now + due.periodInMinutes * 60_000;
    await events.alarm(due);
  };
  assert.ok((await start()).ok);
  assert.equal(scheduled.periodInMinutes, 3);
  assert.equal(scheduled.scheduledTime, now + 180_000);
  assert.ok((await start()).error);
  await advance(179_999);
  assert.equal(clicks, 0);
  const alarmBeforeRestart = scheduled;
  await import('../extension/refresh-worker.mjs?restarted');
  await new Promise(setImmediate);
  assert.equal(scheduled, alarmBeforeRestart);
  const duplicate = { ...scheduled };
  const first = advance(1);
  await events.alarm(duplicate);
  await first;
  assert.equal(clicks, 1);
  assert.equal(store.watch.count, 1);
  assert.equal(store.latest.totals.creditKrw, 1000);
  assert.equal(store.latest.canAutoApprove, false);
  assert.equal(local.depositQueue.length, 2);
  assert.equal(local[local.depositQueue[0]][0].eventId, store.latest.transactions[0].observationId);
  await advance(179_999);
  assert.equal(clicks, 1);
  await advance(1);
  assert.equal(clicks, 2);
  assert.equal(store.watch.count, 2);
  // Older Chrome versions can lose alarms. Restore one full interval after waking.
  scheduled = null;
  await import('../extension/refresh-worker.mjs?missingAlarm');
  await new Promise(setImmediate);
  assert.equal(scheduled.scheduledTime, now + 180_000);
  // Resume an active session saved by the 30-second rehearsal version.
  const oldLocalAlarm = {name:scheduled.name, scheduledTime:now+30_000, periodInMinutes:0.5};
  scheduled = oldLocalAlarm;
  Object.assign(store.watch,{periodInMinutes:0.5,firstRefreshAt:oldLocalAlarm.scheduledTime});
  await import('../extension/refresh-worker.mjs?upgrade');
  await new Promise(setImmediate);
  assert.equal(scheduled.periodInMinutes,3);
  assert.equal(store.watch.periodInMinutes,3);
  assert.equal(scheduled.scheduledTime,now+180_000);
  assert.equal(store.watch.firstRefreshAt,scheduled.scheduledTime);
  await events.alarm({...oldLocalAlarm,scheduledTime:scheduled.scheduledTime});
  assert.equal(clicks,2);
  changedDocument = true;
  await advance(180_000);
  assert.equal(store.watch.count,3);
  blocked = true;
  await advance(180_000);
  assert.equal(store.watch.active, false);
  assert.equal(store.watch.reason, '인증 필요');
  assert.equal(scheduled, null);
  assert.equal(clicks, 3);
  blocked = false;
  local.delivery.origin='https://srv.comatching.site';
  assert.ok((await start()).ok);
  assert.equal(scheduled.periodInMinutes,3);
  assert.equal(scheduled.scheduledTime,now+180_000);
  await events.alarm(duplicate); // A delayed event from the previous session.
  assert.equal(clicks, 3);
  assert.ok((await msg({type:'stopRefresh'})).ok);
  assert.equal(scheduled, null);
  await advance(180_000);
  assert.equal(clicks, 3);
});
