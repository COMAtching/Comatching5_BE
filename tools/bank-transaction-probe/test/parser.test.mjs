import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { allowedPage, safePageUrl, parseWon, parseDate, inspectTable, buildSnapshot } from '../extension/parser.mjs';
import { makeTable } from '../fixtures/data.mjs';

const options = { sourceUrl: 'https://obank.kbstar.com/quics?page=C101762&session=SECRET#token',
  bank: 'toss', accountAlias: '테스트입금', captureId: 'synthetic-capture', capturedAt: '2026-11-22T05:00:00Z' };
const snapshot = (table, extra = {}) => buildSnapshot({ ...options, table, ...extra });

test('split columns: duplicates retained, invalid rows reported, no row-number transaction ID', () => {
  const result = snapshot(makeTable());
  assert.deepEqual(result.counts, { observedDataRows: 6, accepted: 5, rejected: 1, excluded: 0 });
  assert.deepEqual(result.totals, { creditKrw: 5000, debitKrw: 1000 });
  assert.deepEqual(result.rejectedRows, [{ sourceRow: 7, reason: 'invalid_money' }]);
  assert.equal(result.transactions[0].bankTransactionId, null);
  assert.notEqual(result.transactions[0].observationId, result.transactions[1].observationId);
  assert.equal(result.transactions[0].description, result.transactions[1].description);
  assert.equal(result.canAutoApprove, false);
  assert.equal(result.coverage, 'rendered_page_only');
  assert.equal(result.account.verification, 'user_supplied_unverified');
  assert.equal(result.source.url, 'https://obank.kbstar.com/quics?page=C101762');
  assert.equal(result.dataKind, 'bank_page_observation');
});

test('10,000 identical 1,000 KRW rows survive; later capture adds 2,000 without pretending to dedupe', () => {
  const row = ['2026-11-22 13:04:05', '1,000', '동일입금자'];
  const columns = ['거래일시', '입금액', '거래내용'];
  const first = snapshot(makeTable(columns, Array.from({ length: 10000 }, () => [...row])));
  assert.equal(first.transactions.length, 10000);
  assert.equal(first.totals.creditKrw, 10000000);
  assert.equal(new Set(first.transactions.map(t => t.observationId)).size, 10000);
  const next = snapshot(makeTable(columns, Array.from({ length: 12000 }, () => [...row])), { captureId: 'second-capture' });
  assert.equal(next.transactions.length, 12000);
  assert.equal(next.totals.creditKrw, 12000000);
  assert.ok(next.warnings.includes('observations_are_not_deduplicated'));
});

test('strict KRW parsing never drops bad characters or accepts rounded/unsafe amounts', () => {
  for (const value of ['1,000', '₩ 1,000', '1,000원', 'KRW 1000', '1000 KRW']) assert.equal(parseWon(value), 1000);
  for (const value of ['1,00', '1,00O', '1.000', '1,000.50', '1e3', '1 000', '-1000', '***', '9007199254740992']) assert.throws(() => parseWon(value));
  assert.equal(parseWon('-', { blankIsZero: true }), 0);
  assert.equal(parseWon(''), null);
  assert.equal(parseWon('-1,000', { allowNegative: true }), -1000);
});

test('calendar validation, separate time, and precision without inventing seconds', () => {
  assert.equal(parseDate('2024.2.29', '1:02:03').occurredAt, '2024-02-29T01:02:03+09:00');
  assert.equal(parseDate('20261122').transactionDate, '2026-11-22');
  assert.equal(parseDate('2026-11-22 01:02').occurredAt, null);
  assert.equal(parseDate('2026-11-22 01:02').timePrecision, 'minute');
  assert.equal(parseDate('2026-11-22').timePrecision, 'day');
  for (const value of ['2026-02-29', '2026-13-01', '2026-04-31', '2026-01-00', '26-11-22', '2026-11-22 24:00', '2026-11-22 12:60', '2026-11-22 12:00:60']) assert.throws(() => parseDate(value));
  assert.throws(() => parseDate('2026-11-22 12:00:00', '12:01:00'));
});

test('invalid date in the first transaction is reported, never consumed as an extra heading', () => {
  const result = snapshot(makeTable(['거래일자', '입출금구분', '거래금액'], [
    ['2026-13-01', '입금', '1000'], ['2026-11-22', '입금', '2000'],
  ]));
  assert.equal(result.source.headerEnd, 1);
  assert.equal(result.counts.observedDataRows, 2);
  assert.deepEqual(result.rejectedRows, [{ sourceRow: 2, reason: 'invalid_date' }]);
  assert.equal(result.transactions[0].amountKrw, 2000);
});

test('local demo exports are explicitly labeled synthetic', () => {
  assert.equal(snapshot(makeTable(), { sourceUrl: 'http://127.0.0.1:48765/fixtures/bank.html' }).dataKind, 'synthetic_demo');
});

test('unified amount and direction, explicit transaction ID, overdraft balance', () => {
  const result = snapshot(makeTable(['거래일자', '거래시간', '입출금구분', '거래금액', '거래번호', '잔액'], [
    ['2026.11.22', '10:01:02', '입금', '1000', 'TX0001', '-2,000'],
    ['2026.11.22', '10:02:00', '출금', '500', 'TX0002', '-2,500'],
    ['2026.11.22', '10:02:01', '취소', '500', 'TX0003', '-2,000'],
  ]));
  assert.equal(result.transactions[0].bankTransactionId, 'TX0001');
  assert.equal(result.transactions[0].balanceKrw, -2000);
  assert.equal(result.transactions[1].direction, 'DEBIT');
  assert.equal(result.rejectedRows[0].reason, 'unknown_direction');
});

test('unknown headings can be manually mapped; duplicated or contradictory mappings fail', () => {
  const table = makeTable(['날짜컬럼', '금액컬럼', '표시컬럼'], [['2026-11-22', '1000', 'CM7K2P']]);
  assert.throws(() => snapshot(table), /거래일자/);
  assert.equal(snapshot(table, { mapping: { date: 0, credit: 1, description: 2 } }).transactions[0].description, 'CM7K2P');
  assert.throws(() => snapshot(table, { mapping: { date: 0, credit: 1, debit: 1 } }), /중복/);
  assert.throws(() => snapshot(table, { mapping: { date: 0, amount: 1 } }), /구분/);
  assert.throws(() => snapshot(table, { mapping: { date: 0, credit: 1, amount: 2 } }), /중 하나/);
  assert.throws(() => snapshot(table, { mapping: { date: 0, credit: 99 } }), /범위/);
});

test('ambiguous header does not silently select one deposit column', () => {
  const table = makeTable(['거래일시', '입금액', '입금금액'], [['2026-11-22', '1000', '2000']]);
  const info = inspectTable(table);
  assert.deepEqual(info.ambiguous, ['credit']);
  assert.throws(() => snapshot(table));
});

test('separate amount columns and a transfer-method heading do not select conflicting modes', () => {
  const table = makeTable(['거래일시', '거래구분', '입금액', '출금액'], [
    ['2026.11.23 12:22:05', '인터넷', '1,000', '0'],
    ['2026.11.23 12:23:00', '자동이체', '0', '700'],
  ]);
  const result = snapshot(table);
  assert.deepEqual(result.totals, { creditKrw: 1000, debitKrw: 700 });
  assert.equal(result.counts.accepted, 2);
  assert.equal(result.source.columnMapping.direction, undefined);
});

test('multi-level headings expand correctly without consuming first transaction', () => {
  const table = makeTable(['거래일자', '입금액', '출금액'], [['2026-11-22', '1000', '0']]);
  table.rows = [
    { section: 'thead', cells: [{ text: '거래일자', header: true, rowSpan: 2 }, { text: '거래금액', header: true, colSpan: 2 }] },
    { section: 'thead', cells: [{ text: '입금액', header: true }, { text: '출금액', header: true }] },
    table.rows[1],
  ];
  assert.equal(inspectTable(table).headerEnd, 1);
  assert.deepEqual(inspectTable(table).mapping, { date: 0, credit: 1, debit: 2 });
  assert.equal(snapshot(table).transactions[0].amountKrw, 1000);
});

test('totals excluded explicitly; both-sided, empty, irregular or masked money rows rejected', () => {
  const table = makeTable(['거래일자', '입금액', '출금액'], [
    ['2026-11-22', '1000', '1000'], ['2026-11-22', '', ''], ['2026-11-22', '***', '0'],
    ['2026-11-22', '1000'], ['합계', '9999', '9999'], ['', '', ''],
  ]);
  const result = snapshot(table);
  assert.deepEqual(result.rejectedRows.map(r => r.reason), ['both_credit_and_debit', 'zero_or_missing_amount', 'invalid_money', 'irregular_data_row']);
  assert.equal(result.excludedRows.length, 2);
  assert.equal(result.transactions.length, 0);
});

test('merged data rows and inherited rowspan values never generate made-up transactions', () => {
  const table = makeTable(['거래일자', '입금액'], [['2026-11-22', '1000'], ['2000']]);
  table.rows[1].cells[0].rowSpan = 2;
  const result = snapshot(table);
  assert.equal(result.transactions.length, 0);
  assert.equal(result.rejectedRows.length, 2);
});

// Cell geometry and labels from the KB quick-inquiry result; all values are invented.
function kbQuickTable() {
  const names = ['거래일시', '적요', '내통장표시내용', '출금금액', '입금금액', '잔액', '거래점', '구분'];
  const rows = [];
  function pair(section, values, detail) {
    const header = section === 'thead';
    rows.push({ sourceRow: rows.length + 1, section, rowGroup: header ? 0 : 1,
      cells: values.map((text, i) => ({ text, header, rowSpan: i === 2 ? 1 : 2, colSpan: 1 })) });
    rows.push({ sourceRow: rows.length + 1, section, rowGroup: header ? 0 : 1,
      cells: [{ text: detail, header, rowSpan: 1, colSpan: 1 }] });
  }
  pair('thead', names, '의뢰인/수취인');
  pair('tbody', ['2026.01.06 13:14:15', '전자금융', '', '0', '1,000', '2,000', '예시', ''], '가상송금인');
  pair('tbody', ['2026.01.06 13:14:15', '전자금융', 'DEMO-2', '0', '1,000', '3,000', '예시', ''], '가상송금인');
  pair('tbody', ['2026.01.06 13:15:00', '전자금융', '환불', '500', '0', '2,500', '예시', ''], '가상수취인');
  return { tableIndex: 1, caption: '계좌거래내역정보목록', rows };
}

test('KB quick inquiry counts each pair once and keeps the passbook text separate from the counterparty', () => {
  const table = kbQuickTable();
  const inspected = inspectTable(table);
  assert.equal(inspected.headerEnd, 1);
  assert.equal(inspected.mapping.description, 2);
  const result = snapshot(table);
  assert.deepEqual(result.counts, { observedDataRows: 3, accepted: 3, rejected: 0, excluded: 0 });
  assert.deepEqual(result.totals, { creditKrw: 2000, debitKrw: 500 });
  assert.deepEqual(result.transactions.map(t => [t.sourceRow, t.sourceEndRow]), [[3,4], [5,6], [7,8]]);
  assert.equal(result.transactions[0].description, null);
  assert.equal(result.transactions[1].description, 'DEMO-2');
  assert.equal(result.transactions[0].counterparty, '가상송금인');
  assert.equal(result.transactions[2].counterparty, '가상수취인');
});

test('KB pair with an invalid amount is one rejected transaction; orphan or mismatched cells are never synthesized', () => {
  const table = kbQuickTable();
  table.rows[2].cells[4].text = '1,00O';
  table.rows[5].rowGroup = 3;
  table.rows.pop();
  const result = snapshot(table);
  assert.equal(result.counts.accepted, 0);
  assert.deepEqual(result.rejectedRows.map(r => [r.sourceRow, r.reason]), [
    [3, 'invalid_money'], [5, 'irregular_data_row'], [6, 'irregular_data_row'], [7, 'irregular_data_row'],
  ]);
});

test('allowlist rejects lookalike domains, arbitrary websites, and insecure bank origin', () => {
  for (const url of ['https://obank.kbstar.com/quics', 'https://kbstar.com', 'http://127.0.0.1:48765/']) assert.equal(allowedPage(url), true);
  for (const url of ['https://kbstar.com.evil.test/', 'https://fakekbstar.com/', 'https://evil.test', 'http://obank.kbstar.com/', 'file:///tmp/bank.html']) assert.equal(allowedPage(url), false);
  assert.equal(safePageUrl('https://obank.kbstar.com/quics?password=secret#secret'), 'https://obank.kbstar.com/quics');
});

test('refresh worker uses a temporary tab grant, alarms and session storage without mandatory host permissions', async () => {
  const manifest = JSON.parse(await readFile(new URL('../extension/manifest.json', import.meta.url), 'utf8'));
  assert.deepEqual(manifest.permissions, ['activeTab', 'scripting', 'alarms', 'storage']);
  assert.equal(manifest.host_permissions, undefined);
  assert.deepEqual(manifest.optional_host_permissions, ['http://127.0.0.1/*','https://srv.comatching.site/*']);
  assert.equal(manifest.content_scripts, undefined);
  assert.equal(manifest.background.service_worker, 'refresh-worker.mjs');
});
