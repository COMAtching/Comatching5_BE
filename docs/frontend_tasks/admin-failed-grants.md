# 프론트 개발 과제 · 관리자가 지급 실패 주문을 찾아 재시도하기

**완료 기준은 관리자가 주문 번호를 미리 몰라도 결제 승인 관리 화면에서 실패한 주문을 찾고, 입금 재승인 없이 아이템 지급을 다시 요청한 뒤 결과까지 확인할 수 있는 것이다.**

## 현재 관리자가 확인할 수 있는 범위

`/adminpage/dashboard`의 목록 API는 아직 만료되지 않은 승인 대기 주문(`status=PENDING`)만 반환한다. 입금 승인 후 아이템 지급이 실패한 주문은 `status=APPROVED`, `grantStatus=FAILED`이므로 목록을 새로고침해도 나타나지 않는다. 화면이 구독하는 웹소켓 이벤트도 주문 생성과 승인 상태 변경만 전달한다. 카드에 보이는 ‘승인 완료’는 입금을 확인했다는 뜻이다.

관리자는 아래 실패 목록 API로 현재 재시도할 주문을 조회할 수 있다. 실패 원인은 `item-service`의 ‘주문 지급 오류’ 로그에서 주문 번호와 시도 번호로 찾는다. 로그의 `markedFailed=1`은 해당 시도에서 실패 상태를 기록했다는 뜻이다. DB에서 같은 목록을 대조할 때의 조건은 다음과 같다.

```sql
SELECT id, grant_attempt
FROM comatching_item.purchase_order
WHERE status = 'APPROVED' AND grant_status = 'FAILED' AND refund_status = 'NONE'
ORDER BY id DESC;
```

## 화면에 연결할 관리자 API

기준 경로는 `/api/v1/admin/payment`이며 관리자 권한이 필요하다.

| 용도 | 메서드와 상대 경로 | 구현 상태와 응답 |
| --- | --- | --- |
| 실패 주문 발견 | `GET /grants?grantStatus=FAILED&page=0&size=20` | 구현됨. 주문 번호 내림차순으로 `APPROVED / FAILED`만 반환한다. 각 행에는 `orderId`, `requestedItemName`, `status`, `grantStatus`, `attempt`가 있다. 실패 건수는 `data.totalElements`에서 읽는다. |
| 주문 한 건의 현재 상태 확인 | `GET /requests/{orderId}/grant` | 구현됨. 응답의 `data`에 `orderId`, `status`, `grantStatus`, `attempt`, `refundStatus`가 들어 있다. |
| 실패한 지급 재시도 | `POST /requests/{orderId}/grant/retry` | 구현됨. 요청 본문 없이 호출한다. HTTP `202 Accepted`는 재시도가 접수됐다는 뜻이다. 이미 대기 중이거나 처리된 주문은 HTTP `409`와 `PAY-015`를 반환한다. |

목록의 기본값은 `grantStatus=FAILED`, `page=0`, `size=20`이고 페이지 크기는 최대 100이다. 다른 지급 상태, 음수 페이지, 0 이하 크기는 HTTP 400이다. 브라우저는 기존 승인 대기 목록과 같은 `data.content`, `currentPage`, `size`, `totalElements`, `totalPages`, `hasNext`, `hasPrevious` 구조를 사용한다. 빈 목록도 HTTP 200이며, 지급 재시도가 접수되어 `PENDING`으로 전환된 주문은 다음 조회에서 빠진다.

## 프론트 작업과 인수 조건

1. **실패 주문 찾기.** `AdminDashboard.tsx`에 ‘지급 실패’ 탭을 추가하고 실패 건수를 표시한다. `useAdminOrders.ts`에 실패 목록 전용 조회 훅과 캐시 키를 둔다. 화면을 처음 연 관리자도 백엔드 목록에서 과거 실패 주문을 볼 수 있어야 한다.
2. **지급만 재시도하기.** 실패 주문에 ‘입금 승인 완료 · 지급 실패’와 시도 번호를 표시한다. ‘지급 재시도’ 버튼은 재시도 API를 호출하고, 응답을 기다리는 동안 비활성화한다. `202`를 받으면 ‘재시도 접수’를 표시한다.
3. **결과 확인하기.** 실패 목록을 갱신하면 접수된 주문은 목록에서 빠진다. 화면은 방금 접수한 주문 번호를 보관해 단건 API로 상태를 다시 조회한다. `PENDING`이면 조회를 이어가고, `GRANTED`이면 ‘지급 완료’, 다시 `FAILED`이면 ‘지급 실패’와 재시도 버튼을 표시한다. 다른 관리자가 먼저 처리해 `409`가 온 경우에도 최신 상태를 조회한다.

배경과 재현 테스트는 [지급 트랜잭션 문제 해결 기록](../payment-grant-rollback.md)에 있다. 현재 계약은 [관리자 결제 컨트롤러](../../item-service/src/main/java/com/comatching/item/infra/controller/admin/AdminPaymentController.java)에서 확인할 수 있다.

환불을 시작한 주문은 실패 목록에서 빠진다. 진행 목록에서 이어서 처리하는 화면은 [수동 환불 프론트 과제](admin-order-refunds.md)에 정리했다.
