// Runs only inside DepositObservationKafkaIT, against its ephemeral loopback HTTP port.
import assert from 'node:assert/strict';
import { buildSnapshot } from '../../../../tools/bank-transaction-probe/extension/parser.mjs';
import { makeTable } from '../../../../tools/bank-transaction-probe/fixtures/data.mjs';
import { observationBatches, postObservations } from '../../../../tools/bank-transaction-probe/extension/deposit-delivery.mjs';

const [port, captureId] = process.argv.slice(2);
const table = makeTable(['거래일시','입금금액','출금금액'], [
  ['2026.11.24 13:00:00','1000','0'], ['2026.11.24 13:00:00','1000','0'], ['2026.11.24 13:00:01','0','500'],
]);
const snapshot = buildSnapshot({ table, bank:'kb', accountAlias:'축제', captureId,
  capturedAt:'2026-11-24T04:00:00Z', sourceUrl:'https://obank1.kbstar.com/quics?page=C025255' });
const [rows] = observationBatches(snapshot);
assert.equal(rows.length,2);
const loopback = 'http://127.0.0.1:' + Number(port);
const origin = 'chrome-extension://example';
const preflight = await fetch(loopback + '/api/v1/collector/deposit-observations', {
  method:'OPTIONS', headers:{ Origin:origin, 'Access-Control-Request-Method':'POST',
    'Access-Control-Request-Headers':'authorization,content-type' },
});
assert.equal(preflight.status,200);
assert.equal(preflight.headers.get('Access-Control-Allow-Origin'),origin);
await postObservations(loopback, process.env.TEST_DEPOSIT_TOKEN, rows, (url, options) =>
  fetch(url, { ...options, headers:{...options.headers, Origin:origin} }));
