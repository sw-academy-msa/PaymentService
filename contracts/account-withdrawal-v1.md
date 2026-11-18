# 회원탈퇴 원본 확인과 거래 차단 v1

## 정책

배송 완료 전 주문, 종료 전 승인 상품과 심사 중 상품, 활성 재고 확보, 결과 불명 결제와 보상 처리 중 결제가 있으면 탈퇴를 거절합니다.
수수료 계산과 이력 기능은 후속 범위이며, 완료된 판매 이력이나 수수료 기능 부재만으로 탈퇴를 차단하지 않습니다.
Order의 pendingSettlements 필드는 기존 내부 계약 호환을 위해 0을 반환합니다.
Account는 별도로 Payment 원본의 결과 불명 결제와 보상 처리 여부를 계속 확인합니다.
초안과 반려 상품은 단독 차단 사유가 아니며, 탈퇴 후 자동 삭제하는 정책을 추가하지 않습니다.
확보 5분, 결과 불명 결제 확인과 Order Saga의 확정/보상 책임은 유지합니다.

공개 API는 기존 GET /api/v1/accounts/me/withdrawal-eligibility와 DELETE /api/v1/accounts/me를 유지합니다.
GET은 안내용 시점 조회이며 실제 탈퇴 승인의 근거로 재사용하지 않습니다.
현재 비밀번호와 confirmed=true는 기존 요청 DTO로 확인합니다.
거래가 있으면 409 ACCOUNT_WITHDRAWAL_BLOCKED, 확인 불능이나 준비 작업 충돌은 503 ACCOUNT_WITHDRAWAL_CHECK_UNAVAILABLE입니다.
verified=false의 건수를 0으로 추정하지 않습니다.

## 처리 순서

1. Account 로컬 트랜잭션에서 현재 비밀번호와 상태, 기존 탈퇴 이력을 확인합니다.
2. account.withdrawal_operations에 120초 CHECKING 작업을 저장합니다. 같은 계정에는 하나만 준비합니다.
3. HTTP로 Order와 Product에 같은 operationId의 영속 차단을 요청한 뒤 각 서비스 원본을 집계합니다.
4. 진행 주문과 판매가 모두 없을 때 Order의 결제 참조를 100건씩 끝까지 조회하고 Payment 원본 결과를 대조합니다.
5. 모두 가능하면 CLEARED로 기록합니다. 확인 중 진행이 있으면 ABORTING으로 바꾸고 양쪽 차단을 해제합니다.
6. Account의 최종 로컬 트랜잭션에서 비밀번호와 상태를 다시 확인하고 유효한 CLEARED 작업을 COMMITTED로 바꿉니다.
   같은 트랜잭션에서 WITHDRAWN, 역할/알림/광고 동의 해제, Refresh 세션 폐기와 최초 탈퇴 이력을 저장합니다.
7. 성공한 차단은 계속 유지해 과거 Access JWT로 새 주문/상품 심사 제출/확보를 만들지 못하게 합니다.

모든 네트워크 호출은 DB 트랜잭션 밖에서 수행합니다. 서비스 간 SQL JOIN이나 공유 트랜잭션은 없습니다.
Product와 Order의 일반 거래는 PostgreSQL 계정별 공유 advisory lock을, 차단/해제는 같은 키의 배타 잠금을 사용합니다.
기존 거래가 커밋/롤백하기 전에는 탈퇴 차단을 완료하지 않습니다. 일반 거래끼리를 계정별로 전역 직렬화하지 않습니다.
복수 계정은 UUID 정렬 순서로 잠근 뒤 기존 상품 잠금을 획득합니다. 재고 확정/해제와 복구 작업은 막지 않습니다.

## 장애와 회수

외부 오류에서는 계정과 Refresh를 유지합니다. ABORTING은 10초 후 다시 시도하고 작업자는 기본 5초마다 최대 25개를 확인합니다.
프로세스가 멈춘 CHECKING/CLEARED는 준비 임대 만료 후 ABORTING으로 회수합니다. 이미 COMMITTED인 차단은 회수하지 않습니다.
대량 결제 확인 중에는 성공한 각 HTTP 응답 뒤 준비 임대를 갱신합니다. 만료된 준비는 갱신/커밋할 수 없습니다.
탈퇴 완료 전 장애는 사용자가 재요청해야 하며, 복구 작업이 임의로 탈퇴를 승인하지 않습니다.
같은 operationId의 해제는 멱등합니다. 확보보다 해제가 먼저 도착해도 canceled tombstone을 저장해 늦은 확보가 차단을 되살리지 못하게 합니다.
과거 작업의 늦은 해제는 새 operationId의 차단을 풀지 않습니다. 관련 이력의 안전한 보관/정리는 별도 운영 절차입니다.
Account의 회수 작업을 비활성화하기 전 비종료 작업을 확인해야 합니다. ABORTING과 오래된 CHECKING을 모니터링합니다.

## 내부 API와 인증

Order와 Product:

- GET /internal/v1/account-withdrawals/{accountId}, 시점 상태 조회
- PUT /internal/v1/account-withdrawals/{accountId}/fences/{operationId}, 영속 차단 후 상태 조회
- DELETE /internal/v1/account-withdrawals/{accountId}/fences/{operationId}, 멱등 차단 해제

Order만 GET /internal/v1/account-withdrawals/{accountId}/payments?page=0으로 참조 100건과 hasNext를 반환합니다.
Payment는 POST /internal/v1/account-withdrawals/payments로 references(orderId, paymentAttemptId, approved)를 받아 pendingPayments를 반환합니다.
Payment가 accountId를 갖고 있다고 가정하지 않습니다. 없는/다른 주문 결제, PENDING/UNKNOWN, 활성 작업 임대, 보상 중 APPROVED는 보류합니다.
참조는 외부 사용자 입력이 아니라 거래 차단한 Order 원본에서 가져옵니다.
Order의 비종료 거래가 0이고 신규 거래가 차단된 조건에서만 최종 결제 결과를 근거로 탈퇴합니다.
Payment는 기존 승인, CHECK, CANCEL Kafka 계약을 바꾸거나 주문 Saga를 소유하지 않습니다.

내부 API는 일반/구매 Gateway에 라우팅하지 않고 공개 OpenAPI에서도 제외합니다.
Account는 기존 AccessTokenService.issueService와 RSA 키로 client_id=account-service, principal_type=service,
scope=account.withdrawal, roles=[]인 5분 JWT를 발급하고 캐시합니다. 별도 개인키나 서비스 로그인 API를 만들지 않습니다.
Order/Product/Payment는 별도 내부 JWT 체인에서 RS256, issuer, audience, exp, token_use,
지정 ACCOUNT_WITHDRAWAL_SUBJECT와 client_id, principal_type, scope를 검증합니다.
기존 사용자 JWT 필터와 Order 재고 scope, Notification scope를 완화하지 않습니다.
Account의 내부 HTTP는 Eureka와 LoadBalancer로 서비스 복제본을 선택하며 같은 EC2의 localhost로 고정하지 않습니다.

## 로컬 설정과 배포

AccountService/scripts/Initialize-LocalWithdrawalConnection.ps1을 명시 실행하면 네 저장소 .env에 동일한 공개 서비스 주체 UUID를 준비하고 Account의 기능만 활성화합니다.
기존 서로 다른 주체는 덮어쓰지 않습니다. DB 비밀번호, 모델 키와 토스 키는 변경하거나 출력하지 않습니다.
네 서비스를 새 코드로 빌드/재시작해야 적용됩니다. Product와 Order의 새 차단 테이블, Account 준비 테이블은 개발 update로 생성합니다.
운영은 validate이므로 같은 엔티티 구조의 스키마를 배포 전에 준비해야 합니다. 이 작업은 운영 DB나 AWS를 변경하지 않습니다.
외부 토스 결제나 실제 사용자 탈퇴를 검증 목적으로 실행하지 않습니다.

이 계약은 거래 생성 차단과 계정 종료입니다. 모든 서비스의 기존 Access JWT 즉시 폐기, 개인정보 물리 삭제,
법적 보관 기간과 재가입 정책, Query 이력 삭제, 이미 접수된 알림 회수는 별도로 다룹니다.
