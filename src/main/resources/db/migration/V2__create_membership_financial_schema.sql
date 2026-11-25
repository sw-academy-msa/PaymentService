-- 구독 금융 원본만 추가하며 과거 주문과 결제 데이터는 수정하지 않습니다.
alter table payment.outbox_events add column failed_at timestamp(6) with time zone;
alter table payment.outbox_events add column last_failure_code varchar(64);
create table payment.membership_withdrawal_fences (
    id uuid primary key,
    account_id uuid not null,
    canceled boolean not null,
    finalized boolean not null
);
create index idx_membership_withdrawal_account on payment.membership_withdrawal_fences(account_id,canceled);

create table payment.membership_subscriptions (
    id uuid not null primary key,
    account_id uuid not null unique,
    generation bigint not null,
    state varchar(255) not null,
    auto_renew boolean not null,
    plan_version varchar(40) not null,
    price_amount bigint not null,
    currency varchar(3) not null,
    anchor_day integer not null,
    anchor_local_time time(6),
    zone varchar(40) not null,
    period_start timestamp(6) with time zone,
    period_end timestamp(6) with time zone,
    entitlement_until timestamp(6) with time zone,
    grace_until timestamp(6) with time zone,
    next_charge_at timestamp(6) with time zone,
    canceled_at timestamp(6) with time zone,
    aggregate_version bigint not null,
    row_version bigint not null,
    terms_version varchar(40) not null,
    agreed_at timestamp(6) with time zone not null,
    agreed_account_id uuid not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);
create index idx_membership_due on payment.membership_subscriptions (state,next_charge_at);
create index idx_membership_expiry on payment.membership_subscriptions (state,entitlement_until);

create table payment.membership_billing_credentials (
    subscription_id uuid not null primary key,
    customer_key_cipher text,
    billing_key_cipher text,
    encryption_key_id varchar(40) not null,
    delete_state varchar(24) not null,
    delete_next_at timestamp(6) with time zone,
    lease_token uuid,
    lease_until timestamp(6) with time zone,
    attempts integer not null,
    last_failure_code varchar(64),
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);
create index idx_membership_delete on payment.membership_billing_credentials (delete_state,delete_next_at);

create table payment.membership_auth_sessions (
    provider_key_version varchar(40) not null,
    id uuid not null primary key,
    account_id uuid not null,
    subscription_id uuid not null,
    generation bigint not null,
    customer_key_cipher text not null,
    auth_key_cipher text,
    encryption_key_id varchar(40) not null,
    state varchar(255) not null,
    expires_at timestamp(6) with time zone not null,
    idempotency_key varchar(100) not null unique,
    payload_hash varchar(64) not null,
    completion_hash varchar(64),
    completion_key uuid,
    lease_token uuid,
    lease_until timestamp(6) with time zone,
    attempts integer not null,
    next_attempt_at timestamp(6) with time zone not null,
    terms_version varchar(40) not null,
    agreed_at timestamp(6) with time zone not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);
create index idx_membership_auth_due on payment.membership_auth_sessions (state,next_attempt_at,lease_until);
create index idx_membership_auth_expiry on payment.membership_auth_sessions (expires_at);

create table payment.membership_billing_cycles (
    id uuid not null primary key,
    subscription_id uuid not null,
    generation bigint not null,
    cycle_number bigint not null,
    scheduled_at timestamp(6) with time zone not null,
    period_start timestamp(6) with time zone,
    period_end timestamp(6) with time zone,
    amount bigint not null,
    currency varchar(3) not null,
    status varchar(255) not null,
    retry_number integer not null,
    next_attempt_at timestamp(6) with time zone,
    grace_until timestamp(6) with time zone,
    succeeded_attempt_id uuid,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    unique (subscription_id, generation, cycle_number)
);
create index idx_membership_cycle_due on payment.membership_billing_cycles (status,next_attempt_at);

create table payment.membership_billing_attempts (
    id uuid not null primary key,
    cycle_id uuid not null,
    attempt_number integer not null,
    order_id varchar(64) not null unique,
    idempotency_key varchar(100) not null unique,
    status varchar(255) not null,
    amount bigint not null,
    currency varchar(3) not null,
    request_hash varchar(64) not null,
    payment_key varchar(200) unique,
    provider_key_version varchar(40) not null,
    first_requested_at timestamp(6) with time zone,
    next_attempt_at timestamp(6) with time zone not null,
    lease_token uuid,
    lease_until timestamp(6) with time zone,
    transport_attempts integer not null,
    last_failure_code varchar(64),
    approved_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    unique (cycle_id, attempt_number)
);
create index idx_membership_attempt_due on payment.membership_billing_attempts (status,next_attempt_at,lease_until);

create table payment.membership_command_receipts (
    event_id uuid not null primary key,
    account_id uuid not null,
    command_version bigint not null,
    payload_hash varchar(64) not null,
    processed_at timestamp(6) with time zone not null,
    unique (account_id, command_version)
);

alter table payment.membership_billing_credentials add foreign key (subscription_id) references payment.membership_subscriptions(id);
alter table payment.membership_auth_sessions add foreign key (subscription_id) references payment.membership_subscriptions(id);
alter table payment.membership_billing_cycles add foreign key (subscription_id) references payment.membership_subscriptions(id);
alter table payment.membership_billing_attempts add foreign key (cycle_id) references payment.membership_billing_cycles(id);
