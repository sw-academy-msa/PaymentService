# 판매 등록 광고 테스트 결제

광고 결제는 구매 주문 및 멤버십과 분리된 일회성 금융 원본이다. `AD-<paymentId>`만 사용하며 구매 주문 파서에 전달하지 않는다. 토스 키는 기존 TossConfig에서 검증한 `test_` 쌍만 사용한다. 실제 토스 호출은 로컬 테스트에 포함하지 않는다.

## API

PurchaseGateway를 통해 SELLER이며 user 주체인 본인만 접근한다. 모든 응답은 공통 ApiResponse이며 Cache-Control: no-store를 사용한다. ADMIN은 판매자 API를 사용할 수 없다.

| Method | 경로 | 의미 |
| --- | --- | --- |
| GET | `/api/v1/payments/advertising/{paymentId}/checkout` | 서버 확정 금액, 공개 clientKey, AD 주문번호, 고정 귀환 주소 |
| GET | `/api/v1/payments/advertising/{paymentId}` | 금융 복구 조회, paymentKey 미포함 |
| POST | `/api/v1/payments/advertising/{paymentId}/completion` | Idempotency-Key UUID와 `{paymentKey,tossOrderId}`, 202 비동기 승인 접수 |

202는 PAID나 상품 IN_REVIEW를 의미하지 않는다. PENDING은 결과 불명과 공급자 조회 중 상태를 포함한다. PAID 후 Product의 SUBMITTED를 별도로 확인한다. 승인 금액은 Payment DB 원본만 사용하고 브라우저 금액을 받지 않는다. 동일 completion 키와 본문은 재전송 가능하고 다른 키나 본문은 409다. 소유권은 403, 대상 없음은 404, 상태 충돌은 409, 기능 미준비는 503이다.

귀환 주소는 `https://stockdrop-app.vercel.app/#/seller/advertising/toss/success`와 fail만 허용한다. 클라이언트가 귀환 URL을 지정할 수 없다.

## 내부 이벤트 계약

Product 전용 producer만 `product.advertising.commands.v1`에 쓰고 Payment만 읽도록 Kafka ACL을 배포한다. Payment만 `payment.advertising.results.v1`에 쓰고 Product만 읽는다. DLT는 명령 토픽과 같은 파티션 수로 미리 생성한다. 자동 토픽 생성은 사용하지 않는다.

공통 envelope는 eventId, eventType, schemaVersion=1, aggregateId=applicationId, aggregateVersion, occurredAt, correlationId, causationId, payload다. Kafka key는 applicationId다. 신규 회차는 모든 UUID를 새로 발급한다. 수신 영수증은 eventId와 applicationId+aggregateVersion을 유일하게 보관한다. 동일 이벤트의 다른 본문은 거절하며 역순 취소도 영속 tombstone으로 보관한다.

- AdvertisingPaymentRequested payload: applicationId, submissionId, productId, sellerId, productVersion, quoteHash(SHA-256 소문자 hex), amount(최소 1000), currency=KRW, formulaVersion=V1, expiresAt. 금액 산정은 Product 소유다. expiresAt은 DB 정밀도와 일치하도록 밀리초로 고정한다.
- AdvertisingPaymentCancellationRequested payload: applicationId, submissionId, productId, sellerId, paymentId(아직 준비 전이면 null), reasonCode. 허용 사유는 PRODUCT_REJECTED, SUBMISSION_FAILED, SELLER_ABANDONED다. Product는 PAID 이후 판매자 abandon을 자동 환불로 변환하지 않는다.
- 결과 AdvertisingPaymentPrepared 및 AdvertisingPaymentStateChanged payload: applicationId, submissionId, paymentId, productId, sellerId, amount, currency, quoteHash, formulaVersion, paymentStatus, resultVersion, cancellationRequested, approvedAt, canceledAt, failureCode. paymentKey 및 비밀 키는 포함하지 않는다.

취소 또는 접수 중단으로 공급자 승인을 시작하지 않은 원본은 FAILED 및 providerState=NOT_STARTED다. cancellationRequested가 true인 FAILED는 이동한 금액 없이 종료된 회차이며 Product가 편집 잠금을 해제할 수 있다. CANCELED는 실제 승인된 금액이 전액 취소됐음을 확인한 경우에만 사용한다. 취소 미확정은 PAID를 유지하고 재조회한다. 예전 회차 취소는 새 회차 결제를 변경하지 않는다.

## 금융 처리와 복구

claim 트랜잭션에서 확인 시작과 30초 임대를 저장하고 커밋한 뒤 토스를 호출한다. complete는 동일한 유효 임대만 반영한다. 임대 만료 후에는 같은 토스 멱등 키로 조회 및 복구하며 결과 불명 상태를 확정 실패로 바꾸지 않는다. 승인 이력이 불명인 만료 회차도 조회를 계속한다.

승인 키는 `stockdrop-ad-approve-<paymentId>`, 전액 취소 키는 `stockdrop-ad-cancel-<paymentId>`다. 공급자 취소 사유는 고정 문자열이며 사용자 반려 원문을 전달하지 않는다. DONE은 원본 orderId, paymentKey, KRW, 총액 및 잔액을 검증한다. CANCELED는 잔액 0일 때만 확정한다. 401, 409, 429, 5xx와 전송 실패는 불명으로 남긴다.

준비 및 결과 저장은 기존 Outbox와 같은 트랜잭션이다. 기존 Outbox publisher를 켜야 결과가 발행된다. 광고 미확정 원본 및 취소 대기는 기존 Account 탈퇴 memberships clearance의 pendingMemberships 수에 포함한다. 계정별 advisory lock과 탈퇴 fence가 새 준비와 탈퇴 확정의 경쟁을 막는다.

## 플래그와 이행

| 속성 | 환경 변수 | 기본값 |
| --- | --- | --- |
| payment.advertising.enabled | PAYMENT_ADVERTISING_ENABLED | false |
| payment.advertising.consumer-enabled | PAYMENT_ADVERTISING_CONSUMER_ENABLED | false |
| payment.advertising.processing-enabled | PAYMENT_ADVERTISING_PROCESSING_ENABLED | false |

SSM 이름은 `/stockdrop/prod/payment-service/PAYMENT_ADVERTISING_ENABLED`, `/stockdrop/prod/payment-service/PAYMENT_ADVERTISING_CONSUMER_ENABLED`, `/stockdrop/prod/payment-service/PAYMENT_ADVERTISING_PROCESSING_ENABLED`다. Infra의 기존 서비스별 환경변수 로딩 규칙에 맞춘 이름이며 실제 파라미터는 생성하거나 조회하지 않는다. V5__create_advertising_payments.sql은 신규 테이블과 인덱스만 추가한다. 기존 V1~V4는 변경하지 않는다.

배포는 V5와 전용 토픽 및 ACL, Payment 기능 OFF, Product 소비와 회차, Gateway 최소 허용 경로, 숨김 FE 순이다. 활성화는 양쪽 consumer 및 복구, 기존 Outbox publisher, Payment 신규 접수, Product 신규 접수, FE 순으로 수행한다. 롤백은 FE 및 신규 접수만 먼저 OFF하고 미확정 금융 원본과 취소 및 Outbox가 drain될 때까지 소비와 복구를 유지한다. 운영 배포와 활성화는 이번 작업에서 수행하지 않는다.

로컬 검증은 AdvertisingFlowIntegrationTest의 격리 PostgreSQL과 AdvertisingTossClientTest의 합성 HTTP 서버를 사용한다. 공개 계약은 openapi/payment.openapi.json이다. 실제 테스트 위젯 결제와 브라우저 E2E는 Product 및 FE 연동 후 별도로 확인해야 한다.
