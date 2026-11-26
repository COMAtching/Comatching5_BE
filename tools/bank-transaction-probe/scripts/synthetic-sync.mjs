import { SyntheticJournal } from '../sync/journal.mjs';
import { receiverConfig, watch } from '../sync/collector.mjs';

const [command, count, direction] = process.argv.slice(2);
const journal = new SyntheticJournal(process.env.BANK_PROBE_JOURNAL ?? 'output/sync/journal.sqlite');
try {
  if (command === 'append') {
    journal.append(Number(count), direction);
    console.log(JSON.stringify({ streamId: journal.streamId, appended: Number(count), last: journal.highWater() }));
  } else if (command === 'watch') {
    const stop = new AbortController();
    process.once('SIGINT', () => stop.abort());
    process.once('SIGTERM', () => stop.abort());
    const config = receiverConfig();
    console.log('30초 주기 수집을 시작합니다. 합성 원장 ' + journal.streamId);
    await watch(journal, config, { signal: stop.signal, report: entry => console.log(JSON.stringify(entry)) });
  } else {
    throw Error('사용법: npm run sync -- watch 또는 npm run sync -- append 2000 [입금|출금]');
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
} finally {
  journal.close();
}
