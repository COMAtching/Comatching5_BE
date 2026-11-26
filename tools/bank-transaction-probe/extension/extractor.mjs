// Self-contained: Chrome serializes this function into the active document.
// Read only rendered table text, never form values, cookies, requests or storage.
export function extractRenderedTables() {
  const allowed = (url) => {
    try {
      const u = new URL(url);
      return (u.protocol === 'https:' && (u.hostname === 'kbstar.com' || u.hostname.endsWith('.kbstar.com')))
        || (['http:', 'https:'].includes(u.protocol) && ['localhost', '127.0.0.1', '[::1]'].includes(u.hostname));
    } catch { return false; }
  };
  if (!allowed(location.href)) return { status: 'unsupported_origin', tables: [] };
  const visible = (el) => {
    if (el.closest('[hidden], [aria-hidden="true"]')) return false;
    const style = getComputedStyle(el);
    return style.display !== 'none' && style.visibility !== 'hidden' && style.visibility !== 'collapse'
      && el.getClientRects().length > 0;
  };
  const excluded = new Set(['INPUT', 'TEXTAREA', 'SELECT', 'SCRIPT', 'STYLE', 'NOSCRIPT', 'BUTTON']);
  function renderedText(el) {
    if (excluded.has(el.tagName) || !visible(el)) return '';
    return Array.from(el.childNodes, (child) => {
      if (child.nodeType === Node.TEXT_NODE) return child.textContent;
      if (child.nodeType !== Node.ELEMENT_NODE) return '';
      if (child.tagName === 'BR') return ' ';
      return renderedText(child);
    }).join(' ').replace(/\s+/g, ' ').trim();
  }
  const allTables = Array.from(document.querySelectorAll('table'));
  const tables = allTables.flatMap((table, tableIndex) => {
    if (!visible(table) || table.querySelector('table')) return [];
    const groups = Array.from(table.children);
    const visibleRows = Array.from(table.rows).filter(visible);
    const rows = visibleRows.map((row, index) => ({
      footerNavigation: index===visibleRows.length-1 && row.cells.length===1 && row.cells[0].colSpan>1 &&
        !!row.querySelector('a,button,[role=button],input[type=button],input[type=image]') &&
        /^(?:(?:이전|다음)(?:페이지|조회)?[\s◀▶▷◁›‹▸◂►◄]*){1,2}$/.test(renderedText(row)),
      sourceRow: index + 1,
      section: row.parentElement.tagName.toLowerCase(),
      rowGroup: groups.indexOf(row.parentElement),
      cells: Array.from(row.cells, (cell) => ({
        text: renderedText(cell),
        colSpan: cell.colSpan,
        rowSpan: cell.rowSpan,
        header: cell.tagName === 'TH',
      })),
    }));
    if (rows.length < 2) return [];
    return [{ tableIndex, caption: table.caption ? renderedText(table.caption) : '', rows }];
  });
  return {
    status: tables.length ? 'ok' : 'no_visible_tables',
    tables,
    // A child frame may be inaccessible to activeTab. Do not claim full frame coverage.
    childFrameCount: document.querySelectorAll('iframe,frame').length,
  };
}
