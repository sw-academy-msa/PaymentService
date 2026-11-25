# 결제 분석 전송

PaymentCommand APPROVE의 선택 analytics 블록은 주문 스냅샷과 가명 ID를 받습니다. 필드가 없는 과거 명령의 JSON 해시는 유지합니다. 승인 결과와 PURCHASE 의도, 보상 취소와 REFUND 의도는 각각 같은 트랜잭션으로 저장합니다. 주문과 종류의 복합 키로 중복을 막고 refund는 purchase가 종료될 때까지 기다립니다.

PAYMENT_GA_MP_ENABLED는 기본 false입니다. 켜기 전에 Account의 payment-service 클라이언트에 ai.personalization.read scope와 PAYMENT_SERVICE_SUBJECT를 등록하고 PAYMENT_CLIENT_SECRET을 안전하게 주입해야 합니다. GA_MEASUREMENT_ID와 GA_MP_API_SECRET 이름만 문서와 Git에 남깁니다. 접두사는 /stockdrop/prod/payment-service/이며 API secret은 SecureString입니다. 값을 채팅, 명령 인자, 로그에 넣지 않습니다.

내부 통신은 기본 HTTPS입니다. 기존 VPC 평문 통신 배포에서는 운영자가 PAYMENT_INTERNAL_SCHEME=http 및 PAYMENT_INTERNAL_ALLOW_PLAIN_HTTP=true를 명시해야 하며 외부 Gateway로 우회하지 않습니다. MP는 고정 Google HTTPS 목적지만 허용합니다. 테스트의 루프백 HTTP 예외는 prod에서 허용하지 않습니다.

워커는 30초마다 최대 10건을 임대하고 DB 연결을 반납한 뒤 현재 AI 동의와 accountId 및 analyticsId 연결을 확인합니다. 동의 거절은 SKIPPED이며 확인 불가는 재시도합니다. 일시 실패는 1분, 5분, 30분의 세 번 재시도 후 FAILED입니다. stockdrop_payment_ga_failed_total과 테이블 상태를 확인합니다. 전송 뒤 7일이 지나면 payload를 삭제하며 결제 시도에 보관한 analytics도 7일 뒤 지웁니다.

purchase와 refund는 같은 transaction_id를 사용합니다. MP 2xx는 수신 확인이며 분석 처리를 보장하지 않습니다. HTTP 응답 유실 때 재전송될 수 있으므로 실제 GA 검증은 승인된 격리 환경의 debug MP 및 DebugView로 확인합니다. 로컬 테스트는 루프백 HTTP만 호출하며 Google과 토스에 보내지 않습니다. FE는 purchase를 보내지 않습니다.

참조: https://developers.google.com/analytics/devguides/collection/protocol/ga4/reference 및 https://developers.google.com/analytics/devguides/collection/ga4/reference/events

되돌릴 때 PAYMENT_GA_MP_ENABLED=false로 전송을 멈춥니다. V3 테이블과 영수증, 결제 원본 및 Outbox는 보존하고 코드 복구는 merge commit revert PR로 합니다. 재활성화 전 PENDING 상태와 동의를 다시 확인합니다.
