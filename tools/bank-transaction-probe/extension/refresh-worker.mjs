import { quickQueryControl, isQuickInquiry } from './quick-query.mjs';
import { collectPages, footerPageSnapshot } from './scan-pages.mjs';
import { extractRenderedTables } from './extractor.mjs';
import { buildSnapshot, inspectTable } from './parser.mjs';

import { DepositDelivery, deliveryAlarm } from './deposit-delivery.mjs';

const delivery = new DepositDelivery(chrome.storage, chrome.alarms);
const alarmName = 'kb-quick-inquiry';
const refreshMinutes = 3;
const session = chrome.storage.session;
let pending = Promise.resolve();
let refreshPending = false;
// Serialize refresh, stop and start events, including across popup closures.
function run(task) {
  const result = pending.then(task);
  pending = result.catch(() => {});
  return result;
}
const state = async () => (await session.get('watch')).watch;
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const koreaDay = () => new Date(Date.now() + 9 * 3600_000).toISOString().slice(0, 10);

async function scheduleRefresh(watch) {
  const when = Date.now() + refreshMinutes * 60_000;
  await chrome.alarms.create(alarmName, { when, periodInMinutes: refreshMinutes });
  await session.set({ watch: { ...watch, firstRefreshAt: when, periodInMinutes: refreshMinutes } });
}

async function stop(watch, reason) {
  await chrome.alarms.clear(alarmName);
  if (watch) await session.set({ watch: { ...watch, active: false, reason } });
}

async function checkedTab(watch) {
  const tab = await chrome.tabs.get(watch.tabId);
  if (!isQuickInquiry(tab.url) || new URL(tab.url).origin !== watch.origin || tab.discarded) {
    throw Error('대상 탭에서 KB 빠른조회 결과를 다시 열어 주세요.');
  }
  if (koreaDay() !== watch.day) throw Error('날짜가 바뀌었습니다. 조회 기간을 다시 선택해 주세요.');
}

async function control(watch, mode, id, ordinal = 1) {
  const [entry] = await chrome.scripting.executeScript({
    target: { tabId: watch.tabId, frameIds: [watch.frameId] },
    func: quickQueryControl, args: [mode, watch.origin, id, ordinal],
  });
  return entry;
}

async function start(msg) {
  if ((await state())?.active) throw Error('이미 자동 조회 중입니다.');
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab || tab.id !== msg.tabId || !isQuickInquiry(tab.url)) throw Error('KB 빠른조회 결과 탭에서 장부를 열어 주세요.');
  const watch = { tabId: tab.id, frameId: msg.frameId, origin: new URL(tab.url).origin, day: koreaDay(),
    active: true, accountAlias: msg.accountAlias, mapping: msg.mapping, headers: msg.headers, headerEnd: msg.headerEnd, count: 0 };
  const probe = await control(watch, 'check', '');
  if (!probe?.result || probe.result.error || probe.result.tableIndex !== msg.tableIndex) {
    throw Error(probe?.result?.error || '선택한 표가 KB 빠른조회 거래내역인지 확인해 주세요.');
  }
  await session.remove('latest');
  await scheduleRefresh(watch);
}

async function readPage(watch, entry, id) {
  const [capture] = await chrome.scripting.executeScript({
    target:{tabId:watch.tabId,documentIds:[entry.documentId]},func:extractRenderedTables,
  });
  const table=capture?.result?.tables.find(t=>t.tableIndex===entry.result.tableIndex);
  if (!table) throw Error('거래 결과표를 다시 확인하세요.');
  const trusted=inspectTable(table);
  if (trusted.headerEnd!==watch.headerEnd || ['date','debit','credit'].some(key=>
      trusted.mapping[key]===undefined || trusted.mapping[key]!==watch.mapping[key]) ||
      watch.mapping.amount!==undefined || watch.mapping.time!==trusted.mapping.time)
    throw Error('자동 조회의 거래일시·입금·출금 열을 KB 원본 제목과 맞춰 주세요.');
  const snapshot=buildSnapshot({table,sourceUrl:watch.origin+'/quics?page=C025255',bank:'kb',
    frameId:watch.frameId,accountAlias:watch.accountAlias,mapping:watch.mapping,headerEnd:watch.headerEnd,
    captureId:id,capturedAt:new Date().toISOString(),frameWarning:true});
  if(JSON.stringify(snapshot.source.headers)!==JSON.stringify(watch.headers)) throw Error('거래 표의 열 구성이 변경됐습니다.');
  return snapshot;
}

async function move(watch, mode, ordinal = 1) {
  const id=crypto.randomUUID(), click=await control(watch,mode,id,ordinal);
  if(!click?.result || click.result.error) throw Error(click?.result?.error || '조회 버튼을 확인하세요.');
  const deadline=Date.now()+15000;
  while(Date.now()<deadline) {
    await delay(500); await checkedTab(watch);
    const entry=await control(watch,'read',id).catch(()=>null);
    if(!entry?.result || entry.result.error || (!entry.result.settled && entry.documentId===click.documentId)) continue;
    return {entry,snapshot:await readPage(watch,entry,id)};
  }
  throw Error('15초 동안 결과표 갱신을 확인하지 못했습니다.');
}

async function withLayout(watch,page,ordinal = 1) {
  const meta=await control(watch,'layout','',ordinal);
  if(!meta?.result?.accountKey || meta.documentId!==page.entry.documentId || meta.result.tableIndex!==page.entry.result.tableIndex)
    throw Error(meta?.result?.error || '계좌와 페이지 범위를 확인하세요.');
  return {snapshot:page.snapshot,layout:meta.result};
}

async function tick(alarm) {
  const watch=await state();
  if(!watch?.active) { await stop(null); return; }
  if (alarm.scheduledTime < watch.firstRefreshAt) return;
  const startedAt=new Date().toISOString();
  try {
    await checkedTab(watch);
    const first=await move(watch,'click');
    const latest=first.snapshot;
    await session.set({latest});
    const settings=await chrome.storage.local.get('delivery');
    if(settings.delivery?.enabled) {
      const captured = new Map([[latest.captureId, latest]]);
      try {
        const initial=await withLayout(watch,first);
        let position=first, ordinal=1;
        const footer=initial.layout.navigation==='footer';
        const navigate=async number=>{
          const reset=footer && number===1;
          const page=await move(watch,reset ? 'click' : 'page:'+number,ordinal);
          captured.set(page.snapshot.captureId,page.snapshot);
          const result=await withLayout(watch,page,number);
          if (footer && !reset) {
            result.snapshot=footerPageSnapshot(position.snapshot,page.snapshot,page.entry.result.appended);
          }
          captured.set(result.snapshot.captureId,result.snapshot);
          position=page;
          ordinal=number;
          return result;
        };
        const reread=async()=>withLayout(watch,{
          entry:position.entry,snapshot:await readPage(watch,position.entry,crypto.randomUUID()),
        },ordinal);
        const localCursor=await delivery.checkpoint(initial.layout.accountKey);
        const report=await delivery.health().catch(()=>null);
        if (report?.accountKey && report.accountKey!==initial.layout.accountKey)
          throw Error('등록된 수취 계좌가 다릅니다.');
        const serverCursor=report?.accountKey===initial.layout.accountKey ? report.checkpoint : null;
        const collected=await collectPages(initial,serverCursor || localCursor,navigate,reread,startedAt,report?.activationFloor);

        await delivery.enqueueScan(collected,startedAt);
      } catch(error) {
        // Preserve the visible page for review; never issue a completion receipt on a gap.
        for (const snapshot of captured.values()) await delivery.enqueue(snapshot);
        await session.set({watch:{...watch,reason:error.message}});
        void delivery.health('COVERAGE_GAP').catch(()=>{});
        void delivery.drain();
        return;
      }
      void delivery.drain();
    }
    await session.set({watch:{...watch,reason:'',count:watch.count+1,lastAt:latest.capturedAt}});
  } catch(error) {
    void delivery.health(/quota|storage.*limit/i.test(error.message) ? 'STORAGE_FULL' : 'READ_FAILED').catch(()=>{});
    await stop(watch,error.message);
  }
}

async function restoreSchedule() {
  const watch = await state();
  if (!watch?.active) { await chrome.alarms.clear(alarmName); return; }
  const existing = await chrome.alarms.get(alarmName);
  if (existing?.periodInMinutes !== refreshMinutes || (watch.periodInMinutes ?? refreshMinutes) !== refreshMinutes) {
    await scheduleRefresh(watch);
  }
}

chrome.alarms.onAlarm.addListener(alarm => {
  if (alarm.name === deliveryAlarm) return delivery.drain();
  if (alarm.name !== alarmName || alarm.periodInMinutes !== refreshMinutes || refreshPending) return;
  refreshPending = true;
  return run(() => tick(alarm)).finally(() => { refreshPending = false; });
});
chrome.tabs.onRemoved.addListener(tabId => run(async () => {
  const watch = await state();
  if (watch?.active && watch.tabId === tabId) await stop(watch, '대상 탭이 닫혔습니다.');
}));
chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  if (sender.url !== chrome.runtime.getURL('popup.html')) return;
  if (['configureDelivery','pauseDelivery','sendSnapshot','retryHeld'].includes(msg?.type)) {
    const action = async () => {
      if (msg.type === 'pauseDelivery') return delivery.pause();
      if (msg.type === 'configureDelivery') await delivery.configure(msg.origin, msg.token);
      else if (msg.type === 'retryHeld') await delivery.retryHeld();
      else await delivery.enqueue(msg.snapshot, true);
      void delivery.drain();
    };
    action().then(() => reply({ok:true}), error => reply({error:error.message}));
    return true;
  }
  if (!['startRefresh','stopRefresh'].includes(msg?.type)) return;
  run(async () => {
    if (msg.type === 'startRefresh') await start(msg);
    else await stop(await state(), '운영자가 중지했습니다.');
  }).then(() => reply({ ok: true }), error => reply({ error: error.message }));
  return true;
});
run(restoreSchedule);

void delivery.restore();
