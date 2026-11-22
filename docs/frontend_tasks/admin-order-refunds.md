# 프론트 개발 과제 · 미지급 주문의 수동 환불

관리자는 결제 승인 관리 화면(`/adminpage/dashboard`)에서 지급에 실패한 주문을 선택해 환불을 시작하고, 은행에서 전액을 이체한 뒤 완료를 기록한다. **환불 시작에 성공해야 송금 단계로 넘어간다.** API는 은행 송금을 실행하거나 은행 거래내역을 조회하지 않는다.

## 상태에 따른 화면과 버튼

| 서버 상태 | 화면 표시 | 가능한 작업 |
|---|---|---|
| `status=APPROVED`, `grantStatus=FAILED`, `refundStatus=NONE` | 지급 실패 | ‘지급 재시도’와 ‘환불 시작’. 하나를 요청하는 동안 두 버튼 모두 비활성화한다. |
| `refundStatus=IN_PROGRESS` | 환불 진행 중 | 주문 번호, 환불액, 시작한 관리자와 시각을 표시한다. 실제 이체 내역을 확인한 뒤 ‘이체 확인 · 환불 완료’를 누른다. |
| `refundStatus=COMPLETED` | 환불 완료 | 완료한 관리자와 시각을 표시한다. 지급 재시도·환불 시작·완료 버튼을 숨긴다. |
| 지급 대기(`PENDING`), 지급 완료(`GRANTED`), 기존 이력 확인 필요(`REVIEW_REQUIRED`) 등 | 기존 주문·지급 상태 | 환불 시작 버튼을 숨긴다. 지급 여부는 주문의 지급 상태로 판정한다. |

## 관리자 API

공통 경로는 `/api/v1/admin/payment`다. 두 POST 모두 요청 본문이 없고, 환불액은 서버에 저장된 해당 주문의 전액(`amount`, 원)을 사용한다.

| 용도 | 호출 | 성공 시 처리 |
|---|---|---|
| 환불 가능 주문 찾기 | `GET /grants?grantStatus=FAILED` | 지급 실패 목록을 읽는다. 환불이 시작되면 이 목록에서 빠진다. |
| 주문 상태 다시 확인 | `GET /requests/{orderId}/grant` | `data.status`, `grantStatus`, `attempt`, `refundStatus`를 확인한다. |
| 환불 시작 | `POST /requests/{orderId}/refund/start` | HTTP 200, `IN_PROGRESS`. 실패 목록·진행 목록을 갱신하고 이체할 금액을 표시한다. |
| 진행 중 또는 완료 목록 | `GET /refunds?status=IN_PROGRESS&page=0&size=20` | `status`는 `IN_PROGRESS`(기본) 또는 `COMPLETED`. 주문 번호 내림차순이며 페이지당 최대 100건이다. `data.content`에 아래 환불 정보가 들어 있고 `data.totalElements`가 전체 건수다. |
| 이체 확인 후 완료 | `POST /requests/{orderId}/refund/complete` | HTTP 200, `COMPLETED`. 진행·완료 목록을 갱신한다. 메일 발송은 비동기이므로 ‘환불 완료’로 표시한다. |

아래는 완료 응답의 `data` 예시다. 진행 중에는 `completedAt`과 `completedBy`가 `null`이다.

```json
{
  "orderId": 123,
  "requestedItemName": "매칭권 묶음",
  "amount": 3500,
  "refundStatus": "COMPLETED",
  "startedAt": "2026-11-20T14:10:00",
  "startedBy": 900,
  "completedAt": "2026-11-20T14:15:00",
  "completedBy": 900
}
```

## 충돌 및 응답 유실

- 시작 응답이 끊기거나 HTTP 409 `PAY-017`이 오면 주문 상태와 진행 목록을 다시 읽는다. 다른 관리자가 이미 시작했을 수 있다. `IN_PROGRESS`만으로는 송금 전후를 구별할 수 없으므로 시작 담당자와 은행 이체 내역을 대조한다.
- HTTP 409 `PAY-016`은 지급 원장 또는 지급 완료 이벤트 기록이 발견된 경우다. ‘기존 지급 이력 확인 필요’로 표시하고 송금을 중단한다.
- 완료가 HTTP 409 `PAY-018`이면 주문 상태를 다시 읽는다. 완료 응답만 끊겼다면 은행에서 이미 이체한 사실을 확인하고 **완료 API만** 다시 호출한다. 서버는 최초 완료 정보와 시각을 유지하고 메일 이벤트를 중복 생성하지 않는다.
- 환불 원인 확인과 고객 계좌 확인은 수동 절차다. 이 API에는 계좌번호 입력 필드가 없다.

## 연결 위치와 인수 조건

`hooks/admin/useAdminOrders.ts`에 시작·완료 mutation과 상태별 목록 query를 추가한다. `AdminDashboard.tsx`에서 새로고침 후에도 ‘환불 진행 중’ 주문을 찾을 수 있어야 한다. `OrderCard.tsx`에서 금액과 주문 번호를 포함해 이체 완료 확인을 받는다.

완료 기준은 관리자 두 명이 같은 주문에서 재시도와 환불 시작을 눌렀을 때 한쪽만 진행되는 것, 진행 도중 새로고침해도 이체 대상을 다시 찾을 수 있는 것, 완료 요청이 반복돼도 최초 결과만 보이고 이메일이 중복 예약되지 않는 것이다. [실패 주문 목록 화면](admin-failed-grants.md) · [관리자 결제 컨트롤러](../../item-service/src/main/java/com/comatching/item/infra/controller/admin/AdminPaymentController.java)
