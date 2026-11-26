import assert from 'node:assert/strict';
import test from 'node:test';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { quickQueryControl } from '../extension/quick-query.mjs';
import { extractRenderedTables } from '../extension/extractor.mjs';

// Set BANK_PROBE_TEST_CHROME to a Chromium executable. Only a local fixture and
// a disposable profile are used; query, next and capture are the extension functions.
test('KB footer in Chrome: no button, standalone next, appended rows and uncertain navigation', {
  skip: !process.env.BANK_PROBE_TEST_CHROME && 'set BANK_PROBE_TEST_CHROME to run the browser fixture',
}, () => {
  const folder=mkdtempSync(join(tmpdir(),'kb-footer-'));
  try {
    const script = browserFixture.toString();
    const modules=['parser','scan-pages'].map(name=>readFileSync(new URL('../extension/'+name+'.mjs',import.meta.url),'utf8')
      .replace(/^export /gm,'')).join('\n');
    const html='<!doctype html><meta charset=utf-8><p>2026.01.02 ~ 2099.01.01</p>'+
      '<table><tr><th>계좌정보</th><td>000000-00-123456 (합성)</td></tr></table>'+
      '<button id="unrelated">다음</button><div id="results"></div><div id="footer"></div>'+
      '<table id="conditions"><tr><th>조회조건</th><td>당일</td></tr>'+
      '<tr><th>조회내용</th><td><label><input type="radio" checked>입금내용만</label></td></tr>'+
      '<tr><th>조회결과 순서</th><td><label><input type="radio" checked>최근 거래내역이 위로</label></td></tr></table>'+
      '<button id="query">조회</button><output id="out">running</output><script>'+modules+
      '\nconst quickQuery = new Function("location","return ("+'+JSON.stringify(quickQueryControl.toString())+'+")")({origin:"https://fixture.invalid",pathname:"/quics",search:"?page=C025255"});'+
      '\nconst extract = new Function("location","return ("+'+JSON.stringify(extractRenderedTables.toString())+'+")")({href:"http://127.0.0.1/fixture"});'+
      '\n('+script+')().catch(e=>document.querySelector("#out").textContent="FAIL "+e.message);</script>';
    const file=join(folder,'case.html');
    writeFileSync(file,html);
    const dom=execFileSync(process.env.BANK_PROBE_TEST_CHROME,[
      '--headless','--disable-gpu','--disable-background-networking','--disable-sync','--no-first-run',
      '--host-resolver-rules=MAP * ~NOTFOUND','--user-data-dir='+join(folder,'profile'),
      '--virtual-time-budget=30000','--dump-dom',pathToFileURL(file).href,
    ],{encoding:'utf8',timeout:30000,stdio:['ignore','pipe','pipe']});
    assert.equal(dom.match(/<output id="out">([^<]*)<\/output>/)?.[1],'PASS');
  } finally { rmSync(folder,{recursive:true,force:true}); }
});

async function browserFixture() {
  const el=id=>document.getElementById(id);
  const check=(ok,msg)=>{if(!ok) throw Error(msg);};
  const call=(mode,id='',ordinal=1)=>quickQuery(mode,'https://fixture.invalid',id,ordinal);
  const header='<thead><tr><th rowspan="2">거래일시</th><th>내통장표시내용</th><th rowspan="2">출금금액</th><th rowspan="2">입금금액</th></tr><tr><th>의뢰인/수취인</th></tr></thead>';
  const row=n=>'<tr><td rowspan="2">2026.01.02 12:00:0'+n+'</td><td></td><td rowspan="2">0</td><td rowspan="2">'+(n===6?'2':'1')+'</td></tr><tr><td>합성</td></tr>';
  let chunks, append, redraw, inside, current, queries, nextClicks, outside=0;
  el('unrelated').onclick=()=>outside++;
  function render(reset) {
    const html=chunks[current].map(row).join('');
    if (!reset && append) el('results').querySelector('tbody').insertAdjacentHTML('beforeend',html);
    else {
      const body=redraw ? chunks.slice(0,current+1).flat().map(row).join('') : html;
      el('results').innerHTML='<table>'+header+'<tbody>'+body+'</tbody></table>';
    }
    el('footer').innerHTML=current+1<chunks.length ? '<button>다음 ▸</button>' : '';
    if (inside && current+1<chunks.length) {
      el('footer').innerHTML='';
      el('results').querySelector('tbody').insertAdjacentHTML('beforeend','<tr><td colspan="4"><a href="#next">다음</a></td></tr>');
    }
    const next=el('footer').querySelector('button') || el('results').querySelector('a');
    if (next) next.onclick=event=>{event.preventDefault();current++;nextClicks++;render(false);};
  }
  el('query').onclick=()=>{queries++;current=0;render(true);};
  for (const scenario of ['short','replace','append','inside','redraw']) {
    chunks=scenario==='short'?[[6,5,4,3,2,1]]:[[6,5],[4,3],[2,1]];
    inside=scenario==='inside'; redraw=scenario==='redraw'; append=scenario==='append'; queries=0;nextClicks=0;current=0;render(true);
    let pos=1,previous;
    async function capture(mode, target) {
      const id=crypto.randomUUID();
      const click=call(mode,id,pos);
      check(!click.error,click.error);
      await new Promise(resolve=>setTimeout(resolve,500));
      const read=call('read',id);
      check(read.settled,'table did not refresh');
      pos=target;
      const result=await readPage();
      if (target>1) result.snapshot=footerPageSnapshot(previous,result.full,read.appended);
      previous=result.full;
      return result;
    }
    async function readPage() {
      const layout=await call('layout','',pos);
      check(!layout.error,layout.error);
      check(layout.navigation==='footer' && layout.number===pos,'footer not identified');
      const table=extract().tables.find(t=>t.tableIndex===layout.tableIndex);
      const snapshot=buildSnapshot({table,accountAlias:'합성',bank:'kb',sourceUrl:'http://127.0.0.1/fixture',
        captureId:crypto.randomUUID(),capturedAt:new Date().toISOString()});
      return {layout,snapshot,full:snapshot};
    }
    const first=await capture('click',1);
    const result=await collectPages(first,null,n=>capture(n===1?'click':'page:'+n,n),readPage);
    const rows=result.pages.flatMap(p=>p.transactions);
    check(rows.length===6 && rows.reduce((sum,r)=>sum+r.amountKrw,0)===7,'wrong ledger');
    check(queries===(chunks.length>1?3:1) && nextClicks===(chunks.length-1)*2 && current===0,'wrong replay');
  }
  check(outside===0,'clicked unrelated next button');
  el('footer').innerHTML='<button>다음</button><button>다음</button>';
  check((await call('layout')).error,'ambiguous next accepted');
  el('footer').innerHTML='<span>더보기</span>';
  check((await call('layout')).error,'unsupported control accepted');
  el('footer').innerHTML='';
  el('conditions').rows[0].cells[0].textContent='기타';
  check(!(await call('layout')).headVerified,'unbounded footer accepted');
  el('out').textContent='PASS';
}
