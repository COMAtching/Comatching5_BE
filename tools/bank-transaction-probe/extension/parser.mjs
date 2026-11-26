export const FIELD_LABELS = {
  date: '거래일자 / 일시', time: '거래시간', credit: '입금액', debit: '출금액',
  amount: '거래금액 (입출금 구분형)', direction: '입출금 구분', balance: '거래 후 잔액',
  description: '거래내용 / 통장 표시', summary: '적요', bankTransactionId: '은행 거래고유번호',
};

const aliases = {
  date: ['거래일시', '거래일자', '거래일', '일자', '거래날짜'],
  time: ['거래시간', '시간'],
  credit: ['입금', '입금액', '입금금액', '맡기신금액', '맡기신돈'],
  debit: ['출금', '출금액', '출금금액', '찾으신금액', '찾으신돈', '지급금액'],
  amount: ['거래금액', '금액'], direction: ['입출금구분', '입출금', '거래구분'],
  balance: ['거래후잔액', '잔액', '거래잔액'],
  description: ['거래내용', '기재내용', '내통장표시내용', '통장표시내용', '통장표시', '받는분보낸분', '보낸분받는분', '보내신분받으신분'],
  summary: ['적요'],
  // A row number/offset is NOT a bank transaction ID.
  bankTransactionId: ['거래고유번호', '거래번호', '거래일련번호'],
};
const clean = (value) => String(value ?? '').replace(/\s+/g, ' ').trim();
const headerKey = (value) => clean(value).replace(/\([^)]*\)|\[[^\]]*\]/g, '').replace(/[\s/·:_-]/g, '');

export function allowedPage(raw) {
  try {
    const u = new URL(raw);
    return (u.protocol === 'https:' && (u.hostname === 'kbstar.com' || u.hostname.endsWith('.kbstar.com')))
      || (['http:', 'https:'].includes(u.protocol) && ['localhost', '127.0.0.1', '[::1]'].includes(u.hostname));
  } catch { return false; }
}

export function safePageUrl(raw) {
  const u = new URL(raw);
  // KB's menu ID is sufficient provenance; discard session IDs and other queries.
  const page = u.searchParams.get('page');
  return `${u.origin}${u.pathname}${page && /^C\d+$/.test(page) ? `?page=${page}` : ''}`;
}

export function expandRows(rows) {
  const grid = [];
  for (let r = 0; r < rows.length; r++) {
    grid[r] ??= [];
    let c = 0;
    for (const cell of rows[r].cells) {
      while (grid[r][c] !== undefined) c++;
      const width = cell.colSpan ?? 1;
      const height = cell.rowSpan ?? 1;
      // rowSpan=0 extends to a row group: unsupported, rather than misaligning data.
      if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1 || width > 100 || height > 100) {
        throw new Error('지원하지 않는 병합 셀입니다. 표 구조를 확인해야 합니다.');
      }
      for (let dy = 0; dy < height; dy++) {
        grid[r + dy] ??= [];
        for (let dx = 0; dx < width; dx++) {
          if (grid[r + dy][c + dx] !== undefined) throw new Error('표의 병합 셀이 겹칩니다.');
          grid[r + dy][c + dx] = clean(cell.text);
        }
      }
      c += width;
    }
  }
  return grid.slice(0, rows.length);
}

export function inferMapping(headers) {
  const mapping = {};
  const ambiguous = [];
  for (const [field, names] of Object.entries(aliases)) {
    const indices = headers.flatMap((parts, index) => {
      // Deepest leaf first, then combined multi-row heading.
      const keys = [...parts].reverse().map(headerKey).concat(headerKey(parts.join('')));
      return keys.some(key => names.includes(key)) ? [index] : [];
    });
    if (indices.length === 1) mapping[field] = indices[0];
    if (indices.length > 1) ambiguous.push(field);
  }
  // With separate amount columns, 거래구분 can describe the transfer method
  // (e.g. 인터넷). A direction column is used only with a unified amount.
  if ((mapping.credit !== undefined || mapping.debit !== undefined)
    && mapping.amount === undefined && !ambiguous.includes('amount')) {
    delete mapping.direction;
    return { mapping, ambiguous: ambiguous.filter(field => field !== 'direction') };
  }
  return { mapping, ambiguous };
}

export function inspectTable(table, headerEndOverride) {
  const matrix = expandRows(table.rows);
  const width = Math.max(0, ...matrix.map(row => row.length));
  let best;
  const candidates = headerEndOverride === undefined
    ? Array.from({ length: Math.min(8, Math.max(0, matrix.length - 1)) }, (_, i) => i)
    : [headerEndOverride];
  for (const headerEnd of candidates) {
    if (!Number.isInteger(headerEnd) || headerEnd < 0 || headerEnd >= matrix.length - 1) continue;
    // Values such as "입금" in the first transaction are data, not another heading.
    // Stop even at an invalid calendar date so a malformed row cannot be swallowed.
    if (headerEndOverride === undefined && matrix[headerEnd].some(text => /^\d{4}(?:[./-]\d{1,2}[./-]\d{1,2}|\d{4})/.test(text))) break;
    // Use contiguous header rows immediately before this row, excluding titles.
    let headerStart = headerEnd;
    while (headerStart > 0 && table.rows[headerStart - 1].cells.every(c => c.header)) headerStart--;
    const headers = Array.from({ length: width }, (_, c) => [...new Set(matrix.slice(headerStart, headerEnd + 1).map(row => row[c] ?? '').filter(Boolean))]);
    const { mapping, ambiguous } = inferMapping(headers);
    const score = Object.keys(mapping).length + (mapping.date !== undefined ? 5 : 0)
      + (mapping.credit !== undefined || mapping.debit !== undefined || (mapping.amount !== undefined && mapping.direction !== undefined) ? 5 : 0);
    // A deeper all-header row resolves grouped headers. Never absorb a data row on a tie.
    if (!best || score > best.score || (score === best.score && table.rows[headerEnd].cells.every(c => c.header))) {
      best = { matrix, headers, mapping, ambiguous, headerEnd, score };
    }
  }
  if (!best) throw new Error('제목행과 데이터행이 있는 표가 필요합니다.');
  // KB quick inquiry stacks two independent text fields in one column.
  // The two heading rows, including their spans, identify this layout.
  best.kbDetailColumn = -1;
  const upper = table.rows[best.headerEnd - 1], lower = table.rows[best.headerEnd];
  if (upper?.section === 'thead' && lower.section === 'thead' && lower.cells.length === 1
      && lower.cells[0].header && headerKey(lower.cells[0].text) === '의뢰인수취인'
      && upper.cells.length === best.headers.length) {
    const column = upper.cells.findIndex(c => headerKey(c.text) === '내통장표시내용');
    if (column >= 0 && isKbPair(upper, lower, column, best.headers.length)) best.kbDetailColumn = column;
  }
  return best;
}

function isKbPair(first, second, column, width) {
  return column >= 0 && first.cells.length === width && second?.section === first.section
    && second.rowGroup === first.rowGroup
    && first.cells.every((c, i) => (c.rowSpan ?? 1) === (i === column ? 1 : 2) && (c.colSpan ?? 1) === 1)
    && second.cells.length === 1 && (second.cells[0].rowSpan ?? 1) === 1 && (second.cells[0].colSpan ?? 1) === 1;
}

export function parseWon(value, { blankIsZero = false, allowNegative = false } = {}) {
  let text = clean(value);
  if (['', '-', '—', '–'].includes(text)) return blankIsZero ? 0 : null;
  text = text.replace(/^(₩|KRW)\s*/i, '').replace(/\s*(원|KRW)$/i, '').trim();
  const pattern = allowNegative ? /^-?(?:\d+|\d{1,3}(?:,\d{3})+)$/ : /^(?:\d+|\d{1,3}(?:,\d{3})+)$/;
  if (!pattern.test(text)) throw new Error('invalid_money');
  const amount = Number(text.replaceAll(',', ''));
  if (!Number.isSafeInteger(amount)) throw new Error('unsafe_money_integer');
  return amount;
}

export function parseDate(dateValue, timeValue = '') {
  const text = clean(dateValue);
  // Full year required; no locale-dependent Date.parse or invented midnight.
  const match = text.match(/^(\d{4})[./-](\d{1,2})[./-](\d{1,2})\.?\s*(.*)$/)
    ?? text.match(/^(\d{4})(\d{2})(\d{2})\s*(.*)$/);
  if (!match) throw new Error('invalid_date');
  const [, year, month, day, embeddedTime] = match;
  const date = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day)));
  if (Number(year) < 1900 || date.getUTCFullYear() !== Number(year) || date.getUTCMonth() + 1 !== Number(month) || date.getUTCDate() !== Number(day)) throw new Error('invalid_date');
  const transactionDate = `${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`;
  if (embeddedTime && clean(timeValue) && embeddedTime !== clean(timeValue)) throw new Error('conflicting_time');
  const time = clean(timeValue) || embeddedTime;
  if (!time) return { transactionDate, transactionTime: null, timePrecision: 'day', occurredAt: null };
  const tm = time.match(/^(\d{1,2}):(\d{2})(?::(\d{2}))?$/);
  if (!tm || Number(tm[1]) > 23 || Number(tm[2]) > 59 || Number(tm[3] ?? 0) > 59) throw new Error('invalid_time');
  const transactionTime = `${tm[1].padStart(2, '0')}:${tm[2]}${tm[3] === undefined ? '' : `:${tm[3]}`}`;
  return { transactionDate, transactionTime, timePrecision: tm[3] === undefined ? 'minute' : 'second',
    occurredAt: tm[3] === undefined ? null : `${transactionDate}T${transactionTime}+09:00` };
}

function validateMapping(mapping, width) {
  const columns = Object.values(mapping);
  if (columns.some(c => !Number.isInteger(c) || c < 0 || c >= width)) throw new Error('열 선택 범위가 잘못되었습니다.');
  if (new Set(columns).size !== columns.length) throw new Error('하나의 열을 두 항목에 중복 지정할 수 없습니다.');
  if (mapping.date === undefined) throw new Error('거래일자 열을 선택하세요.');
  const split = mapping.credit !== undefined || mapping.debit !== undefined;
  const unified = mapping.amount !== undefined || mapping.direction !== undefined;
  if (split && unified) throw new Error('입금/출금액 방식과 금액/구분 방식 중 하나만 선택하세요.');
  if (!split && !(mapping.amount !== undefined && mapping.direction !== undefined)) throw new Error('입금/출금액 또는 거래금액과 입출금 구분 열이 필요합니다.');
}

export function buildSnapshot({ table, frameId = 0, mapping, headerEnd, sourceUrl, accountAlias, bank, captureId, capturedAt, frameWarning = false }) {
  if (!allowedPage(sourceUrl)) throw new Error('KB국민은행 또는 로컬 데모에서만 사용할 수 있습니다.');
  if (!clean(accountAlias)) throw new Error('계좌 별칭을 입력하세요. 계좌번호는 필요하지 않습니다.');
  if (!['toss', 'kb', 'other'].includes(bank)) throw new Error('은행을 선택하세요.');
  const inspected = inspectTable(table, headerEnd);
  const selected = mapping ?? inspected.mapping;
  validateMapping(selected, inspected.headers.length);
  const transactions = [], rejectedRows = [], excludedRows = [];
  let joinedRows = 0;
  const valueAt = (cells, key) => selected[key] === undefined ? '' : (cells[selected[key]] ?? '');
  for (let rowIndex = inspected.headerEnd + 1; rowIndex < table.rows.length; rowIndex++) {
    const rawRow = table.rows[rowIndex];
    const cells = inspected.matrix[rowIndex];
    const sourceRow = rawRow.sourceRow ?? rowIndex + 1;
    // Never include footer totals in a transaction amount; keep their count explicit.
    if ((rawRow.footerNavigation && inspected.kbDetailColumn >= 0) || rawRow.section === 'tfoot' || /^(합계|총계|소계|입출금합계)(?:\s|$|:)/.test(clean(cells[0]))) {
      excludedRows.push({ sourceRow, reason: 'summary_row' }); continue;
    }
    if (rawRow.section === 'tbody' && rawRow.cells.length === 1 &&
        rawRow.cells[0].colSpan === inspected.headers.length &&
        /^(?:조회된?|검색된?)?(?:거래)?(?:내역|결과|자료|데이터)(?:이|가)?없습니다[.!]?$/.test(clean(cells[0]).replace(/\s/g,''))) {
      excludedRows.push({sourceRow,reason:'no_results'}); continue;
    }
    if (cells.every(c => !clean(c))) { excludedRows.push({ sourceRow, reason: 'empty_row' }); continue; }
    const next = table.rows[rowIndex + 1];
    const joined = rawRow.section === 'tbody' && isKbPair(rawRow, next, inspected.kbDetailColumn, inspected.headers.length);
    if (joined) { rowIndex++; joinedRows++; }
    try {
      if ((!joined && rawRow.cells.some(c => (c.colSpan ?? 1) !== 1 || (c.rowSpan ?? 1) !== 1))
        || rawRow.cells.length !== inspected.headers.length
        || cells.length !== inspected.headers.length || cells.some(c => c === undefined)) throw new Error('irregular_data_row');
      const dates = parseDate(valueAt(cells, 'date'), valueAt(cells, 'time'));
      let credit = 0, debit = 0;
      if (selected.amount !== undefined) {
        const amount = parseWon(valueAt(cells, 'amount'));
        const direction = clean(valueAt(cells, 'direction'));
        if (['입금', '입', '수입'].includes(direction)) credit = amount;
        else if (['출금', '출', '지급'].includes(direction)) debit = amount;
        else throw new Error('unknown_direction');
      } else {
        credit = parseWon(valueAt(cells, 'credit'), { blankIsZero: true });
        debit = parseWon(valueAt(cells, 'debit'), { blankIsZero: true });
      }
      if (!(credit > 0) && !(debit > 0)) throw new Error('zero_or_missing_amount');
      if (credit > 0 && debit > 0) throw new Error('both_credit_and_debit');
      const balanceKrw = parseWon(valueAt(cells, 'balance'), { allowNegative: true });
      const bankTransactionId = clean(valueAt(cells, 'bankTransactionId')) || null;
      transactions.push({
        observationId: `${captureId}:${frameId}:${table.tableIndex}:${sourceRow}`,
        bankTransactionId, ...dates,
        direction: credit > 0 ? 'CREDIT' : 'DEBIT', amountKrw: credit > 0 ? credit : debit,
        balanceKrw, description: clean(valueAt(cells, 'description')) || null,
        ...(joined ? { counterparty: clean(next.cells[0].text) || null, sourceEndRow: next.sourceRow ?? rowIndex + 1 } : {}),
        summary: clean(valueAt(cells, 'summary')) || null,
        sourceRow,
      });
    } catch (error) {
      rejectedRows.push({ sourceRow, reason: error.message });
    }
  }
  const sum = (direction) => {
    const total = transactions.filter(t => t.direction === direction).reduce((n, t) => n + t.amountKrw, 0);
    if (!Number.isSafeInteger(total)) throw new Error('합계가 안전한 정수 범위를 벗어났습니다.');
    return total;
  };
  const warnings = ['rendered_page_only', 'not_bank_verified', 'not_for_automatic_approval', 'observations_are_not_deduplicated'];
  if (transactions.some(t => !t.bankTransactionId)) warnings.push('bank_transaction_id_missing');
  if (transactions.some(t => t.timePrecision !== 'second')) warnings.push('transaction_time_incomplete');
  if (rejectedRows.length) warnings.push('rows_rejected');
  if (frameWarning) warnings.push('frame_coverage_unverified');
  return {
    schemaVersion: 'bank-transaction-probe/v1', captureId, capturedAt,
    dataKind: ['localhost', '127.0.0.1', '[::1]'].includes(new URL(sourceUrl).hostname) ? 'synthetic_demo' : 'bank_page_observation',
    source: { kind: 'rendered_html_table', url: safePageUrl(sourceUrl), frameId, tableIndex: table.tableIndex,
      headerEnd: inspected.headerEnd + 1, headers: inspected.headers.map(parts => parts.join(' / ')), columnMapping: selected },
    account: { alias: clean(accountAlias), bank, verification: 'user_supplied_unverified' },
    coverage: 'rendered_page_only', canAutoApprove: false,
    counts: { observedDataRows: table.rows.length - inspected.headerEnd - 1 - joinedRows, accepted: transactions.length,
      rejected: rejectedRows.length, excluded: excludedRows.length },
    totals: { creditKrw: sum('CREDIT'), debitKrw: sum('DEBIT') },
    warnings, transactions, rejectedRows, excludedRows,
  };
}
