import { extractRenderedTables } from './extractor.mjs';
import { allowedPage, inspectTable, buildSnapshot, FIELD_LABELS } from './parser.mjs';

const $ = id => document.getElementById(id);
const demo = ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname)
  && new URL(location.href).searchParams.get('demo') === '1';
document.body.classList.add(location.protocol === 'chrome-extension:' ? 'popup' : 'full-page');
const format = value => value.toLocaleString('ko-KR');
let candidates = [], current, snapshot, context, filter = 'all', visibleLimit = 20;
let ledgerRows = [];
let tabId, watch;
const extension = location.protocol === 'chrome-extension:';
function refreshControls() {
  if (!extension) return;
  const ready = snapshot?.account.bank === 'kb' && current?.inspected.kbDetailColumn >= 0;
  $('auto-controls').hidden = false;
  $('auto-toggle').disabled = !watch?.active && !ready;
  $('auto-toggle').textContent = watch?.active ? '자동 조회 중지' : '3분 자동 조회 시작';
  const time = watch?.lastAt && new Date(watch.lastAt).toLocaleTimeString('ko-KR');
  const last = time ? ' · 마지막 갱신 ' + time : '';
  $('auto-status').textContent = watch?.active
    ? (watch.tabId === tabId ? '자동 조회 중' : '다른 KB 탭에서 조회 중') + ' · 3분 간격 · ' + watch.count + '회' + last + (watch.reason ? ' · ' + watch.reason : '')
    : watch?.reason ? watch.reason + last : 'KB 빠른조회 결과를 한 번 읽은 뒤 시작하세요.';
}
async function syncWatch() {
  const stored = await chrome.storage.session.get(['watch', 'latest']);
  watch = stored.watch;
  if (stored.latest && watch?.tabId === tabId && (!snapshot || stored.latest.capturedAt > snapshot.capturedAt)) {
    snapshot = stored.latest;
    $('bank').value = snapshot.account.bank;
    $('alias').value = snapshot.account.alias;
    renderSnapshot();
  }
  refreshControls();
}

const reasons = {
  invalid_money:'금액 형식 오류', unsafe_money_integer:'금액 범위 초과',
  invalid_date:'날짜 형식 오류', invalid_time:'거래 시각 오류', conflicting_time:'시각 불일치',
  unknown_direction:'입출금 구분 확인', zero_or_missing_amount:'금액 누락 또는 0원',
  both_credit_and_debit:'입출금이 동시에 표시됨', irregular_data_row:'열 구조 확인',
};
function status(message, error = false) {
  $('status').textContent = message;
  $('status').hidden = !message;
  $('status').dataset.error = String(error);
}
function element(tag, text = '', className = '') {
  const el = document.createElement(tag);
  el.textContent = text;
  if (className) el.className = className;
  return el;
}
function invalidate() {
  snapshot = null; refreshControls(); ledgerRows = []; visibleLimit = 20; filter = 'all';
  $('save').disabled = true;
  for (const id of ['total-in','total-out','credit-count']) $(id).textContent = '—';
  $('scope').textContent = '현재 페이지에 표시된 거래';
  $('warnings').textContent = '';
  renderRows();
}
function renderRows() {
  const ready = Boolean(snapshot);
  const selected = filter === 'all' ? ledgerRows : ledgerRows.filter(row => row.kind === filter);
  const counts = { all: ledgerRows.length, CREDIT:0, DEBIT:0, review:0 };
  for (const row of ledgerRows) counts[row.kind]++;
  for (const b of $('filters').children) {
    b.disabled = !ready;
    b.setAttribute('aria-pressed', String(filter === b.dataset.filter));
    b.querySelector('small').textContent = ready ? format(counts[b.dataset.filter]) : '';
  }
  $('rows').replaceChildren(...selected.slice(0, visibleLimit).map(renderRow));
  $('ledger-table').hidden = !selected.length;
  $('empty').hidden = Boolean(selected.length);
  $('empty').textContent = ready ? '해당하는 거래가 없습니다.'
    : current ? '표와 열 설정을 확인한 후 선택한 열을 적용하세요.' : '조회할 계좌를 확인한 후 거래내역 읽기를 눌러주세요.';
  $('more').hidden = selected.length <= visibleLimit;
  $('ledger-foot').hidden = !ready;
  $('range').textContent = ready ? format(Math.min(visibleLimit, selected.length)) + ' / ' + format(selected.length) + '행 표시' : '';
}
function renderRow({ kind, data }) {
  const review = kind === 'review', credit = kind === 'CREDIT';
  const row = element('tr','',review ? 'review' : credit ? 'credit' : 'debit');
  const label = element('div','','tx-label');
  const icon = element('span',review ? '!' : credit ? '↙' : '↗','tx-icon');
  icon.setAttribute('aria-hidden','true');
  const title = element('div');
  title.append(element('span', review ? '원본 ' + data.sourceRow + '행' : (data.description || data.summary || '표시 없음'),'tx-title'));
  if (review || (data.description && data.summary)) title.append(element('span',review ? (reasons[data.reason] || data.reason) : data.summary,'tx-sub'));
  if (!review && data.counterparty) title.append(element('span','의뢰인/수취인 · ' + data.counterparty,'tx-sub'));
  label.append(icon,title);
  const name = element('td'); name.append(label);
  const date = element('td',review ? '—' : data.transactionDate.replaceAll('-','.'));
  if (!review) date.append(element('span',data.transactionTime || '시간 없음','tx-time'));
  const direction = element('td');
  direction.append(element('span',review ? '확인 필요' : credit ? '입금' : '출금','tag'));
  row.append(name,date,direction,element('td',review ? '—' : (credit ? '+' : '−') + format(data.amountKrw) + '원','tx-amount'));
  return row;
}
function option(label, value) { return new Option(label, value); }

function renderMapping() {
  invalidate();
  $('mapping').replaceChildren();
  const inspected = inspectTable(current.table, Number($('header-end').value) - 1);
  for (const [field, title] of Object.entries(FIELD_LABELS)) {
    const label = element('label', title);
    const select = element('select'); select.dataset.field = field;
    select.append(option('사용 안 함', ''));
    inspected.headers.forEach((parts, i) => select.append(option((i+1) + '. ' + (parts.join(' / ') || '(제목 없음)'), String(i))));
    select.value = inspected.mapping[field] === undefined ? '' : String(inspected.mapping[field]);
    label.append(select); $('mapping').append(label);
  }
  return inspected;
}
function selectTable() {
  current = candidates[Number($('table').value)];
  $('header-end').max = String(current.table.rows.length - 1);
  $('header-end').value = String(current.inspected.headerEnd + 1);
  return renderMapping();
}
function applySelection() {
  const mapping = Object.fromEntries(Array.from($('mapping').querySelectorAll('select'))
    .filter(s => s.value !== '').map(s => [s.dataset.field, Number(s.value)]));
  snapshot = buildSnapshot({ ...context, ...current, mapping, headerEnd:Number($('header-end').value)-1,
    accountAlias:$('alias').value, bank:$('bank').value });
  renderSnapshot();
  refreshControls();
  status(context.frameWarning ? '일부 프레임의 포함 여부를 확인하세요.' : '');
}
function renderSnapshot() {
  ledgerRows = [...snapshot.transactions.map(data => ({kind:data.direction,data})),
    ...snapshot.rejectedRows.map(data => ({kind:'review',data}))].sort((a,b) => a.data.sourceRow-b.data.sourceRow);
  const { accepted, rejected, excluded } = snapshot.counts;
  const unreadable = !accepted && rejected > 0;
  $('total-in').textContent = unreadable ? '—' : format(snapshot.totals.creditKrw);
  $('total-out').textContent = unreadable ? '—' : format(snapshot.totals.debitKrw) + '원';
  $('credit-count').textContent = unreadable ? '—' : format(snapshot.transactions.filter(t => t.direction === 'CREDIT').length) + '건';
  $('scope').textContent = '정상 ' + format(accepted) + '건 · 확인 필요 ' + format(rejected) + '행' + (excluded ? ' · 제외 ' + format(excluded) + '행' : '');
  const warnings = [];
  if (snapshot.warnings.includes('bank_transaction_id_missing')) warnings.push('거래고유번호가 없는 내역이 있어 재조회한 거래의 중복을 자동 판별할 수 없습니다.');
  if (snapshot.warnings.includes('frame_coverage_unverified')) warnings.push('일부 프레임의 거래가 빠질 수 있습니다.');
  $('warnings').textContent = warnings.join(' ');
  $('save').disabled = !accepted;
  $('configuration').open = false;
  renderRows();
}
function handleError(error) {
  invalidate();
  if (current) $('configuration').open = true;
  status(error.message || '표를 읽지 못했습니다. 조회 화면을 확인하세요.',true);
}
async function scan() {
  current = null; context = null; candidates = [];
  invalidate(); status('거래내역을 읽고 있습니다.');
  $('configuration').hidden = true; $('scan').disabled = true;
  try {
    let results, sourceUrl, fallback = false;
    if (demo) {
      const frame = document.getElementById('demo-bank');
      // The same extractor is imported and executed within the synthetic page.
      const result = await frame.contentWindow.captureFixture();
      results = [{ frameId: 0, result }]; sourceUrl = frame.src;
    } else {
      const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
      tabId = tab?.id; sourceUrl = tab?.url;
      if (!allowedPage(sourceUrl)) throw new Error('KB국민은행 조회 페이지에서 확장 프로그램을 실행하세요.');
      try {
        results = await chrome.scripting.executeScript({ target: { tabId: tab.id, allFrames: true }, func: extractRenderedTables });
      } catch {
        // If a child frame is inaccessible, report incomplete coverage explicitly.
        fallback = true;
        results = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: extractRenderedTables });
      }
    }
    context = { sourceUrl, captureId: crypto.randomUUID(), capturedAt: new Date().toISOString(),
      frameWarning: fallback || results.some(r => r.error || !r.result || r.result.status === 'unsupported_origin' || r.result.childFrameCount > 0) };
    let unsupported = 0;
    for (const entry of results) {
      for (const table of entry.result?.tables ?? []) {
        try { candidates.push({ frameId: entry.frameId, table, inspected: inspectTable(table) }); }
        catch { unsupported++; }
      }
    }
    candidates.sort((a, b) => b.inspected.score - a.inspected.score);
    if (!candidates.length) throw new Error(`읽을 수 있는 표가 없습니다. 로그인 후 거래내역 조회를 완료하세요. 카드형 화면·접근 불가 프레임·지원하지 않는 병합 표일 수 있습니다.${unsupported ? ` (지원하지 않는 표 ${unsupported}개)` : ''}`);
    $('table').replaceChildren(...candidates.map((c,i) => option('표 ' + (i+1) + ' · ' + (c.table.caption || c.inspected.headers.map(h => h.join('/')).join(' | ')), String(i))));
    $('configuration').hidden = false;
    const inspected = selectTable();
    const kbListOnly = inspected.kbDetailColumn >= 0 && candidates.slice(1).every(c =>
      ['date', 'amount', 'credit', 'debit'].every(field => c.inspected.mapping[field] === undefined && !c.inspected.ambiguous.includes(field)));
    if ((candidates.length > 1 && !kbListOnly) || unsupported || inspected.ambiguous.length) {
      $('configuration').open = true;
      status('거래내역 표와 열을 선택한 후 적용하세요.' + (unsupported ? ' 읽을 수 없는 표 ' + unsupported + '개.' : ''));
    } else {
      applySelection();
    }
    $('scan-text').textContent = '새로 읽기';
  } catch (error) { handleError(error); }
  finally { $('scan').disabled = false; }
}
$('scan').addEventListener('click',scan);
$('table').addEventListener('change', () => { try { selectTable(); status('열을 확인한 후 적용하세요.'); } catch (e) { handleError(e); } });
$('header-end').addEventListener('input', () => { try { renderMapping(); status('선택한 열을 적용하세요.'); } catch (e) { handleError(e); } });
$('mapping').addEventListener('change', () => { invalidate(); status('선택한 열을 적용하세요.'); });
$('convert').addEventListener('click', () => { try { applySelection(); } catch (e) { handleError(e); } });
for (const [id, event] of [['bank','change'], ['alias','change']]) {
  $(id).addEventListener(event, () => {
    if (!snapshot || !current) return;
    try { applySelection(); } catch(e) { handleError(e); }
  });
}
$('filters').addEventListener('click',event => {
  const button = event.target.closest('button[data-filter]');
  if (!button || !snapshot) return;
  filter = button.dataset.filter; visibleLimit = 20; renderRows();
});
$('more').addEventListener('click', () => { visibleLimit += 20; renderRows(); });
$('save').addEventListener('click', () => {
  if (!snapshot?.counts.accepted) return;
  const blob = new Blob([JSON.stringify(snapshot, null, 2) + '\n'], { type: 'application/json;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a'); link.href = url;
  link.download = `bank-probe-${snapshot.capturedAt.replace(/[:.]/g, '-')}-${snapshot.captureId.slice(0, 8)}.json`;
  link.click(); setTimeout(() => URL.revokeObjectURL(url), 10000);
  status('JSON 다운로드를 요청했습니다. 다운로드 폴더에서 확인하세요.');
});

invalidate();
if (extension) {
  const { connectDeliveryUI } = await import('./popup-delivery.mjs');
  connectDeliveryUI(() => snapshot);
  const [tab] = await chrome.tabs.query({ active:true, currentWindow:true });
  tabId = tab?.id;
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === 'session' && (changes.watch || changes.latest)) syncWatch().catch(e => status(e.message, true));
  });
  $('auto-toggle').addEventListener('click', async () => {
    $('auto-toggle').disabled = true;
    try {
      const msg = watch?.active ? { type:'stopRefresh' } : {
        type:'startRefresh', tabId, frameId:current.frameId, tableIndex:current.table.tableIndex,
        accountAlias:snapshot.account.alias, headers:snapshot.source.headers,
        mapping:snapshot.source.columnMapping, headerEnd:snapshot.source.headerEnd - 1,
      };
      const reply = await chrome.runtime.sendMessage(msg);
      if (!reply?.ok) throw Error(reply?.error || '자동 조회 상태를 확인해 주세요.');
      status('');
    } catch (error) { status(error.message, true); }
    await syncWatch();
  });
  await syncWatch();
}
if (demo) {
  $('mode').textContent = '샘플 계좌';
  $('scan').disabled = true;
  const frame = document.createElement('iframe');
  frame.id = 'demo-bank'; frame.className = 'fixture-frame'; frame.title = '샘플 원본';
  frame.tabIndex = -1; frame.setAttribute('aria-hidden','true'); frame.inert = true;
  frame.addEventListener('load',scan,{once:true});
  frame.src = '../fixtures/bank.html'; document.body.append(frame);
}
