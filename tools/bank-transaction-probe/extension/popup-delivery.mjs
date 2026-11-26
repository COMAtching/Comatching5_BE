import { origins } from './deposit-delivery.mjs';

export function connectDeliveryUI(getSnapshot) {
  const el = id => document.getElementById(id);
  const panel = el('delivery-panel');
  const server = el('delivery-origin');
  const token = el('delivery-token');
  const info = el('delivery-info');
  const controls = ['delivery-connect','delivery-pause','delivery-send','delivery-retry'].map(el);
  let busy = false;
  panel.hidden = false;

  async function render() {
    const { delivery, depositQueue = [], depositHeld = {}, deliveryLastAt, deliveryIssue } =
      await chrome.storage.local.get(['delivery','depositQueue','depositHeld','deliveryLastAt','deliveryIssue']);
    controls[0].disabled = busy;
    controls[1].disabled = busy || !delivery?.enabled;
    controls[2].disabled = busy || !delivery?.enabled;
    const held = Object.values(depositHeld);
    controls[3].hidden = !held.length;
    controls[3].disabled = busy || !delivery?.enabled;
    const review = held.length ? ' · 확인 필요 ' + held.length + '묶음 (HTTP ' + [...new Set(held)].join(', ') + ')' : '';
    const state = delivery?.enabled ? '연결 설정됨 · ' + delivery.origin : '전송 꺼짐';
    const last = deliveryLastAt ? ' · 최근 서버 접수 ' + new Date(deliveryLastAt).toLocaleString('ko-KR') : '';
    info.textContent = state + ' · 대기 ' + depositQueue.length + '묶음' + review + last + (deliveryIssue ? ' · ' + deliveryIssue : '');
  }

  async function act(action) {
    busy = true;
    controls.forEach(control => { control.disabled = true; });
    el('delivery-error').textContent = '';
    try {
      const result = await action();
      if (!result?.ok) throw Error(result?.error || '전송 설정을 확인하세요.');
      token.value = '';
    } catch (error) {
      el('delivery-error').textContent = error.message;
    } finally {
      busy = false;
      await render();
    }
  }
  controls[0].onclick = () => act(async () => {
    const origin = server.value;
    if (!origins.includes(origin) || !/^[!-~]{32,}$/.test(token.value)) throw Error('서버와 토큰(32자 이상)을 확인하세요.');
    const url = new URL(origin);
    const granted = await chrome.permissions.request({ origins:[url.protocol + '//' + url.hostname + '/*'] });
    if (!granted) throw Error('선택한 수신 서버에 접근할 권한이 필요합니다.');
    return chrome.runtime.sendMessage({type:'configureDelivery', origin, token:token.value});
  });
  controls[1].onclick = () => act(() => chrome.runtime.sendMessage({type:'pauseDelivery'}));
  controls[2].onclick = () => act(() => {
    const snapshot = getSnapshot();
    if (!snapshot || snapshot.dataKind !== 'bank_page_observation' || snapshot.account.bank !== 'kb') {
      throw Error('KB 거래내역을 먼저 읽어 주세요.');
    }
    if (!snapshot.transactions.some(row => row.direction === 'CREDIT')) throw Error('현재 페이지에 보낼 입금이 없습니다.');
    return chrome.runtime.sendMessage({type:'sendSnapshot', snapshot});
  });
  controls[3].onclick = () => act(() => chrome.runtime.sendMessage({type:'retryHeld'}));
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === 'local' && ['delivery','depositQueue','depositHeld','deliveryIssue','deliveryLastAt'].some(k => changes[k])) void render();
  });
  chrome.storage.local.get('delivery').then(({delivery}) => {
    if (delivery?.origin) server.value = delivery.origin;
    return render();
  });
}
