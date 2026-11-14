-- 결제 서비스의 최초 테이블입니다. 엔티티 enum 값은 애플리케이션이 검증하므로 DB CHECK로 고정하지 않습니다.

create table payment.outbox_events (
    attempts integer not null,
    created_at timestamp(6) with time zone not null,
    next_attempt_at timestamp(6) with time zone not null,
    published_at timestamp(6) with time zone,
    id uuid not null,
    partition_key varchar(255) not null,
    payload text not null,
    topic varchar(255) not null,
    primary key (id)
);

create table payment.payment_attempts (
    approval_requested boolean not null,
    attempts integer not null,
    cancellation_requested boolean not null,
    confirmation_started boolean not null,
    amount bigint not null,
    approved_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    expires_at timestamp(6) with time zone not null,
    lease_until timestamp(6) with time zone,
    next_attempt_at timestamp(6) with time zone not null,
    result_version bigint not null,
    id uuid not null,
    lease_token uuid,
    order_id uuid not null unique,
    last_failure_code varchar(64),
    payment_key varchar(200) unique,
    status varchar(255) not null,
    primary key (id)
);

create table payment.payment_command_receipts (
    command_version bigint not null,
    processed_at timestamp(6) with time zone not null,
    attempt_id uuid not null,
    id uuid not null,
    payload_hash varchar(64) not null,
    primary key (id),
    unique (attempt_id, command_version)
);

create index idx_outbox_pending
   on payment.outbox_events (published_at, next_attempt_at, created_at);

create index idx_payment_work
   on payment.payment_attempts (next_attempt_at, lease_until);

create index idx_payment_due
   on payment.payment_attempts (status, cancellation_requested, next_attempt_at);
