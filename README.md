# PaymentService

STOCKDROP 테스트 결제 서비스입니다. Java 21 / Spring Boot 4.1.1 / Spring Cloud 2025.1.4 / Gradle 9.8.2를 사용합니다.

1. 기존 일반 PostgreSQL `stockdrop-account-postgres`(5433)를 실행합니다. Command PostgreSQL(5434)은 사용하지 않습니다.
2. PowerShell에서 `scripts/Initialize-LocalPaymentDatabase.ps1`과 `scripts/Test-LocalPaymentDatabase.ps1`을 실행합니다. 전용 `payment` DB·계정·스키마를 준비하며 비밀번호는 Git 제외 `.env`에 생성합니다. 업무 테이블 SQL은 없습니다.
3. Java 21로 `./gradlew.bat bootRun`을 실행합니다. 기본 포트 8084, 서비스 이름은 `payment-service`입니다.
4. 상태 확인은 `http://localhost:8084/actuator/health`, 로컬 Swagger는 `/swagger-ui.html`입니다.

일반 Gateway(8000)를 재시작하면 `/api/v1/payments`와 하위 GET/POST 경로를 `lb://payment-service`로 전달합니다. PurchaseGateway는 변경하지 않습니다. Gateway와 Payment 모두 Account RS256/JWKS JWT를 검증합니다. 개인키와 Refresh 인증은 Account만 소유합니다.

BUYER 전용 `GET /api/v1/payments/client-configuration`은 브라우저 SDK 초기화용 공개 `clientKey`만 반환합니다. 시크릿 키는 응답·로그에 포함하지 않습니다. 결제 승인·상태 확인·Saga 보상 취소는 공개 HTTP가 아니라 Order 전용 Kafka 명령으로 처리합니다. 구매자 취소·반품·부분환불 API는 이번 범위가 아닙니다.

Payment는 일반 풀 A/B와 RDS 쪽에 배치합니다. 로컬은 Account와 PostgreSQL 인스턴스만 공유하고 논리 DB·계정·스키마를 분리합니다. 다른 서비스 테이블 조회·SQL JOIN·공유 트랜잭션을 사용하지 않습니다. 개발 `ddl-auto=update`, 운영은 스키마 준비 후 `validate`입니다.

Order는 Saga를 소유합니다. Payment는 비공개 명령을 DB에 먼저 저장하고 소비 offset을 진행한 뒤, 별도 영속 작업으로 토스 승인·paymentKey 조회·전액 보상 취소를 실행합니다. 결과와 Outbox는 같은 로컬 트랜잭션이고 Kafka 성공 응답 후 발행 완료를 기록합니다. 수수료 계산·실제 정산·송금은 미구현입니다.

실제 값은 `.env`에만 넣습니다. `TOSS_CLIENT_KEY`와 `TOSS_SECRET_KEY`는 같은 종류의 테스트 키 세트여야 합니다. 주문서형/결제창 키(test_gck/test_gsk)와 API 개별 연동 키(test_ck/test_sk)를 지원하며 라이브 키는 차단합니다. 두 종류를 섞지 않습니다. 키가 없는 기본 실행에서는 아래 플래그를 false로 둡니다.

```dotenv
PAYMENT_TOSS_ENABLED=true
PAYMENT_MESSAGING_ENABLED=true
PAYMENT_PROCESSING_ENABLED=true
PAYMENT_OUTBOX_PUBLISHER_ENABLED=true
KAFKA_BOOTSTRAP_SERVERS=localhost:19092
```

먼저 공용 Kafka를 실행하고 `../OrderCommandService/scripts/Initialize-LocalOrderKafkaTopics.ps1`로 명령·결과·DLT 토픽을 준비합니다. Payment 복제본은 같은 `payment-command-v1` 소비 그룹을 사용하고 Order 결과 소비 그룹과 분리합니다. Kafka 자체는 이 서비스에 새로 띄우지 않습니다. Compose 앱은 기존 `stockdrop-query-kafka_default` 네트워크와 INTERNAL listener `kafka:9092`를 사용합니다.

- [Order·Kafka·토스와 프론트 연결 계약](contracts/payment-flow.md)
- [공개 HTTP API 스냅샷](openapi/payment.openapi.json): 실제 컨트롤러에서 생성하며 Kafka 명령은 별도 계약을 따릅니다.
- OpenAPI 기본 서버는 일반 Gateway인 http://localhost:8000이며 운영 주소는 payment.openapi.gateway-base-url 설정으로 주입합니다. http://localhost:8084는 별도 개발 서버입니다.
- 원본 테이블은 엔티티로 관리하며 별도 운영용 `ops/sql`은 없습니다. DB·계정·스키마를 만드는 로컬 초기화는 업무 테이블 생성과 별개입니다.
- 토스 타임아웃·404·불명 응답은 결제 실패로 확정하지 않습니다. 기한이 지난 뒤에는 새 승인을 하지 않으며 기존 승인 여부를 조회합니다.
- 소비자 중복·순서 역전, 서버 재시작, 승인/취소 응답 유실은 영속 상태·멱등 키·재조회로 복구합니다. 단일 Kafka 디스크 유실까지 무손실을 보장하는 구성은 아닙니다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot'
./gradlew.bat test bootJar
```

테스트는 격리 PostgreSQL/Kafka, 테스트 메모리 RSA 키, 실제 HTTP로 호출하는 토스 대역을 사용합니다. 대역 테스트 통과가 실제 브라우저 인증·토스 승인 완료를 뜻하지는 않습니다. 로컬 `.env`·개인정보·실제 토큰은 커밋하지 않습니다. 상세 문서는 `docs`에서 로컬 관리하며 README·계약·설정·테스트는 추적합니다. Compose 바인딩은 로컬 전용이며 운영 ALB 바인딩과 주소를 그대로 대신하지 않습니다. 실제 ARM64 이미지 빌드·AWS 배포는 별도 검증입니다.

플랫폼 호환 기준: https://spring.io/projects/spring-cloud/


## 객체 생성 규칙

공개 응답·결제 작업·공급자 결과 DTO는 record를 유지하고 Lombok 빌더로 생성합니다. 엔티티는 기존 생성자에만 빌더를 적용하여 최초 PENDING 상태·서버 기준 시간·재시도 일정을 유지합니다. 잠금·결제 상태 전이·조회 재시도·Outbox 트랜잭션은 기존 서비스에서 수행하며 토스 HTTP와 Kafka 계약도 유지합니다.
Lombok은 Spring Boot가 관리하는 버전을 compileOnly·annotationProcessor로 사용하며 실행 의존성에는 포함하지 않습니다. 빌더 문자열에 개인정보·결제키·토큰·이벤트 본문을 출력하지 않습니다.

## 도메인 예외

결제 업무 오류는 payment/exception의 PaymentException·PaymentErrorCode로 표현하고 공통 BusinessException·GlobalExceptionHandler를 재사용합니다. 비활성 결제 설정 조회의 COMMON_SERVICE_UNAVAILABLE 코드·HTTP 503·메시지는 유지합니다.
기존 InvalidPaymentCommandException은 도메인 예외를 상속하되 타입·패키지·메시지를 유지하여 Kafka 영구 오류/DLT 분류를 보존합니다. 기반 PaymentException 전체를 재시도 제외 대상으로 등록하지 않습니다. DB·DLT 장애는 기존 경로로 재시도하고, 토스 응답 불명은 UNKNOWN으로 재확인합니다. 기술적 실패를 결제 확정 실패로 바꾸지 않습니다.

## 공통 성공 응답

공개 결제 설정 조회는 Account와 같은 ApiResponse(success/code/message/data)를 반환합니다. 클라이언트 키는 data.clientKey에서 읽습니다. HTTP 200·COMMON_SUCCESS·no-store와 기존 오류 응답은 유지합니다. Kafka 명령·결과, 토스 HTTP 계약과 DTO 변환은 유지하며 Mapper는 추가하지 않습니다.

## 회원탈퇴 연동

[원본 확인, 신규 거래 차단과 장애 회수 계약](contracts/account-withdrawal-v1.md)을 확인합니다.
완료된 판매 이력은 탈퇴 차단 사유가 아닙니다. 진행 거래와 미확정 결제/보상만 확인하며 수수료는 후속 구현 범위입니다.
