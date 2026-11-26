import test from 'node:test';
import assert from 'node:assert/strict';
import { collectPages, footerPageSnapshot } from '../extension/scan-pages.mjs';

const day='2026-01-02', low=day+'T12:00:00';
function page(number, offsets, total, options={}) {
  const captureId=crypto.randomUUID();
  return {
    layout:{number,accountKey:'a'.repeat(64),startDate:day,endDate:'2099-01-01',total,
      next:number*20<total,end:number*20>=total,...options},
    snapshot:{captureId,counts:{rejected:0},transactions:offsets.map((n,i)=>({
      observationId:captureId+':0:0:'+i,transactionDate:day,transactionTime:n<0?'11:59:59':'12:00:'+String(n).padStart(2,'0'),
      timePrecision:'second',direction:'CREDIT',amountKrw:1000,counterparty:'가상입금',
    }))},
  };
}
async function collect(pages,cursor,change) {
  let current=0;
  const read=()=>pages[current];
  const go=async number=>{current=number-1;return read();};
  return collectPages(read(),cursor,go,change || read);
}

test('25 new payments and the complete checkpoint second span three pages',async()=>{
  const entries=[...Array.from({length:25},(_,i)=>25-i),...Array(16).fill(0),-1];
  const pages=[0,20,40].map((offset,i)=>page(i+1,entries.slice(offset,offset+20),42));
  const result=await collect(pages,low);
  assert.deepEqual(result.manifestPages.map(p=>p.credits),[20,20,1]);
  assert.equal(result.manifestPages[2].totalCredits,2);
  assert.equal(new Set(result.manifestPages.flatMap(p=>p.ids)).size,41);
  assert.equal(result.throughSecond,day+'T12:00:25');
});
test('missing page, changed page, rejected row, unknown end and contradictory count fail closed',async()=>{
  const a=page(1,Array(20).fill(3),22);
  const b=page(2,[0,-1],22);
  await assert.rejects(collect([a,{...b,layout:{...b.layout,number:3}}],low));
  await assert.rejects(collect([a,b],low,()=>page(2,[2,-1],22)),/거래가 바뀌/);
  await assert.rejects(collect([{...a,snapshot:{...a.snapshot,counts:{rejected:1}}}],low));
  await assert.rejects(collect([page(1,[3],null,{next:false,end:false})],null),/마지막 페이지/);
  await assert.rejects(collect([page(1,[3],1,{next:true,end:false})],null),/일치하지/);
  await assert.rejects(collect([page(1,[3,-1],2)],low),/직전 조회/);
});
test('a verified empty result still yields a completion marker',async()=>{
  const r=await collect([page(1,[],0)],null);
  assert.equal(r.throughSecond,day+'T00:00:00');
  assert.equal(r.manifestPages[0].credits,0);
});

test('today-only inquiry keeps a new credit pending until the previous day is covered', async t => {
  const startedAt = '2026-11-26T09:32:00+09:00';
  t.mock.method(Date,'now',()=>Date.parse(startedAt));
  const cursor = '2026-11-25T17:27:19';
  const first = page(1,[1],1,{startDate:'2026-11-26',endDate:'2026-11-26'});
  const credit = first.snapshot.transactions[0];
  Object.assign(credit,{transactionDate:'2026-11-26',transactionTime:'09:30:00',amountKrw:800});
  const scan = () => collectPages(first,cursor,()=>assert.fail(),()=>first,startedAt);
  await assert.rejects(scan,/2026-11-25부터 2026-11-26까지/);
  first.layout.startDate = '2026-11-25';
  first.layout.endDate = '2026-11-25';
  await assert.rejects(scan,/2026-11-25부터 2026-11-26까지/);
  first.layout.endDate = '2026-11-26';
  first.layout.number = 2;
  await assert.rejects(scan,/현재 2페이지/);
  first.layout.number = 1;
  await assert.rejects(scan,/직전 조회의 거래 시각/);
  first.layout.total = 2;
  first.snapshot.transactions.push({...credit,observationId:'previous',transactionDate:'2026-11-25',transactionTime:'17:27:19'});
  const result = await scan();
  assert.equal(result.fromSecond,cursor);
  assert.equal(result.throughSecond,'2026-11-26T09:30:00');
  assert.deepEqual(result.manifestPages[0].ids,[credit.observationId,'previous']);
});
test('current second remains raw until a subsequent complete capture',async()=>{
  const first=page(1,[10,9,8],3);
  const result=await collectPages(first,null,()=>{},()=>first,day+'T12:00:10+09:00');
  assert.equal(result.throughSecond,day+'T12:00:09');
  assert.equal(result.manifestPages[0].credits,2);
  assert.equal(result.manifestPages[0].totalCredits,3);
});

test('KB short result: no numbered pager or total, verified footer with no next control',async()=>{
  const first=page(1,[6,5,4,3,2,1],null,{navigation:'footer',headVerified:true,next:false,end:true});
  const result=await collect([first],null);
  assert.equal(result.manifestPages[0].credits,6);
  await assert.rejects(collect([{...first,layout:{...first.layout,headVerified:false}}],null),/첫 페이지/);
  await assert.rejects(collect([page(1,[],null,{navigation:'footer',headVerified:true,next:false,end:true})],null),/빈 거래표/);
});

test('KB footer next-only pages replay from a new query and finish back on the head',async()=>{
  const offsets=[[6,5],[4,3],[2,1]];
  const pages=offsets.map((rows,i)=>page(i+1,rows,null,{
    navigation:'footer',headVerified:i===0,next:i<2,end:i===2,
  }));
  const visits=[];
  async function visit(n){visits.push(n);return pages[n-1];}
  const result=await collectPages(pages[0],null,visit,()=>assert.fail());
  assert.deepEqual(visits,[2,3,1,2,3,1]);
  assert.deepEqual(result.manifestPages.map(p=>p.credits),[2,2,2]);
  let restarted=false;
  await assert.rejects(collectPages(pages[0],null,async n=>{
    if(n===1) restarted=true;
    if(restarted && n===2) return page(2,[4,2],null,pages[1].layout);
    return pages[n-1];
  },()=>assert.fail()),/거래가 바뀌/);
});

test('KB appended rows retain distinct event IDs and ambiguous page progress is held',()=>{
  const first=page(1,[6,5],null).snapshot;
  const combined=page(2,[6,5,4,4],null).snapshot;
  const segment=footerPageSnapshot(first,combined,true);
  assert.deepEqual(segment.transactions.map(r=>r.observationId),combined.transactions.slice(2).map(r=>r.observationId));
  assert.equal(segment.transactions.length,2);
  assert.equal(new Set(segment.transactions.map(r=>r.observationId)).size,2);
  assert.deepEqual(footerPageSnapshot(first,combined,false).transactions,combined.transactions.slice(2));
  assert.throws(()=>footerPageSnapshot(page(1,[5,5],null).snapshot,page(2,[5,5,4],null).snapshot,false),/다음 페이지/);
  assert.throws(()=>footerPageSnapshot(first,page(2,[5,3],null).snapshot,false),/다음 페이지/);
  assert.throws(()=>footerPageSnapshot(first,first,false),/다음 페이지/);
  assert.throws(()=>footerPageSnapshot(first,page(2,[6,4,3],null).snapshot,true),/다음 페이지/);
});
