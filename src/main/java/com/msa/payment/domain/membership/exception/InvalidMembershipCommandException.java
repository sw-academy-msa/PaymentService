package com.msa.payment.domain.membership.exception;

/** 영구 계약 오류만 재시도 없이 전용 DLT로 분류합니다. */
public class InvalidMembershipCommandException extends MembershipException {
    public InvalidMembershipCommandException() {
        super(MembershipErrorCode.INVALID_COMMAND);
    }
}
