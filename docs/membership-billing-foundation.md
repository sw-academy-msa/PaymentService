# 멤버십 빌링 기반 모듈

이 모듈은 공급자 통신, 암호화, 월 청구일 계산을 제공합니다. 아직 구독 API/DB 원본/Outbox/스케줄러에 연결되지 않은 기반 단계입니다. 전체 멤버십 완료로 간주하지 않습니다.

`MEMBERSHIP_BILLING_ENABLED`는 기본 false입니다. 활성 시 `TOSS_BILLING_CLIENT_KEY`의 test_ck_, `TOSS_BILLING_SECRET_KEY`의 test_sk_ 쌍만 허용합니다. 기존 위젯용 `TOSS_CLIENT_KEY/SECRET_KEY`를 변경하지 않습니다. `MEMBERSHIP_BILLING_DATA_KEY_V1`은 별도 32바이트 AES 키의 base64이며 운영 키는 사용자가 로컬과 다르게 생성합니다. 예제/테스트는 합성 키만 사용합니다.

BillingClient는 발급, 승인, 조회, 삭제를 수행하며 직접 DB나 재시도 일정을 만들지 않습니다. 호출자는 전송 전에 주문번호/멱등 키/본문을 영속해야 합니다. 불명 결과에 새 주문번호를 만드는 것은 금지합니다. 공식 문서의 명시 카드 거절만 DECLINED로 분류하며 중복 주문번호, 인증 오류, 타임아웃, 409, 429, 5xx, 조회 404는 UNKNOWN입니다. 승인 응답은 orderId, BILLING, KRW, DONE, 금액, 잔액, paymentKey와 승인시각을 검증합니다.

공식 근거: [빌링 API](https://docs.tosspayments.com/reference), [키 종류](https://docs.tosspayments.com/reference/using-api/api-keys), [자동결제 오류](https://docs.tosspayments.com/reference/error-codes), [웹훅](https://docs.tosspayments.com/reference/using-api/webhook-events). 빌링 승인에는 최소60초가 필요해65초를 사용하며 향후 작업 임대는120초 이상이어야 합니다. 완료 웹훅을 기다리지 않고 응답/조회로 복구합니다.

암호화는 AES-256-GCM, 무작위12byte nonce, 구독ID/세대/필드 AAD로 결합하며 v1 envelope를 저장합니다. 현재 v1만 지원하므로 실제 키 교체 전에 이전 keyId 읽기를 유지하는 keyring과 재암호화 절차를 추가해야 합니다. 원문/공급자 URI/예외메시지/토큰은 로그에 남기지 않습니다.

다음 구현: 승인된 design.md의 테이블/구독 API, 금융 동의, 인증 세션, 청구 cycle/attempt,2대 DB잠금/임대, bounded retry/수동대사,해지/키삭제/탈퇴,Outbox 및 Account/Notification 연동. FE 카드 인증 화면은 후속 작업이며 카드 정보는 사용자만 입력합니다. 이 모듈 검증은 JDK HttpServer 목으로 수행하고 실제 카드/API 호출이나 운영 SSM 등록은 하지 않았습니다.
