import { DatabaseSync } from 'node:sqlite';
import { randomUUID } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { buildSnapshot } from '../extension/parser.mjs';
import { makeTable } from '../fixtures/data.mjs';

export const PAGE_SIZE = 200;

// This synthetic source assigns an immutable sequence at insertion. Screen row
// numbers, capture IDs and timestamps do not have this property.
export class SyntheticJournal {
  constructor(filename) {
    mkdirSync(path.dirname(filename), { recursive: true });
    this.db = new DatabaseSync(filename);
    this.db.exec(`
      PRAGMA busy_timeout = 5000;
      PRAGMA journal_mode = WAL;
      PRAGMA synchronous = FULL;
      CREATE TABLE IF NOT EXISTS stream (id INTEGER PRIMARY KEY CHECK (id = 1), uuid TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS entry (
        seq INTEGER PRIMARY KEY AUTOINCREMENT, date_text TEXT NOT NULL, direction TEXT NOT NULL,
        amount_krw INTEGER NOT NULL, description TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS checkpoint (destination TEXT PRIMARY KEY, seq INTEGER NOT NULL);
    `);
    this.db.prepare('INSERT OR IGNORE INTO stream VALUES (1, ?)').run(randomUUID());
    this.streamId = this.db.prepare('SELECT uuid FROM stream WHERE id=1').get().uuid;
  }

  // Intentionally identical visible fields within a burst, including the second.
  append(count, direction = '입금') {
    if (!Number.isSafeInteger(count) || count < 1 || count > 100_000) throw Error('건수는 1~100000입니다.');
    if (!['입금', '출금'].includes(direction)) throw Error('종류는 입금 또는 출금입니다.');
    const kst = new Date(Date.now() + 9 * 3600_000).toISOString().slice(0, 19).replace('T', ' ');
    const insert = this.db.prepare('INSERT INTO entry (date_text, direction, amount_krw, description) VALUES (?, ?, 1000, ?)');
    this.db.exec('BEGIN IMMEDIATE');
    try {
      for (let i = 0; i < count; i++) insert.run(kst, direction, '김예시');
      this.db.exec('COMMIT');
    } catch (error) {
      this.db.exec('ROLLBACK');
      throw error;
    }
  }

  highWater() {
    return this.db.prepare('SELECT COALESCE(MAX(seq), 0) AS seq FROM entry').get().seq;
  }

  start(destination) {
    // First subscription starts at the current tail, subsequent starts resume.
    this.db.prepare(`INSERT OR IGNORE INTO checkpoint (destination, seq)
      SELECT ?, COALESCE(MAX(seq), 0) FROM entry`).run(destination);
    return this.cursor(destination);
  }

  cursor(destination) {
    return this.db.prepare('SELECT seq FROM checkpoint WHERE destination=?').get(destination).seq;
  }

  advance(destination, seq) {
    this.db.prepare('UPDATE checkpoint SET seq=MAX(seq, ?) WHERE destination=?').run(seq, destination);
  }

  page(after, through) {
    const entries = this.db.prepare('SELECT * FROM entry WHERE seq > ? AND seq <= ? ORDER BY seq LIMIT ?')
      .all(after, through, PAGE_SIZE);
    if (!entries.length) return null;
    const table = makeTable(['거래일시', '입출금구분', '거래금액', '거래내용'], entries.map(row =>
      [row.date_text, row.direction, String(row.amount_krw), row.description]));
    const snapshot = buildSnapshot({
      table, sourceUrl: 'http://127.0.0.1/fixtures/bank.html', accountAlias: '합성원장',
      bank: 'other', captureId: randomUUID(), capturedAt: new Date().toISOString(),
    });
    if (snapshot.transactions.length !== entries.length) throw Error('합성 원장 파싱 실패. 해당 페이지를 재시도합니다.');
    const deposits = [];
    for (const [i, t] of snapshot.transactions.entries()) {
      if (t.direction === 'CREDIT') deposits.push({
        sequence: entries[i].seq, direction: t.direction, amountKrw: t.amountKrw,
        occurredAt: t.occurredAt, description: t.description,
      });
    }
    return { last: entries.at(-1).seq, deposits, scanned: entries.length };
  }

  close() { this.db.close(); }
}
