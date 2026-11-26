# 멤버십 금융 원본

월 구독의 가격과 약관 동의, 카드 인증 세션, 청구 기간, 고정 주문번호와 멱등 키를 Payment DB에 보관합니다. Account의 자격 projection과 JWT는 이 원본의 결과 이벤트를 반영합니다. 실제 토스 호출, 운영 배포, SSM 등록은 수행하지 않았습니다.

## 플래그와 SDK 연결

모든 플래그는 기본 false입니다. `MEMBERSHIP_BILLING_ENABLED`는 기존 기반 클라이언트와 암호화를 구성하며 `MEMBERSHIP_API_ENABLED`는 신규 카드 인증 접수, `MEMBERSHIP_RENEWAL_ENABLED`는 새 정기 청구와 일별 업무 재시도, `MEMBERSHIP_RECOVERY_ENABLED`는 이미 접수한 발급과 결제 및 키 삭제 복구, `MEMBERSHIP_CONSUMER_ENABLED`는 해지 명령 수신을 각각 제어합니다. 가입 중지와 불명 금융 복구 중지는 별개입니다. 공급자 HTTP는 개별 테스트 키만 허용합니다.

본인 BUYER 사용자 토큰만 `/api/v1/payments/memberships/**`를 호출할 수 있습니다. SELLER를 겸하는 BUYER는 가능하고 ADMIN과 서비스 토큰은 거절합니다. 모든 응답은 no-store입니다. POST의 `Idempotency-Key`는 UUID이며 세션 재사용과 다른 본문 충돌을 검사합니다.

1. configuration을 조회하고 현재 planVersion, termsVersion과 정기결제 동의로 auth-sessions를 생성합니다.
2. 본인 세션 응답의 customerKey와 공개 clientKey로 SDK v2 `payment({customerKey}).requestBillingAuth`를 실행합니다. 카드 입력은 사용자만 합니다.
3. 고정 success URL의 authKey와 customerKey를 본인 세션 completion으로 보냅니다. callback query와 요청 원문은 분석 SDK와 access log에 남기지 않습니다.
4. completion의 202는 접수만 보장합니다. `/me`의 금융 결과와 Account의 sourceVersion 반영을 확인한 뒤 기존 refresh API로 토큰을 즉시 재발급합니다. FE 화면은 후속 작업입니다.

운영 redirect는 프론트 HashRouter 경로인 `https://stockdrop-app.vercel.app/#/membership/billing/success`와 `#/membership/billing/fail`입니다. local 프로필만 `http://localhost:5179/#/membership/billing/success`와 `#/membership/billing/fail`을 허용합니다. 토스가 authKey와 customerKey 쿼리를 해시 앞(`/?authKey=...#/membership/...`)이나 해시 안(`#/membership/...?authKey=...`)에 붙일 수 있으므로 프론트는 기존 주문 결제 귀환처럼 두 위치를 모두 읽고 즉시 주소에서 지웁니다. API 요청으로 임의 redirect를 받지 않습니다.

## 금융 실행과 복구

구독을 먼저 잠그고 기간과 시도를 잠급니다. 계정별 구독, 세대별 기간 순번, 기간별 시도 번호, orderId, 멱등 키와 paymentKey에 유일 제약을 적용했습니다. HTTP 전에 실행 시작과 120초 임대 및 고정 요청을 커밋합니다. HTTP 전체 본문 수신은 65초이며 64KiB 수신 상한을 적용합니다. HTTP 동안 DB 트랜잭션을 유지하지 않습니다. 인스턴스별 단일 worker이며 poll은 30초, 후보는 10개 이하입니다.

임대 만료와 오래된 토큰의 결과는 반영하지 않습니다. 불명 결과는 같은 주문 조회와 같은 멱등 키 전송을 번갈아 처리하며 HTTP 한 번마다 새 임대를 받습니다. 1초, 5초, 30초, 최대 60초 간격이며 10회 또는 최초 전송 후 15일에 수동 대사로 격리합니다. 확정 카드 거절만 다음날 새 업무 시도를 허용합니다. 정기 청구와 이후 3일 재시도를 합해 최대 4회입니다. 유예 상한은 원 청구 시각 후 3일과 마지막 결과 처리용 120초입니다.

`MEMBERSHIP_PROVIDER_KEY_VERSION`은 기본 v1입니다. 미확정 시도와 인증 세션에 저장한 버전이 현재 설정과 다르면 다른 키로 승인하지 않고 수동 대사로 격리합니다. 키 교체 때 버전을 반드시 함께 변경하고 이전 키의 미확정 금융을 먼저 대사합니다. 암호화 키는 아직 v1만 지원하므로 실제 교체 전에는 keyring과 재암호화가 필요합니다.

가입일은 첫 승인 한국 시각으로 고정합니다. 없는 날짜는 말일로 줄이고 다음 달에는 원래 날짜를 복원합니다. 유예 중 승인도 원 청구 기간 종료일을 사용합니다. 요금 설정 변경은 기존 구독의 가격을 변경하지 않습니다. 새 세대에도 aggregateVersion은 감소하지 않습니다.

## 해지와 탈퇴

해지 명령은 autoRenew=false를 금융 원본에 먼저 커밋합니다. 시작하지 않은 미래 청구는 취소하며, 이미 시작한 청구는 같은 요청으로 대사합니다. 그 청구가 승인되면 납부기간을 제공하고 추가 갱신은 중지합니다. 유예 중 해지는 즉시 종료합니다. 키 삭제는 미확정 청구와 발급이 해결된 뒤 수행하며 공급자 삭제 확인 전에는 암호문을 지우지 않습니다. 실패는 유한 재시도 후 수동 대사로 남깁니다.

해지 예약(CANCEL_SCHEDULED)은 혜택 종료 전에 철회할 수 있도록 빌링키를 혜택 종료(EXPIRED) 시점에 삭제합니다. 즉시 종료되는 해지(유예, 첫 결제 전)는 바로 삭제합니다. 해지 취소 명령 `MEMBERSHIP_CANCEL_WITHDRAW_REQUESTED`는 같은 세대의 해지 예약, 혜택 종료 전, 탈퇴 차단 없음, 미확정 청구 없음, 빌링키 ACTIVE일 때만 autoRenew=true, state=ACTIVE, nextChargeAt=periodEnd로 되살리고 `MEMBERSHIP_CANCEL_WITHDRAWN`을 발행합니다. 조건이 맞지 않으면 상태를 바꾸지 않고 `MEMBERSHIP_CANCEL_WITHDRAW_REJECTED` 스냅샷을 발행합니다. 계정별 commandVersion이 더 큰 명령을 이미 처리했다면 늦게 온 명령은 수신 기록만 남깁니다.

결제 실패 유예(GRACE) 중에는 같은 세대에서 새 카드 인증 세션을 만들 수 있습니다. 자동갱신 중, 유예 상한 전, 미확정 청구와 진행 중 세션 없음, 기존 키 ACTIVE, 이전 교체 키 삭제 완료일 때만 허용하고 아니면 409입니다. 세션이 열려 있는 동안 정기 재시도는 이전 키로 실행하지 않고 60초 뒤 다시 확인합니다. 발급되면 이전 키는 retired 암호문으로 옮겨 공급자 삭제를 따로 진행하고, 같은 청구 기간의 다음 시도를 새 키로 즉시 만듭니다. 이 시도가 거절되면 기존 재시도 일정과 유예 상한을 그대로 따릅니다.

기존 Account 전용 서비스 토큰의 `/internal/v1/account-withdrawals/{account}/memberships` 경로를 사용합니다. GET은 시작한 미확정 금융 수를 조회합니다. PUT `/fences/{operation}`은 새 가입과 새 실행을 차단합니다. POST `/fences/{operation}/completion`은 시작한 금융이 해결된 뒤 무환불 즉시 종료를 확정합니다. DELETE는 실패한 계정 탈퇴의 차단을 해제하고 지연된 같은 확보 요청을 거절하는 tombstone을 보존합니다. 이미 확정한 멤버십 해지는 되돌리지 않으며 이후 새 가입은 새 동의와 카드 등록이 필요합니다. Account 로컬 탈퇴 커밋이 실패해도 자동갱신을 임의로 복구하지 않습니다.

## 이벤트와 격리

결과는 `stockdrop.membership.events.private.v1`의 완전 자격 스냅샷입니다. 기존 STARTED, RENEWED, PAYMENT_FAILED, CANCELED, EXPIRED에 `MEMBERSHIP_RENEWAL_PENDING`과 `MEMBERSHIP_WITHDRAWN`을 포함합니다. PENDING은 정기 청구가 시작된 유예 상한을 전달하며 결제 성공이나 쿠폰 지급을 뜻하지 않습니다. Account는 원본 버전으로 projection을 갱신하고 알림 대상 타입만 알림으로 변환해야 합니다.

해지 명령은 전용 factory에서 일시 오류 3회 재시도 후 같은 partition의 `.DLT`로 보냅니다. 영구 계약 오류는 즉시 DLT입니다. DLT 송신이 실패하면 ack 없이 해당 컨테이너를 정지합니다. 운영자가 원인을 해결하고 같은 eventId로 재처리해야 합니다. 기존 주문 결제 토픽의 무한 재시도 정책은 변경하지 않았습니다.

멤버십 Outbox만 발행 20회 실패 후 failed_at으로 격리하고 안전한 이벤트 ID로 오류를 기록합니다. payload와 eventId를 보존하고 발행 완료로 처리하지 않습니다. 격리와 수동 대사 행의 존재, 오래된 Outbox와 projection 지연에 경보를 연결해야 합니다.

수동 대사 목록은 읽기 전용으로 다음 필드만 조회합니다. 암호문, 인증 키, 공급자 키와 원문 응답을 운영 출력에 포함하지 않습니다.

```sql
select a.id, a.cycle_id, a.order_id, a.status, a.first_requested_at,
       a.transport_attempts, a.provider_key_version, a.last_failure_code
from payment.membership_billing_attempts a
where a.status = 'MANUAL_REVIEW'
order by a.created_at;

select id, subscription_id, state, expires_at
from payment.membership_auth_sessions where state = 'MANUAL_REVIEW';

select subscription_id, delete_state, attempts, last_failure_code
from payment.membership_billing_credentials where delete_state = 'MANUAL_REVIEW';

select id, created_at, attempts, failed_at, last_failure_code
from payment.outbox_events where failed_at is not null;
```

운영 변경은 별도 승인 대상입니다. 새 datasource나 라이브러리는 없으며 기존 DB 풀을 사용합니다. 마이그레이션은 추가만 하고 down migration과 금융 기록 삭제는 하지 않습니다. 되돌릴 때 가입과 신규 할인부터 차단하고 금융 UNKNOWN 및 키 삭제 작업은 호환 코드로 drain합니다.
