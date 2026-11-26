One 560px main container shared by extension and demo.
<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>계좌내역 JSON 검증기</title>
  <link rel="stylesheet" href="popup.css">
</head>
<body>
  <main>
    <h1>계좌내역 JSON 검증기 <small>0.1</small></h1>
    <p>KB 조회 화면의 표를 읽습니다. 현재 페이지에 로드된 행만 포함됩니다.</p>
    <div class="notice">로그인과 계좌 연결은 은행에서 직접 진행하세요. 이 프로그램은 결제 승인을 실행하지 않습니다.</div>
    <div class="pair">
      <label>조회한 계좌의 은행<select id="bank"><option value="toss">토스뱅크 · KB 다른금융 조회</option><option value="kb">KB국민은행</option><option value="other">기타 은행 · KB 다른금융 조회</option></select></label>
      <label>계좌 별칭<input id="alias" value="행사입금" maxlength="60" autocomplete="off" placeholder="계좌번호 대신 별칭"></label>
    </div>
    <button id="scan" type="button">현재 페이지의 표 읽기</button>
    <p id="status" role="status" aria-live="polite">은행에서 거래내역을 조회한 뒤 실행하세요.</p>
    <section id="configuration" hidden>
      <label>표 선택<select id="table"></select></label>
      <label>제목행의 마지막 행 번호<input id="header-end" type="number" min="1" step="1" value="1"></label>
      <p class="hint">자동 선택된 열을 실제 은행 표와 비교하세요. 날짜와 입금/출금액, 또는 날짜와 금액/구분이 필요합니다. 적요는 입금자명과 다를 수 있습니다.</p>
      <div id="mapping" class="pair"></div>
      <button id="convert" type="button">JSON 미리보기 만들기</button>
    </section>
    <section id="preview" hidden>
      <h2>추출 결과</h2>
      <p id="summary"></p>
      <p id="warnings" class="notice"></p>
      <div class="scroll"><table><thead><tr><th>일자·시각</th><th>구분</th><th>금액</th><th>통장 표시</th></tr></thead><tbody id="rows"></tbody></table></div>
      <p class="hint">미리보기는 처음 20건입니다. 파일에는 선택한 표의 모든 정상 행과 제외·오류 행 번호가 포함됩니다. 같은 내역을 재추출하면 중복됩니다.</p>
      <button id="save" type="button">JSON 파일 저장</button>
    </section>
    <details><summary>권한과 제한</summary><p>확장 프로그램 버튼을 누른 탭에만 접근합니다. 입력칸 값·인증정보·네트워크 응답을 읽거나 서버로 전송하지 않습니다. 표에 표시된 거래내용은 JSON에 포함됩니다. 자동 새로고침·페이지 넘김·백그라운드 수집은 아직 지원하지 않습니다.</p></details>
  </main>
  <script type="module" src="popup.mjs"></script>
</body>
</html>
