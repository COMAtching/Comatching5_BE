const rowValues = r => [r.transactionDate,r.transactionTime,r.direction,r.amountKrw,r.balanceKrw,
  r.counterparty,r.description,r.summary,r.bankTransactionId];

// A replaced page can have identical-looking transfers. Slice a cumulative result
// when DOM identity or strict chronological ordering confirms the prefix.
export function footerPageSnapshot(previous, current, appended) {
  const before=previous.transactions, after=current.transactions;
  if (!before.length || !after.length) throw Error('다음 페이지의 거래를 확인할 수 없습니다.');
  const key = row => JSON.stringify(rowValues(row));
  const beforeKeys=before.map(key);
  const prefix=after.length>=before.length && beforeKeys.every((value,i)=>value===key(after[i]));
  // If KB redraws the entire tbody, distinguish an appended prefix from a disjoint
  // next page only when going back to that prefix would reverse the bank's time order.
  const head=before[0], tail=before.at(-1);
  const chronologicalPrefix=head.timePrecision==='second' && tail.timePrecision==='second' &&
    head.transactionDate+'T'+head.transactionTime > tail.transactionDate+'T'+tail.transactionTime;
  if (!appended && !prefix) {
    const seen=new Set(beforeKeys);
    if (!after.some(row=>seen.has(key(row)))) return current;
  }
  if (!prefix || after.length===before.length || (!appended && !chronologicalPrefix))
    throw Error('다음 페이지가 바뀌었는지 확인할 수 없습니다.');
  const transactions=after.slice(before.length);
  // Internal capture for delivery; keep the source row IDs and timestamp of this read.
  return {
    captureId:current.captureId, capturedAt:current.capturedAt, dataKind:current.dataKind,
    account:current.account, counts:{accepted:transactions.length,rejected:current.counts.rejected},
    rejectedRows:current.rejectedRows, transactions,
  };
}

// Read newest first and finish the entire checkpoint second, including ties across pages.
// The cursor comes from an acknowledged complete server scan. No clock-based skipping.
export async function collectPages(first, cursor, navigate, reread, startedAt = new Date().toISOString(), activationFloor = null) {
  const pages = [first];
  const dayStart = first.layout.startDate + 'T00:00:00';
  // A transfer posted in the currently open second can acquire an identical sibling.
  const sealed = new Date(Date.parse(startedAt)-1000+9*3600_000).toISOString().slice(0,19);
  if (first.layout.number !== 1)
    throw Error(`현재 ${first.layout.number}페이지입니다. KB에서 조회를 눌러 첫 페이지를 여세요.`);
  if (first.layout.headVerified===false && first.layout.total!==first.snapshot.transactions.length)
    throw Error('첫 페이지 번호 또는 은행의 전체 거래 건수를 확인할 수 없습니다.');
  const low = cursor || (activationFloor && !first.layout.end && first.layout.total===null && activationFloor>dayStart ? activationFloor : dayStart);
  const virtualBoundary = low===dayStart || low===activationFloor;
  if (low>sealed) throw Error('승인 시작 또는 이전 조회 시각을 지난 뒤 다시 조회하세요.');
  const today = new Date(Date.now()+9*3600_000).toISOString().slice(0,10);
  if (first.layout.endDate < today || dayStart > low) {
    const from = low.slice(0,10);
    throw Error(`KB 조회 기간은 ${first.layout.startDate} ~ ${first.layout.endDate}입니다. ${from}부터 ${today}까지 포함하도록 기간을 바꾸고 조회를 누르세요.`);
  }
  const stamp = row => row.timePrecision === 'second' ? row.transactionDate+'T'+row.transactionTime : null;
  const signature = ({snapshot,layout}) => JSON.stringify([layout,snapshot.counts,snapshot.rejectedRows,snapshot.transactions.map(rowValues)]);
  let last, newest, totalRows=0, anchorSeen=false;
  for (;;) {
    const page=pages.at(-1);
    const {layout,snapshot}=page;
    if (layout.accountKey!==first.layout.accountKey || layout.startDate!==first.layout.startDate ||
        layout.endDate!==first.layout.endDate || layout.navigation!==first.layout.navigation || layout.total!==first.layout.total || layout.number!==pages.length ||
        snapshot.counts.rejected) throw Error('조회 조건, 페이지 또는 행 구성이 바뀌었습니다.');
    if (layout.navigation==='footer' && !snapshot.transactions.length && layout.total!==0)
      throw Error('빈 거래표는 은행의 조회 결과 없음 표시를 확인한 뒤 처리합니다.');
    for (const row of snapshot.transactions) {
      const t=stamp(row);
      if (!t) continue;
      if (t.slice(0,10)<layout.startDate || t.slice(0,10)>layout.endDate) throw Error('조회 기간 밖의 거래가 표시됐습니다.');
      if (last && last<t) throw Error('최근 거래가 위에 오도록 조회하세요.');
      if (t<=sealed) newest ??= t;
      last=t;
      if (t===cursor) anchorSeen=true;
    }
    totalRows+=snapshot.transactions.length;
    if (totalRows>20000) throw Error('조회 기간을 줄여 이전 거래 구간부터 복구하세요.');
    const knownEnd=layout.total===totalRows || layout.end;
    if (knownEnd && layout.next) throw Error('은행의 마지막 페이지와 다음 페이지 표시가 일치하지 않습니다.');
    if (layout.total!==null && (totalRows>layout.total || (layout.end && totalRows!==layout.total))) throw Error('은행의 전체 건수와 수집 건수가 다릅니다.');
    if (last && last<low && (anchorSeen || virtualBoundary)) break;
    if (knownEnd) break;
    if (!layout.next || pages.length>=250) throw Error('이전 조회 경계 또는 마지막 페이지를 확인할 수 없습니다.');
    pages.push(await navigate(pages.length+1));
  }
  // An empty baseline can use midnight, which need not have a matching bank row.
  if (cursor && !virtualBoundary && !anchorSeen) throw Error('직전 조회의 거래 시각을 찾지 못했습니다.');
  function verify(again,index) {
    if(signature(again)!==signature(pages[index])) throw Error('페이지 이동 중 거래가 바뀌었습니다. 다음 조회에서 복구합니다.');
  }
  if (first.layout.navigation==='footer' && pages.length>1) {
    // This KB layout only promises a forward button. Replay from a fresh inquiry.
    for (let i=0;i<pages.length;i++) verify(await navigate(i+1),i);
    verify(await navigate(1),0);
  } else {
    for (let i=pages.length-1;i>=0;i--) verify(i===pages.length-1 ? await reread() : await navigate(i+1),i);
  }
  const included=pages.map(({snapshot,layout}) => {
    const ids=snapshot.transactions.filter(r=>r.direction==='CREDIT' && (!stamp(r) || (stamp(r)>=low && stamp(r)<=sealed))).map(r=>r.observationId);
    return {number:layout.number, captureId:snapshot.captureId, totalCredits:snapshot.transactions.filter(r=>r.direction==='CREDIT').length, credits:ids.length, ids};
  });
  return {pages:pages.map(p=>p.snapshot), accountKey:first.layout.accountKey, fromSecond:low, throughSecond:newest && newest>low ? newest : low, manifestPages:included};
}
