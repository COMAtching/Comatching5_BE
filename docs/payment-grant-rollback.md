# 결제 승인을 롤백했는데 매칭권은 왜 남았을까?

기존 승인 흐름을 통합 테스트로 재현했더니 주문은 승인 대기(`PENDING`)로 돌아갔고 지급 원장은 0건이었다. 그런데 회원의 재고와 충전 이력은 각각 1건씩 남았다. **주문과 지급 원장을 저장한 트랜잭션보다 재고를 저장한 트랜잭션이 먼저 커밋됐기 때문이다.** 같은 주문을 다시 승인하면 이미 받은 품목을 한 번 더 받을 수 있었다.

## 매칭권 2장과 옵션권 1장을 산 경우

기존 `AdminPaymentServiceImpl.approvePurchase()`는 품목마다 지급 원장을 쓴 다음 `itemService.addItem()`을 호출했다. 지급 원장은 “이 주문의 이 품목을 지급했다”는 기록이고, 재고는 회원이 실제로 사용할 수 있는 수량이다. 첫 품목 지급 직후 예외가 발생한다고 가정하면 다음과 같다.

| 순서 | 승인 트랜잭션 A | 재고 트랜잭션 B |
| --- | --- | --- |
| 1 | 주문을 승인 상태로 바꾸고 매칭권 지급 원장을 쓴다. 아직 커밋 전이다. | |
| 2 | `addItem()`을 호출하고 잠시 중단된다. | 매칭권 2장과 충전 이력을 저장한 뒤 커밋한다. |
| 3 | 다시 실행되지만 후속 작업에서 예외가 발생한다. 승인과 원장이 함께 롤백된다. | 이미 커밋한 매칭권 2장과 이력은 남는다. |

다시 승인하면 기존 코드는 `(주문 ID, 주문 품목 ID)`로 원장을 조회한다. 3단계에서 원장이 사라졌으니 매칭권을 미지급으로 판단한다. 이때 2장을 더 지급하면 회원은 총 4장을 받는다. 원장의 유일 제약도 비교할 행이 사라진 경우에는 중복을 막을 수 없다.

## 왜 재고만 별도로 커밋됐나

서버 1에서 42번 회원의 매칭권을 차감하는 동안 서버 2에서 관리자가 같은 매칭권을 조정할 수 있다. 두 서버가 같은 Redis 키 `item:inventory:42:MATCHING_TICKET`를 잠그면 한 번에 한쪽만 재고를 바꾼다. 같은 회원의 옵션권은 다른 키를 쓴다.

기존 단일 품목 경로에서는 Spring이 `@DistributedLock`이 붙은 `addItem()` 호출을 가로챈다. `DistributedLockAspect`가 Redis 락을 얻고, 별도 Spring 빈인 `AopForTransaction.proceed()`를 호출한다. 여기에 선언된 **`REQUIRES_NEW`** 때문에 승인 트랜잭션 A가 잠시 중단되고 재고 트랜잭션 B가 시작된다. B의 커밋 또는 롤백이 끝나면 AOP가 락을 푼다. 다음 서버가 락을 얻을 때는 앞선 재고 변경이 이미 확정돼 있다. 이 순서를 묶음 주문에도 품목마다 적용하면서, 주문 전체가 실패해도 첫 품목만 확정될 수 있었다. [분산락 AOP](../common-module/src/main/java/com/comatching/common/aop/DistributedLockAspect.java) · [트랜잭션 선언](../common-module/src/main/java/com/comatching/common/aop/AopForTransaction.java)

## 어떻게 바꿨나

1. **승인할 때는 지급 대기까지 기록한다.** 승인 트랜잭션에서 주문 상태를 `APPROVED`, 지급 상태를 `PENDING`으로 함께 저장한다. 승인이 롤백되면 지급 대기도 사라져서 워커가 처리할 주문이 생기지 않는다. [승인 처리](../item-service/src/main/java/com/comatching/item/domain/product/service/AdminPaymentServiceImpl.java)
2. **승인이 커밋된 뒤 워커가 두 품목의 락을 모두 얻는다.** 워커는 회원과 품목으로 락 키를 만들고, 중복을 제거한 뒤 일정한 순서로 획득한다. 락을 모두 얻은 다음 별도 Spring 빈의 `grant()`를 호출한다. 지급 트랜잭션이 끝날 때까지 두 락을 유지한다. [지급 워커](../item-service/src/main/java/com/comatching/item/domain/order/service/OrderGrantDispatcher.java)
3. **묶음 전체를 한 트랜잭션에서 지급한다.** `grant()`는 주문 행을 잠그고 지급 상태와 시도 번호를 다시 확인한다. 각 품목의 원장을 쓰고 `addUnderInventoryLock()`으로 재고와 충전 이력을 쌓은 뒤 지급 상태를 `GRANTED`로 바꾼다. 이 재고 메서드는 `MANDATORY`이므로 호출자의 지급 트랜잭션에 참여한다. 옵션권 이력 저장에서 예외가 나면 앞서 쓴 매칭권 원장·재고·이력까지 함께 롤백된다. 워커는 롤백 후 해당 지급 시도를 `FAILED`로 기록한다. 주문은 이미 승인됐으므로 지급만 재시도할 수 있다. [묶음 지급](../item-service/src/main/java/com/comatching/item/domain/order/service/OrderGrantTransaction.java) · [재고 반영](../item-service/src/main/java/com/comatching/item/domain/item/service/ItemServiceImpl.java)

## 지급에 실패한 주문은 어떻게 재시도하나

워커는 `APPROVED / PENDING` 주문만 조회한다. `APPROVED / FAILED` 주문은 관리자가 재시도를 요청할 때까지 대기한다. 예를 들어 123번 주문의 첫 지급이 실패했다면 다음 순서로 복구한다.

| 순서 | 누가 무엇을 하나 | 지급 상태 | 시도 번호 |
| --- | --- | --- | --- |
| 1 | 관리자가 `GET /api/v1/admin/payment/requests/123/grant`로 상태를 확인한다. | `FAILED` | 1 |
| 2 | 관리자가 `POST /api/v1/admin/payment/requests/123/grant/retry`를 호출한다. 서버가 지급을 다시 예약하고 `202 Accepted`를 반환한다. | `PENDING` | 2 |
| 3 | 워커가 다음 조회 때 이 주문을 찾아 매칭권 2장과 옵션권 1장 전체를 다시 지급한다. | 성공하면 `GRANTED`, 다시 실패하면 `FAILED` | 2 |

세 단계 내내 입금 승인 상태는 `APPROVED`다. 첫 시도의 재고가 전부 롤백됐으므로 두 번째 시도도 첫 품목부터 시작한다. 관리자는 같은 GET으로 최종 결과를 확인할 수 있다.

재시도 요청은 현재 지급 상태가 `FAILED`일 때만 반영한다. 첫 요청으로 이미 `PENDING`이 된 상태에서 한 번 더 요청하면 `409 Conflict`가 반환된다. 시도 번호는 늦게 도착한 실패 처리를 구분한다. 예를 들어 2차 지급이 대기 중인데 1차 워커가 뒤늦게 실패를 기록하려 해도, DB의 시도 번호가 달라 2차 작업은 그대로 진행된다. [관리자 API](../item-service/src/main/java/com/comatching/item/infra/controller/admin/AdminPaymentController.java) · [조건부 상태 변경 쿼리](../item-service/src/main/java/com/comatching/item/domain/order/repository/OrderRepository.java)

## 통합 테스트에서 확인한 결과

| 재현 조건 | DB에서 확인한 결과 |
| --- | --- |
| 기존 방식으로 첫 품목을 지급한 뒤 승인 트랜잭션에서 예외 발생 | 주문 `PENDING`, 원장 0건, 재고와 이력 각 1건 |
| 변경 후 승인 트랜잭션 자체를 롤백하고 워커 실행 | 주문 `PENDING`, 지급 `NOT_REQUESTED`, 원장·재고·이력 모두 0건 |
| 승인 후 두 번째 품목의 충전 이력 저장을 강제로 실패시킴 | 주문 `APPROVED`, 지급 `FAILED`, 원장·재고·이력 모두 0건 |
| 실패한 지급을 재시도하고 워커 두 개가 같은 주문을 동시에 처리 | 지급 `GRANTED`, 두 품목의 원장과 이력 각 2건, 총 지급 수량 3장 |

MySQL과 Redis를 사용하는 [OrderGrantBoundaryIT](../item-service/src/test/java/com/comatching/item/domain/order/service/OrderGrantBoundaryIT.java)에서 재현할 수 있다.

```sh
./gradlew :item-service:integrationTest --tests '*OrderGrantBoundaryIT'
```
