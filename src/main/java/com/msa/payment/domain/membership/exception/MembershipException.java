package com.msa.payment.domain.membership.exception;

import com.msa.payment.global.error.BusinessException;

/** 멤버십 오류를 기존 전역 처리기와 공통 응답으로 전달합니다. */
public class MembershipException extends BusinessException {
    public MembershipException(MembershipErrorCode code) {
        super(code);
    }
}
