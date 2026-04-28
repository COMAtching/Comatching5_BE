# 결제/주문 API 최종 명세 (Gateway 8080 기준)

## 0. 공통
### 0.1 Base URL
- 게이트웨이: `http://localhost:8080`

### 0.2 인증 방식
- 보호 API는 `accessToken` 쿠키가 필요합니다.
- 게이트웨이는 `accessToken` 쿠키를 읽어 내부적으로 `X-Member-*` 헤더를 주입합니다.
- 쿠키가 없거나 유효하지 않으면 게이트웨이에서 차단됩니다.

게이트웨이 인증 에러 예시:
```json
{
  "code": "GATEWAY-001",
  "status": 401,
  "message": "토큰이 누락되었습니다."
}
```

### 0.3 공통 응답 포맷
성공:
```json
{
  "code": "GEN-000",
  "status": 200,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {}
}
```

에러:
```json
{
  "code": "ITEM-002",
  "status": 400,
  "message": "상품을 찾을 수 없습니다.",
  "data": null
}
```

### 0.4 주문 상태
- `PENDING`: 승인 대기
- `APPROVED`: 승인 완료
- `REJECTED`: 거절 완료
- `CANCELED`: 취소
- `EXPIRED`: 만료 (요청 후 10분 경과)

---

## 1. (선행) 로그인 - 관리자/사용자
### 1.1 프론트 요청
- `POST /api/auth/login`

요청 body:
```json
{
  "email": "admin@test.com",
  "password": "1234"
}
```

### 1.2 서버 응답
- 성공 시 `Set-Cookie`로 `accessToken`, `refreshToken` 발급
- 응답 코드는 일반적으로 `302` (리다이렉트: `/main` 또는 `/onboarding`)

### 1.3 주요 에러코드
- `AUTH-008` 로그인 실패
- `AUTH-004` 계정 정지
- `AUTH-005` 계정 비활성
- `GEN-010` 인증되지 않은 사용자

---

## 2. 사용자 실명 수정
### 2.1 프론트 요청
- `PATCH /api/members/real-name`
- 쿠키: `accessToken` (ROLE_USER)

요청 body:
```json
{
  "realName": "홍길동"
}
```

### 2.2 서버 성공 응답
```json
{
  "code": "GEN-000",
  "status": 200,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": null
}
```

### 2.3 주요 에러코드
- `MEM-011` 실명 공백
- `MEM-001` 사용자 없음
- `GEN-011` 권한 없음
- `GATEWAY-001`, `GATEWAY-002`

---

## 3. [관리자] 상품 등록
### 3.1 프론트 요청
- `POST /api/v1/admin/shop/products`
- 쿠키: `accessToken` (ROLE_ADMIN)

요청 body:
```json
{
  "name": "매칭권 10개 (+옵션권 5개)",
  "description": "매칭권과 옵션권을 함께 충전해요.",
  "price": 9000,
  "displayOrder": 3,
  "isActive": true,
  "isBundle": true,
  "rewards": [
    {
      "itemType": "MATCHING_TICKET",
      "quantity": 10
    },
    {
      "itemType": "OPTION_TICKET",
      "quantity": 10
    }
  ],
  "bonusRewards": [
    {
      "itemType": "OPTION_TICKET",
      "quantity": 5
    }
  ]
}
```

필드 규칙:
- `description`: 필수, 공백 불가, 50자 이하
- `price`: 1 이상
- `displayOrder`: 0 이상, 낮을수록 먼저 노출
- `isBundle`: 번들 상품 여부, true이면 번들 조회 필터에 포함
- `rewards`: 실제 지급 구성품, 최소 1개 이상
- `bonusRewards`: 프론트 표시용 보너스 구성품, 실제 지급은 `rewards` 기준
- `bonusRewards.itemType`: 같은 요청의 `rewards`에 존재해야 함
- `bonusRewards.quantity`: 1 이상, 동일 `itemType`의 실제 지급 수량 이하
- `rewards`, `bonusRewards` 각각 동일 `itemType` 중복 불가

### 3.2 서버 성공 응답
```json
{
  "code": "GEN-000",
  "status": 200,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "id": 10,
    "name": "매칭권 10개 (+옵션권 5개)",
    "description": "매칭권과 옵션권을 함께 충전해요.",
    "price": 9000,
    "displayOrder": 3,
    "isActive": true,
    "isBundle": true,
    "rewards": [
      {
        "itemType": "MATCHING_TICKET",
        "itemName": "매칭권",
        "quantity": 10
      },
      {
        "itemType": "OPTION_TICKET",
        "itemName": "옵션권",
        "quantity": 10
      }
    ],
    "bonusRewards": [
      {
        "itemType": "OPTION_TICKET",
        "itemName": "옵션권",
        "quantity": 5
      }
    ]
  }
}
```

### 3.3 주요 에러코드
- `GEN-011` 권한 없음
- `GEN-003` 유효성 검증 실패
- `GEN-002` 입력값 오류 (설명/순서/중복 itemType, 가격/수량 오류, 보너스 구성 오류)
- `GATEWAY-001`, `GATEWAY-002`

---

## 4. [관리자] 상품 목록 조회
### 4.1 프론트 요청
- `GET /api/v1/admin/shop/products`
- `GET /api/v1/admin/shop/products?isBundle=true`
- `GET /api/v1/admin/shop/products?isBundle=false`
- 쿠키: `accessToken` (ROLE_ADMIN)

Query:
- `isBundle` (optional): `true`이면 번들 상품만, `false`이면 비번들 상품만 반환. 생략하면 전체 반환

### 4.2 서버 성공 응답
활성/비활성 전체 상품을 `displayOrder ASC, id ASC` 순서로 반환합니다. `isBundle`이 있으면 해당 여부로 필터링합니다.
```json
{
  "code": "GEN-000",
  "status": 200,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": [
    {
      "id": 10,
      "name": "매칭권 10개 (+옵션권 5개)",
      "description": "매칭권과 옵션권을 함께 충전해요.",
      "price": 9000,
      "displayOrder": 3,
      "isActive": true,
      "isBundle": true,
      "rewards": [
        {
          "itemType": "MATCHING_TICKET",
          "itemName": "매칭권",
          "quantity": 10
        },
        {
          "itemType": "OPTION_TICKET",
          "itemName": "옵션권",
          "quantity": 10
        }
      ],
      "bonusRewards": [
        {
          "itemType": "OPTION_TICKET",
          "itemName": "옵션권",
          "quantity": 5
        }
      ]
    },
    {
      "id": 11,
      "name": "판매 중지 상품",
      "description": "관리자에게만 보이는 비활성 상품입니다.",
      "price": 1000,
      "displayOrder": 99,
      "isActive": false,
      "isBundle": false,
      "rewards": [
        {
          "itemType": "MATCHING_TICKET",
          "itemName": "매칭권",
          "quantity": 1
        }
      ],
      "bonusRewards": []
    }
  ]
}
```

### 4.3 주요 에러코드
- `GEN-011` 권한 없음
- `GATEWAY-001`, `GATEWAY-002`

---

## 5. [관리자] 상품 삭제(판매 중지)
### 5.1 프론트 요청
- `DELETE /api/v1/admin/shop/products/{productId}`
- 쿠키: `accessToken` (ROLE_ADMIN)
- body: 없음

Path:
- `productId`: 판매 중지 처리할 상품 ID

### 5.2 서버 성공 응답
상품을 DB에서 실제 삭제하지 않고 `isActive=false`로 변경합니다.
```json
{
  "code": "GEN-000",
  "status": 200,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": null
}
```

### 5.3 주요 에러코드
- `GEN-007` 상품 없음
- `GEN-011` 권한 없음
- `GATEWAY-001`, `GATEWAY-002`

---

## 6. 상품 목록 조회
### 6.1 프론트 요청
- `GET /api/v1/shop/products`
- `GET /api/v1/shop/products?isBundle=true`
- `GET /api/v1/shop/products?isBundle=false`

Query:
- `isBundle` (optional): `true`이면 번들 상품만, `false`이면 비번들 상품만 반환. 생략하면 전체 활성 상품 반환

### 6.2 서버 성공 응답
활성 상품만 `displayOrder ASC, id ASC` 순서로 반환합니다. `isBundle`이 있으면 해당 여부로 필터링합니다.
```json
{
  "code": "GEN-000",
  "status": 200,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": [
    {
      "id": 10,
      "name": "매칭권 10개 (+옵션권 5개)",
      "description": "매칭권과 옵션권을 함께 충전해요.",
      "price": 9000,
      "displayOrder": 3,
      "isActive": true,
      "isBundle": true,
      "rewards": [
        {
          "itemType": "MATCHING_TICKET",
          "itemName": "매칭권",
          "quantity": 10
        },
        {
          "itemType": "OPTION_TICKET",
          "itemName": "옵션권",
          "quantity": 10
        }
      ],
      "bonusRewards": [
        {
          "itemType": "OPTION_TICKET",
          "itemName": "옵션권",
          "quantity": 5
        }
      ]
    }
  ]
}
```

### 6.3 주요 에러코드
- `GEN-099` 서버 내부 오류

---

## 7. 결제 주문 생성 (상품 ID 기반)
### 7.1 프론트 요청
- `POST /api/v1/shop/purchase/{productId}`
- 쿠키: `accessToken` (ROLE_USER)
- body: 없음

### 7.2 서버 성공 응답
```json
{
  "code": "GEN-000",
  "status": 200,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": null
}
```

### 7.3 주요 에러코드
- `ITEM-002` 상품 없음
- `ITEM-003` 비활성 상품
- `PAY-003` 이미 대기 주문 존재
- `PAY-004` 실명 없음
- `PAY-010` username(닉네임) 없음
- `GEN-005` path variable 타입 오류
- `GATEWAY-001`, `GATEWAY-002`

---

## 8. 내 대기 주문 상태 조회
### 8.1 프론트 요청
- `GET /api/v1/shop/purchase/status`
- 쿠키: `accessToken`

### 8.2 서버 성공 응답
PENDING:
```json
{
  "code": "GEN-000",
  "status": 200,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "status": "PENDING"
  }
}
```

NONE:
```json
{
  "code": "GEN-000",
  "status": 200,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "status": "NONE"
  }
}
```

### 8.3 주요 에러코드
- `GATEWAY-001`, `GATEWAY-002`

---

## 9. [관리자] 대기 주문 목록 조회
### 9.1 프론트 요청
- `GET /api/v1/admin/payment/requests`
- 쿠키: `accessToken` (ROLE_ADMIN)

### 9.2 서버 성공 응답
```json
{
  "code": "GEN-000",
  "status": 200,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": [
    {
      "requestId": 101,
      "memberId": 25,
      "requestedItemName": "매칭권 10개 (+옵션권 5개)",
      "requesterRealName": "홍길동",
      "requesterUsername": "길동이",
      "optionTicketQty": 10,
      "matchingTicketQty": 10,
      "requestedPrice": 9000,
      "expectedPrice": 9000,
      "status": "PENDING",
      "requestedAt": "2026-04-23T14:10:00",
      "expiresAt": "2026-04-23T14:20:00"
    }
  ]
}
```

### 9.3 주요 에러코드
- `GEN-011` 권한 없음
- `GATEWAY-001`, `GATEWAY-002`

---

## 10. [관리자] 승인
### 10.1 프론트 요청
- `POST /api/v1/admin/payment/approve/{requestId}`
- 쿠키: `accessToken` (ROLE_ADMIN)
- body: 없음

### 10.2 서버 성공 응답
```json
{
  "code": "GEN-000",
  "status": 200,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": null
}
```

### 10.3 주요 에러코드
- `PAY-001` 요청 없음
- `PAY-002` 이미 처리됨 (동시 승인 포함)
- `PAY-006` 요청 만료
- `GEN-011` 권한 없음
- `GATEWAY-001`, `GATEWAY-002`

---

## 11. [관리자] 거절
### 11.1 프론트 요청
- `POST /api/v1/admin/payment/reject/{requestId}`
- 쿠키: `accessToken` (ROLE_ADMIN)
- body: 없음

### 11.2 서버 성공 응답
```json
{
  "code": "GEN-000",
  "status": 200,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": null
}
```

### 11.3 주요 에러코드
- `PAY-001` 요청 없음
- `PAY-002` 이미 처리됨
- `PAY-006` 요청 만료
- `GEN-011` 권한 없음
- `GATEWAY-001`, `GATEWAY-002`

---

## 12. 관리자 실시간 모니터링 (STOMP)
### 12.1 연결 정보
- 모니터 페이지: `GET /admin-payment-monitor.html`
- SockJS endpoint: `/ws/payment`
- STOMP subscribe topic: `/topic/admin/orders`

### 12.2 서버 푸시 공통 포맷
```json
{
  "eventId": 1234,
  "eventType": "ORDER_CREATED",
  "occurredAt": "2026-04-23T14:10:00",
  "payload": {}
}
```

### 12.3 `ORDER_CREATED.payload`
```json
{
  "orderId": 101,
  "memberId": 25,
  "requestedItemName": "매칭권 10개 (+옵션권 5개)",
  "requesterRealName": "홍길동",
  "requesterUsername": "길동이",
  "optionTicketQty": 10,
  "matchingTicketQty": 10,
  "requestedPrice": 9000,
  "expectedPrice": 9000,
  "status": "PENDING",
  "requestedAt": "2026-04-23T14:10:00",
  "expiresAt": "2026-04-23T14:20:00"
}
```

### 12.4 `ORDER_STATUS_CHANGED.payload`
```json
{
  "orderId": 101,
  "fromStatus": "PENDING",
  "toStatus": "APPROVED",
  "decidedAt": "2026-04-23T14:12:00",
  "decidedByAdminId": 1,
  "reason": null
}
```
