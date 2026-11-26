-- 광고 결제 원본과 취소 선도착 영수증을 기존 주문 및 멤버십과 분리합니다.
create table payment.advertising_payments (
    id uuid primary key,
    application_id uuid not null unique,
    submission_id uuid not null,
    product_id uuid not null,
    seller_id uuid not null,
    amount bigint not null check (amount >= 1000),
    currency varchar(3) not null,
    formula_version varchar(20) not null,
    quote_hash varchar(64) not null,
    toss_order_id varchar(50) not null unique,
    payment_key varchar(200) unique,
    status varchar(20) not null,
    provider_state varchar(20) not null,
    cancellation_requested boolean not null,
    cancellation_reason_code varchar(40),
    approved_at timestamp(6) with time zone,
    canceled_at timestamp(6) with time zone,
    expires_at timestamp(6) with time zone not null,
    result_version bigint not null,
    completion_key uuid,
    lease_token uuid,
    lease_until timestamp(6) with time zone,
    attempts integer not null,
    next_attempt_at timestamp(6) with time zone not null,
    last_failure_code varchar(64),
    confirmation_started boolean not null,
    approval_requested boolean not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);
create index idx_advertising_due on payment.advertising_payments(next_attempt_at, lease_until);
create index idx_advertising_seller on payment.advertising_payments(seller_id, status);
create table payment.advertising_command_receipts (
    event_id uuid primary key,
    application_id uuid not null,
    submission_id uuid not null,
    product_id uuid not null,
    seller_id uuid not null,
    payload_hash varchar(64) not null,
    aggregate_version bigint not null,
    event_type varchar(50) not null,
    processed_at timestamp(6) with time zone not null,
    unique (application_id, aggregate_version)
);
create index idx_advertising_command_application on payment.advertising_command_receipts(application_id, event_type);
