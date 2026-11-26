-- 유예 중 카드 교체로 물러난 이전 빌링키를 공급자 삭제 확인까지 암호문으로 보관합니다.
alter table payment.membership_billing_credentials add column retired_billing_key_cipher text;
alter table payment.membership_billing_credentials
    add column retired_next_at timestamp(6) with time zone;
alter table payment.membership_billing_credentials
    add column retired_attempts integer not null default 0;
create index idx_membership_retired_delete
    on payment.membership_billing_credentials (retired_next_at)
    where retired_billing_key_cipher is not null;
