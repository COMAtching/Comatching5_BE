// Run by BankApprovalIT. Only the Chrome storage and alarm adapters are in memory.
import assert from 'node:assert/strict';
import {buildSnapshot} from '../../../../tools/bank-transaction-probe/extension/parser.mjs';
import {DepositDelivery,origins} from '../../../../tools/bank-transaction-probe/extension/deposit-delivery.mjs';
import {collectPages} from '../../../../tools/bank-transaction-probe/extension/scan-pages.mjs';

const [port,second]=process.argv.slice(2);
const startedAt=new Date().toISOString();
const rows=[];
function pair(section,values,detail) {
  const header=section==='thead';
  rows.push({section,cells:values.map((text,i)=>({text,header,rowSpan:i===1?1:2}))});
  rows.push({section,cells:[{text:detail,header}]});
}
pair('thead',['거래일시','내통장표시내용','입금금액','출금금액'],'의뢰인/수취인');
pair('tbody',[second.replace('T',' '),'','1,000','0'],'단독');
const snapshot=buildSnapshot({table:{tableIndex:1,rows},bank:'kb',accountAlias:'검증',captureId:crypto.randomUUID(),
  capturedAt:new Date().toISOString(),sourceUrl:'https://obank1.kbstar.com/quics?page=C025255'});
const accountKey='a'.repeat(64);
const page={snapshot,layout:{accountKey,number:1,navigation:'footer',headVerified:true,total:null,end:true,next:false,startDate:second.slice(0,10),endDate:'2099-12-31'}};
const scan=await collectPages(page,null,()=>assert.fail(),()=>page,startedAt);
function area() {
  const values={};
  return {get:async keys=>structuredClone(Object.fromEntries([keys].flat().filter(k=>k in values).map(k=>[k,values[k]]))),
    set:async update=>Object.assign(values,structuredClone(update)),remove:async k=>{delete values[k];}};
}
const delivery=new DepositDelivery({local:area(),session:area()},{create:async()=>{}},(url,opts)=>{
  assert.equal(new URL(url).origin,origins[0]);
  return fetch('http://127.0.0.1:'+Number(port)+new URL(url).pathname,opts);
});
await delivery.configure(origins[0],process.env.TEST_DEPOSIT_TOKEN);
assert.equal((await delivery.health()).checkpoint,null);
await delivery.enqueueScan(scan,startedAt);
await delivery.drain();
assert.equal(await delivery.checkpoint(accountKey),second);
assert.equal((await delivery.health()).checkpoint,second);
