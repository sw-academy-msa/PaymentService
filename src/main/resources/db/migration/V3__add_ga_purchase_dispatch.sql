-- 분석 전송은 결제 원본과 함께 저장하며 HTTP는 임대 커밋 뒤 실행합니다.
alter table payment.payment_attempts add column analytics_payload text;
create table payment.ga_purchase_dispatch (
    order_id uuid not null, kind varchar(255) not null, payload text,
    status varchar(255), attempts integer not null, next_attempt_at timestamptz,
    lease_token uuid, lease_until timestamptz, created_at timestamptz,
    finished_at timestamptz, amount bigint not null,
    primary key (order_id, kind)
);
create index idx_ga_dispatch_pending on payment.ga_purchase_dispatch(next_attempt_at)
    where status='PENDING';
