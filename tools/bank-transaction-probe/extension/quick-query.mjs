export function isQuickInquiry(raw) {
  try {
    const url = new URL(raw);
    return url.protocol === 'https:' && /^(obank|obank1)\.kbstar\.com$/.test(url.host)
      && url.pathname === '/quics' && url.searchParams.get('page') === 'C025255';
  } catch { return false; }
}

// Chrome serializes this function into the selected frame's isolated world.
export function quickQueryControl(mode, origin, requestId, ordinal = 1) {
  const visible = el => el.getClientRects().length > 0 && getComputedStyle(el).visibility === 'visible'
    && !el.closest('[hidden],[inert],[aria-hidden="true"]');
  if (location.origin !== origin || (window.top === window && (location.pathname !== '/quics'
      || new URLSearchParams(location.search).get('page') !== 'C025255'))) return { error: 'KB 빠른조회 화면을 다시 여세요.' };

  const normalize = text => (text || '').replace(/\s/g, '');
  if ([...document.querySelectorAll('input[type=password],dialog[open],[role=alertdialog],[aria-modal=true]')].some(visible)) {
    return { error: '은행의 인증 또는 안내 창을 먼저 확인하세요.' };
  }
  const tables = [...document.querySelectorAll('table')];
  const results = tables.filter(table => visible(table) && table.tHead && ['거래일시','입금금액','내통장표시내용','의뢰인/수취인']
    .every(label => [...table.tHead.querySelectorAll('th')].some(th => normalize(th.textContent) === label)));
  if (results.length !== 1) return { error: 'KB 거래내역 결과표를 한 개만 표시해 주세요.' };
  const table = results[0];
  const ledger = { tableIndex: tables.indexOf(table) };
  if (mode === 'layout' || mode.startsWith('page:')) {
    // Limit navigation to numbered links in a visible paginator in this results frame.
    const bars=[...document.querySelectorAll('.paging,.pagination,.paginate,.pagingArea,.paging_area,.pagingWrap,[aria-label*="페이지"]')]
      .filter(el => visible(el) && el.querySelector('a,button,strong,[aria-current]'));
    const pagers=bars.filter(el=>!bars.some(other=>other!==el && el.contains(other)));
    if (pagers.length>1) return {error:'거래 페이지 선택 영역이 모호합니다.'};
    const selected=[...document.querySelectorAll('input[type=radio]:checked')].flatMap(el=>
      [...el.labels ?? []].filter(visible).map(label=>normalize(label.innerText)));
    if (!selected.some(s=>['전체','입금내용만'].includes(s)) || !selected.includes('최근거래내역이위로') ||
        selected.includes('출금내용만') || selected.includes('과거거래내역이위로'))
      return {error:'조회내용을 전체 또는 입금내용만, 순서를 최근 거래내역이 위로 설정하세요.'};
    const bar=pagers[0];
    const current=bar && [...bar.querySelectorAll('[aria-current=page],strong,.active,.on')].filter(visible)
      .map(el=>normalize(el.textContent)).filter(text=>/^\d+$/.test(text));
    const label = el => normalize(el.getAttribute('aria-label') || el.innerText || el.getAttribute('value') ||
      el.getAttribute('alt') || el.getAttribute('title') || el.querySelector('img')?.alt);
    const numeric = el => Number(label(el).match(/^(?:페이지)?(\d+)(?:페이지)?$/)?.[1]);
    const actionable = 'a,button,input[type=button],input[type=submit],input[type=image],[role=button],[onclick]';
    const enabled = el => !el.matches(':disabled,[aria-disabled=true],.disabled');
    const nextLabel = /^(?:다음(?:페이지(?:로|그룹)?|목록|블록|조회)?|Next)[▶▷▸▹►›»>]?$/i;
    const prevLabel = /^(?:이전(?:페이지(?:로|그룹)?|목록|블록|조회)?|Prev)[◀◁◂◃◄‹«<]?$/i;
    let navigation = 'numbered', number = 1, next = false, end = false;
    if (current?.length) {
      if (new Set(current).size !== 1) return {error:'현재 페이지 번호를 확인할 수 없습니다.'};
      number = Number(current[0]);
      const controls = [...bar.querySelectorAll('a,button')].filter(el=>visible(el) && enabled(el));
      const step = (el,forward) => forward ? nextLabel.test(label(el)) || /^[›»>]$/.test(label(el))
        : prevLabel.test(label(el)) || /^[‹«<]$/.test(label(el));
      next = controls.some(el=>numeric(el)===number+1 || step(el,true));
      const terminal = [...bar.querySelectorAll(':disabled,[aria-disabled=true],.disabled')].filter(visible)
        .some(el=>/다음|마지막|끝|[>›»]/.test(label(el)));
      end = terminal && !next && !controls.some(el=>numeric(el)>number || /다음|마지막|끝|[>›»]/.test(label(el)));
      if (mode.startsWith('page:')) {
        const target=Number(mode.slice(5));
        if (Math.abs(number-target)!==1) return {error:'연속된 페이지가 아닙니다.'};
        const direct=controls.filter(el=>numeric(el)===target);
        const buttons=direct.length ? direct : controls.filter(el=>step(el,target>number));
        if(buttons.length!==1) return {error:'페이지 이동 버튼을 확인하세요.'};
        return click(buttons[0]);
      }
    } else {
      // KB quick inquiry omits page numbers. Its pager sits between the result rows and
      // the separate query-conditions table; a short result has an empty footer there.
      const follows = (a,b) => !!(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
      const conditions = tables.filter(t => visible(t) && !t.contains(table) && !table.contains(t) &&
        follows(table,t) && ['조회조건','조회내용','조회결과순서'].every(name =>
          [...t.querySelectorAll('th,td')].some(c=>c.closest('table')===t && normalize(c.textContent)===name)));
      if (conditions.length===1 && conditions[0].querySelector('input[type=radio]:checked')) {
        const limit=conditions[0];
        const inFooter=el=>!el.contains(table) && !el.contains(limit) && follows(el,limit) &&
          (table.contains(el) ? el.closest('tfoot') || (el.closest('tr')===table.rows[table.rows.length-1] &&
            el.closest('tr')?.cells.length===1 && el.closest('td')?.colSpan>1) : follows(table,el));
        if (bar && !inFooter(bar)) return {error:'거래표의 페이지 표시 위치를 확인할 수 없습니다.'};
        const footerControls=[...document.querySelectorAll(actionable)].filter(el=>visible(el) && inFooter(el));
        // A styled link may contain a nested button or onclick span. Use the innermost control.
        const controls=footerControls.filter(el=>!footerControls.some(other=>el!==other && el.contains(other)));
        const nextButtons=controls.filter(el=>nextLabel.test(label(el)));
        const previous=controls.filter(el=>prevLabel.test(label(el)));
        const unknownPageHint=[...document.querySelectorAll('span,img,strong,div')].some(el=>
          !el.childElementCount && inFooter(el) && visible(el) &&
          /^(?:다음|이전|더보기|계속조회|\d+\/\d+|[›»<>]|Next|Prev)/i.test(label(el) || normalize(el.alt)) &&
          !controls.some(control=>control.contains(el)));
        if (unknownPageHint) return {error:'거래표 아래의 페이지 안내를 확인할 수 없습니다.'};
        if (nextButtons.length>1 || previous.length>1 || controls.some(el=>
          !nextButtons.includes(el) && !previous.includes(el) && !/인쇄|저장|다운로드|엑셀/.test(label(el))))
          return {error:'거래표 아래의 페이지 이동 버튼을 확인할 수 없습니다.'};
        if (ordinal===1 && previous.some(enabled)) return {error:'새 조회의 첫 페이지를 확인할 수 없습니다.'};
        navigation='footer';
        number=ordinal;
        if (nextButtons.some(el=>!enabled(el))) return {error:'다음 버튼이 아직 비활성 상태입니다.'};
        next=nextButtons.length===1;
        end=!next;
        if (mode.startsWith('page:')) {
          if (!next || Number(mode.slice(5))!==ordinal+1) return {error:'연속된 다음 페이지가 아닙니다.'};
          return click(nextButtons[0]);
        }
      } else if (bar || mode.startsWith('page:')) {
        return {error:'거래표 아래의 조회조건과 페이지 경계를 확인할 수 없습니다.'};
      }
    }
    const text=document.body.innerText;
    const dates=[...text.matchAll(/(\d{4})[.-](\d{2})[.-](\d{2})\s*[~～]\s*(\d{4})[.-](\d{2})[.-](\d{2})/g)];
    const ranges=new Set(dates.map(m=>m.slice(1,4).join('-')+'/'+m.slice(4,7).join('-')));
    const accountCells=[...document.querySelectorAll('th,td')].filter(el=>visible(el) && normalize(el.textContent)==='계좌정보');
    const account=accountCells[0]?.nextElementSibling?.innerText?.match(/^\s*(\d+(?:[ -]\d+)*)(?=\s*(?:\(|$))/)?.[1].replace(/\D/g,'');
    if(ranges.size!==1 || accountCells.length!==1 || !/^\d{10,16}$/.test(account ?? '')) return {error:'계좌 식별 또는 조회 기간을 확인할 수 없습니다.'};
    const [startDate,endDate]=[...ranges][0].split('/');
    const counts=[table.caption?.innerText,table.previousElementSibling?.innerText].filter(Boolean)
      .flatMap(t=>[...t.matchAll(/(?:전체|총)\s*([\d,]+)\s*건/g)].map(m=>Number(m[1].replaceAll(',',''))));
    if(new Set(counts).size>1) return {error:'은행의 조회 건수가 일치하지 않습니다.'};
    const empty=[...table.tBodies].flatMap(body=>[...body.rows]).filter(visible);
    const noRows=empty.length===1 && empty[0].cells.length===1 &&
      /^(?:조회된?|검색된?)?(?:거래)?(?:내역|결과|자료|데이터)(?:이|가)?없습니다[.!]?$/.test(normalize(empty[0].innerText));
    const total=counts[0] ?? (noRows ? 0 : null);
    return crypto.subtle.digest('SHA-256',new TextEncoder().encode('KB/'+account)).then(bytes=>({
      tableIndex:ledger.tableIndex,number,next,end,total,startDate,endDate,navigation,headVerified:number===1 && (navigation==='footer' || !!current?.length),
      accountKey:Array.from(new Uint8Array(bytes),n=>n.toString(16).padStart(2,'0')).join(''),
    }));
  }
  if (mode === 'read') {
    const marker = globalThis.__comatchingQuickQuery;
    const settled = marker?.id === requestId && (marker.table !== table ||
      (marker.updatedAt > 0 && Date.now() - marker.updatedAt >= 400));
    const before=marker?.id===requestId && marker.beforeRows;
    const now=settled && before?.length && marker.table===table ? transactionRows() : [];
    const appended=!!before?.length && now.length>before.length && before.every((row,i)=>row===now[i]);
    return { ...ledger, settled, appended };
  }

  const buttons = [...document.querySelectorAll('button,a,input[type=button],input[type=submit],input[type=image],[role=button]')]
    .filter(el => visible(el) && !el.matches(':disabled,[aria-disabled=true]') &&
      normalize(el.innerText || el.getAttribute('value') || el.getAttribute('aria-label') ||
        el.getAttribute('alt') || el.querySelector('img')?.alt) === '조회');
  if (buttons.length !== 1) return { error: '사용 가능한 ‘조회’ 버튼을 한 개만 찾을 수 있는지 확인하세요.' };
  if (mode === 'click') return click(buttons[0]);
  return ledger;

  function transactionRows() {
    return [...table.tBodies].flatMap(body=>[...body.rows]).filter(row=>row.cells.length>1 &&
      /^\s*\d{4}[.\/-]\d{1,2}[.\/-]\d{1,2}/.test(row.cells[0].textContent));
  }

  function click(button) {
    globalThis.__comatchingQuickQuery?.observer.disconnect();
    const marker = { id: requestId, table, updatedAt: 0, beforeRows: mode.startsWith('page:') ? transactionRows() : null };
    const observer = new MutationObserver(() => { marker.updatedAt = Date.now(); });
    observer.observe(table, { childList: true, characterData: true, subtree: true });
    setTimeout(() => observer.disconnect(), 20_000);
    marker.observer = observer;
    globalThis.__comatchingQuickQuery = marker;
    button.click();
    return ledger;
  }
}
