# Payment 결제·Kafka 계약

Order는 Saga와 구매 기한·재고 확정/해제를 조율합니다. Payment는 일반 PostgreSQL/RDS의 자기 결제 원본·명령 이력·결과 Outbox만 관리하고 Product/Order 테이블을 읽지 않습니다. 모든 변경 테이블은 `payment` 스키마이며 DB 계정도 분리합니다.

## 브라우저 연동

1. 구매 버튼은 PurchaseGateway를 통해 Order에 주문 생성 요청을 보내고 모든 옵션 확보 성공을 기다립니다. 주문 응답의 `orderId`는 내부 UUID, `orderNumber`는 `SD-` + UUID입니다.
2. 일반 Gateway에서 BUYER Bearer로 `GET /api/v1/payments/client-configuration`을 호출해 공개 `clientKey`를 받습니다. 실제 시크릿 키는 서버에만 있습니다. 응답은 no-store입니다.
3. 해당 키 종류에 맞는 토스 SDK로 결제 인증을 요청합니다. SDK의 `orderId`에는 **Order 응답의 orderNumber**, 결제 금액에는 **totalAmount**를 사용합니다. 성공/실패 URL은 프론트 라우트이며 서버 승인 완료 응답이 아닙니다.
4. 인증 성공으로 받은 paymentKey를 PurchaseGateway의 `POST /api/v1/orders/{내부 orderId}/payment-attempts`에 전송합니다. 기존 요청 계약은 requestKey(UUID), paymentKey, agreed=true, termsVersion입니다. 결제 금액을 브라우저 값으로 덮어쓰지 않습니다.
5. 프론트는 `GET /api/v1/orders/{내부 orderId}/checkout`으로 Command 확정 상태를 확인합니다. successUrl 도착이나 Query 반영 지연을 주문 성공·실패 판정으로 사용하지 않습니다.

실제 프론트 코드는 이번 저장소에 없습니다. 따라서 키 설정만으로 카드/간편결제 인증이 자동 실행되지는 않습니다. 가상 테스트 결제와 무통장 가상계좌 입금은 다른 개념이며, 입금대기/부분환불/구매자 취소·반품 흐름은 이번 구현 범위가 아닙니다.

## 명령

토픽 `stockdrop.payment.commands.private.v1`, key=paymentAttemptId UUID. JSON은 eventId, schemaVersion=1, type=PAYMENT_COMMAND, action(APPROVE/CHECK/CANCEL), orderId(내부 UUID), paymentAttemptId, commandVersion, occurredAt, amount(정수 원), expiresAt(최초 확보 만료), cancellationRequested입니다. APPROVE에만 paymentKey(최대 200자)를 넣습니다. CANCEL은 cancellationRequested=true입니다.

원본·명령 eventId/hash·결과 Outbox는 같은 트랜잭션입니다. 같은 eventId/내용은 무시하고 다른 내용 재사용은 DLT로 보냅니다. 같은 결제 시도·버전의 다른 명령도 거부합니다. orderId별 결제 시도는 하나이고 금액·만료 시각·paymentKey를 바꿀 수 없습니다. commandVersion은 순차 명령 번호이며 최신 스냅샷 버전처럼 오래된 APPROVE를 무조건 버리지 않습니다. CHECK가 앞서 와도 이후 APPROVE를 처리하고, CANCEL이 앞서 오면 승인 차단 의도를 유지합니다.

## 공급자 처리와 복구

Kafka 소비는 DB 저장만 합니다. 별도 작업이 짧은 DB 임대 트랜잭션으로 일을 가져온 뒤 잠금 밖에서 토스를 호출합니다. 30초 임대 만료로 복구하며 오래된 임대 결과는 반영하지 않습니다. 승인 HTTP 시작 의도를 먼저 저장해, 승인 응답 유실을 미승인으로 착각하지 않습니다.

- 최초 승인: POST `/v1/payments/confirm`, orderId=`SD-`+내부 UUID, amount=Order 원본 금액.
- 재확인: GET `/v1/payments/{paymentKey}`. 주문서형/결제창 키가 지원하지 않는 orderId 조회는 사용하지 않습니다. CHECK가 먼저 와서 키가 없으면 UNKNOWN으로 기다립니다.
- 보상: POST `/v1/payments/{paymentKey}/cancel`, cancelAmount 생략으로 전액 취소. 사용자 환불 요청 API가 아닙니다.
- 승인/보상 멱등 키는 각각 `stockdrop-approve-{attemptId}`, `stockdrop-cancel-{attemptId}`이며 재전송 body도 같습니다.
- 서명 자격은 Basic(secretKey+콜론의 Base64), HTTPS 공식 호스트만 허용하고 redirect를 따르지 않습니다. 기본 request timeout은 3초입니다. 테스트 프로필에서만 루프백 HTTP 대역을 허용합니다.

조회·승인 응답의 paymentKey/orderId/총 금액/KRW/상태를 검증합니다. 승인 시각은 UTC 밀리초로 저장합니다. DONE만 APPROVED, 전액 CANCELED만 CANCELED, ABORTED/EXPIRED만 DECLINED입니다. 404·5xx·부분취소·미확정 상태·금액 불일치·타임아웃은 UNKNOWN이며 응답 원문을 로그에 남기지 않습니다.

5분 이후 새 승인은 하지 않습니다. 외부 승인을 전혀 시작하지 않고 기한이 지났다면 로컬 DECLINED로 종료합니다. 이미 시작한 시도는 기한이 지나도 조회하며 기한 내 실제 승인 결과는 지연 도착해도 보존합니다. 기한 후 실제 승인은 APPROVED와 실제 시각을 Order에 전달하고 Order가 보상을 요청합니다. 취소 응답이 불명이면 기존 APPROVED를 실패로 바꾸지 않고 CANCELED 확인까지 재조회합니다.

## 결과와 발행

토픽 `stockdrop.payment.results.private.v1`, key=paymentAttemptId. eventId, schemaVersion=1, type=PAYMENT_RESULT, orderId, paymentAttemptId, amount, version(시도별 증가), status(PENDING/UNKNOWN/APPROVED/DECLINED/CANCELED), approvedAt, occurredAt입니다. approvedAt은 APPROVED 필수이며 CANCELED에서는 기존 승인 시각을 보존할 수 있습니다. paymentKey, 클라이언트/시크릿 키, 개인정보는 결과에 포함하지 않습니다.

원본 결과와 Outbox를 함께 커밋하고 별도 발행기가 `FOR UPDATE SKIP LOCKED`로 분배합니다. Kafka ack 후 published_at 기록이며 실패 시 재시도합니다. ack 후 DB 커밋 실패는 같은 eventId 중복 발행을 만들 수 있으므로 Order가 멱등·버전 검증합니다. 완료 표시는 소비 완료가 아닙니다. 원본 상태가 APPROVED/종료 상태에서 UNKNOWN이나 과거 상태로 역행하지 않게 검사합니다.

영구 명령 오류는 동일 파티션의 `stockdrop.payment.commands.private.v1.DLT`로 발행 성공 후 처리합니다. DB/DLT 발행 장애는 offset을 넘기지 않고 재시도합니다. 원문 명령과 DLT에는 거래 식별값이 있어 반드시 비공개 ACL을 사용합니다. 운영 TLS/SASL/ACL, DLT 교정 재처리·보존·디스크 복구·부하 검증은 별도입니다. 단일 Kafka 디스크 유실을 Outbox의 완료 기록만으로 복구한다고 주장하지 않습니다.

결과 불명 장기 지속, last_failure_code, DB 임대 만료, Outbox pending/최장 지연/attempts, Kafka 소비 지연·DLT를 운영에서 감시해야 합니다. 원인을 모르는데 강제로 실패·재고 반환하거나 결제 이력/Outbox를 삭제하지 않습니다.

공식 근거: [승인·조회·취소 API](https://docs.tosspayments.com/reference), [인증과 멱등 헤더](https://docs.tosspayments.com/reference/using-api/authorization), [키 종류](https://docs.tosspayments.com/reference/using-api/api-keys).
