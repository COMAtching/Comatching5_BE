// Synthetic examples, NOT a capture of an authenticated KB/Toss page.
export const headers = ['거래일시', '적요', '거래내용', '찾으신 금액(원)', '맡기신 금액(원)', '거래후 잔액(원)', '순번'];
export const rows = [
  ['2026.11.22 14:01:03', '타행이체', 'CM7K2P', '0', '1,000', '11,000', '1'],
  ['2026.11.22 14:01:03', '타행이체', 'CM7K2P', '0', '1,000', '11,000', '1'],
  ['2026.11.22 14:01:04', '타행이체', '김예시', '-', '1,000', '12,000', '3'],
  ['2026.11.22 14:02', '계좌이체', '환불예시', '1,000', '-', '11,000', '4'],
  ['2026.11.22', '타행이체', '날짜만있는예시', '', '2,000', '13,000', '5'],
  ['2026.11.22 14:04:00', '타행이체', '금액오류예시', '', '1,00O', '14,000', '6'],
];
export function makeTable(columnNames = headers, values = rows) {
  return {
    tableIndex: 0, caption: '합성 거래내역',
    rows: [
      { sourceRow: 1, section: 'thead', cells: columnNames.map(text => ({ text, header: true })) },
      ...values.map((row, i) => ({ sourceRow: i + 2, section: 'tbody', cells: row.map(text => ({ text, header: false })) })),
    ],
  };
}
