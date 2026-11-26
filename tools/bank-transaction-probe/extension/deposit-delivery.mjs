// Chrome's worker is the sole writer of the durable queue. Store each captured page
// atomically with its queue index; only remove a batch after its exact receipt.
export const depositPath = '/api/v1/collector/deposit-observations';
export const deliveryAlarm = 'kb-deposit-delivery';
export const origins = ['http://127.0.0.1:9006', 'https://srv.comatching.site'];
const prefix = 'deposit-batch/';
const batchSize = 100;

export function observationBatches(snapshot) {
  if (snapshot?.dataKind !== 'bank_page_observation' || snapshot.account.bank !== 'kb') {
    throw Error('KB 실계좌 결과표에서 입금을 읽어 주세요.');
  }
  const rows = snapshot.transactions.filter(row => row.direction === 'CREDIT').map(row => ({
    eventId: row.observationId, accountAlias: snapshot.account.alias, capturedAt: snapshot.capturedAt,
    transactionDate: row.transactionDate, transactionTime: row.transactionTime, direction: 'CREDIT',
    amountKrw: row.amountKrw, counterparty: row.counterparty ?? null, description: row.description ?? null,
    bankTransactionId: row.bankTransactionId ?? null,
  }));
  const batches = [];
  for (let i = 0; i < rows.length; i += batchSize) batches.push(rows.slice(i, i + batchSize));
  return batches;
}

export async function postObservations(origin, token, rows, send = fetch) {
  const response = await send(origin + depositPath, {
    method: 'POST', credentials: 'omit', redirect: 'error', cache: 'no-store', signal: AbortSignal.timeout(10_000),
    headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
    body: JSON.stringify({ observations: rows }),
  });
  if (response.status !== 202) {
    const error = new Error('수신 서버 HTTP ' + response.status);
    error.status = response.status;
    throw error;
  }
  const { acceptedIds } = await response.json();
  if (!Array.isArray(acceptedIds) || acceptedIds.length !== rows.length ||
      !rows.every((row, i) => acceptedIds[i] === row.eventId)) throw Error('접수된 관측 ID가 일치하지 않습니다.');
}

export class DepositDelivery {
  constructor(storage, alarms, send = globalThis.fetch.bind(globalThis)) {
    this.local = storage.local;
    this.session = storage.session;
    this.alarms = alarms;
    this.send = send;
    this.pending = Promise.resolve();
    this.sending = null;
  }

  edit(task) {
    const result = this.pending.then(task);
    this.pending = result.catch(() => {});
    return result;
  }

  async restore() {
    const { delivery } = await this.local.get('delivery');
    if (delivery?.enabled && !await this.alarms.get(deliveryAlarm)) {
      await this.alarms.create(deliveryAlarm, { periodInMinutes: 1 });
    }
  }

  configure(origin, token) {
    return this.edit(async () => {
      if (!origins.includes(origin) || typeof token !== 'string' || !/^[!-~]{32,}$/.test(token)) {
        throw Error('수신 서버와 수집 전용 토큰(영문·숫자·기호 32자 이상)을 확인하세요.');
      }
      const { delivery, depositQueue = [], depositHeld = {} } = await this.local.get(['delivery','depositQueue','depositHeld']);
      if ((depositQueue.length || Object.keys(depositHeld).length) && delivery?.origin !== origin) throw Error('대기 중인 입금은 기존 서버로 먼저 전송하세요.');
      await this.session.set({ depositCredential: { origin, token } });
      await this.alarms.create(deliveryAlarm, { periodInMinutes: 1 });
      await this.local.set({ delivery: { enabled:true, origin }, deliveryIssue:'' });
    });
  }

  pause() {
    return this.edit(async () => {
      const { delivery } = await this.local.get('delivery');
      if (delivery) await this.local.set({ delivery: { ...delivery, enabled:false } });
      await this.session.remove('depositCredential');
      await this.alarms.clear(deliveryAlarm);
    });
  }

  enqueue(snapshot, required = false) {
    return this.edit(async () => {
      const { delivery, depositQueue = [] } = await this.local.get(['delivery','depositQueue']);
      if (!delivery?.enabled) {
        if (required) throw Error('수신 서버를 먼저 연결하세요.');
        return;
      }
      const batches = observationBatches(snapshot);
      if (!batches.length) return;
      const updates = {};
      const keys = batches.map(rows => prefix + rows[0].eventId);
      const existing = await this.local.get(keys);
      for (const [i, key] of keys.entries()) {
        if (existing[key]) {
          if (JSON.stringify(existing[key]) !== JSON.stringify(batches[i])) throw Error('같은 관측 ID의 전송 대기 내용이 다릅니다.');
        } else {
          updates[key] = batches[i];
          depositQueue.push(key);
        }
      }
      await this.local.set({ ...updates, depositQueue });
    });
  }

  async checkpoint(accountKey) {
    const {delivery,bankCheckpoint} = await this.local.get(['delivery','bankCheckpoint']);
    return bankCheckpoint?.origin === delivery?.origin && bankCheckpoint?.accountKey === accountKey ? bankCheckpoint.second : null;
  }

  async health(issue = null) {
    const {delivery,depositQueue=[],depositHeld={}} = await this.local.get(['delivery','depositQueue','depositHeld']);
    const {depositCredential: c} = await this.session.get('depositCredential');
    if (!delivery?.enabled || !c || c.origin!==delivery.origin || !origins.includes(c.origin)) return;
    const response=await this.send(c.origin+depositPath+'/health', {
      method:'POST',credentials:'omit',redirect:'error',signal:AbortSignal.timeout(5000),
      headers:{Authorization:'Bearer '+c.token,'Content-Type':'application/json'},
      body:JSON.stringify({queueDepth:depositQueue.length,issue:issue || (Object.keys(depositHeld).length?'TRANSFER_HELD':null)}),
    });
    if(!response.ok) throw Error('수집 상태 보고 HTTP '+response.status);
    return response.status===200 ? response.json() : null;
  }

  async enqueueScan(collected, startedAt) {
    // Serialize all observations and their completion marker as a single storage write.
    return this.edit(async () => {
      const {delivery,depositQueue=[]}=await this.local.get(['delivery','depositQueue']);
      if (!delivery?.enabled) return;
      const updates={};
      for (const page of collected.pages) {
        for (const rows of observationBatches(page)) {
          const key=prefix+rows[0].eventId;
          updates[key]=rows;
          depositQueue.push(key);
        }
      }
      const scanId=crypto.randomUUID();
      const key='deposit-scan/'+scanId;
      updates[key]={scanId,accountKey:collected.accountKey,startedAt,finishedAt:new Date().toISOString(),
        fromSecond:collected.fromSecond,throughSecond:collected.throughSecond,pages:collected.manifestPages};
      depositQueue.push(key);
      await this.local.set({...updates,depositQueue});
    });
  }

  retryHeld() {
    return this.edit(async () => {
      const {depositQueue = [], depositHeld = {}} = await this.local.get(['depositQueue','depositHeld']);
      await this.local.set({depositQueue:[...new Set([...depositQueue,...Object.keys(depositHeld)])], depositHeld:{},scanRetries:{}});
    });
  }

  drain() {
    if (!this.sending) this.sending = this.sendPending()
      .catch(error => this.local.set({ deliveryIssue: error.message || '서버 연결 실패' }))
      .finally(() => { this.sending = null; });
    return this.sending;
  }

  async sendPending() {
    for (let i = 0; i < 10; i++) {
      const { delivery, depositQueue = [] } = await this.local.get(['delivery','depositQueue']);
      if (!delivery?.enabled || !depositQueue.length) return;
      if (!origins.includes(delivery.origin)) throw Error('수신 서버 설정을 확인하세요.');
      const { depositCredential } = await this.session.get('depositCredential');
      if (!depositCredential || depositCredential.origin !== delivery.origin) throw Error('대기 내역을 보내려면 토큰을 다시 입력하세요.');
      const key = depositQueue[0];
      const rows = (await this.local.get(key))[key];
      if (!rows) throw Error('전송 대기 자료를 확인하세요.');
      let checkpoint;
      let scanIssue='';
      try {
        if (key.startsWith('deposit-scan/')) {
          const response=await this.send(delivery.origin+depositPath+'/scans',{
            method:'POST',credentials:'omit',redirect:'error',signal:AbortSignal.timeout(15000),
            headers:{Authorization:'Bearer '+depositCredential.token,'Content-Type':'application/json'},
            body:JSON.stringify(rows),
          });
          if(response.status!==202) throw Object.assign(Error('회차 접수 HTTP '+response.status),{status:response.status});
          const receipt=await response.json();
          if(receipt.scanId!==rows.scanId || (!receipt.issue && receipt.checkpoint!==rows.throughSecond)) throw Error('회차 접수 응답 불일치');
          if(!receipt.issue) checkpoint={origin:delivery.origin,accountKey:rows.accountKey,second:receipt.checkpoint};
          else scanIssue=receipt.issue;
        } else await postObservations(delivery.origin, depositCredential.token, rows, this.send);
      } catch (error) {
        if (error.status===425) {
          // Retry a missing prerequisite on the delivery alarm, then expose it alongside any rejected batch.
          const {scanRetries={}}=await this.local.get('scanRetries');
          if ((scanRetries[key] ?? 0)<3) {
            await this.edit(()=>this.local.set({scanRetries:{...scanRetries,[key]:(scanRetries[key] ?? 0)+1}}));
            throw error;
          }
        }
        if (![400,409,413,425].includes(error.status)) throw error;
        // Keep permanent rejections for operator review without blocking later captures.
        await this.edit(async () => {
          const {depositQueue: queue, depositHeld = {}} = await this.local.get(['depositQueue','depositHeld']);
          await this.local.set({depositQueue:queue.filter(k => k !== key), depositHeld:{...depositHeld,[key]:error.status}});
        });
        continue;
      }
      await this.edit(async () => {
        const {depositQueue:queue,scanRetries={}}=await this.local.get(['depositQueue','scanRetries']);
        delete scanRetries[key];
        // Tombstone and index advance in the same write, then reclaim the empty key.
        await this.local.set({ [key]:null, scanRetries, depositQueue:queue.filter(k => k !== key),
          deliveryLastAt:new Date().toISOString(), deliveryIssue:scanIssue, ...(checkpoint ? {bankCheckpoint:checkpoint} : {}) });
        await this.local.remove(key);
      });
    }
  }
}
