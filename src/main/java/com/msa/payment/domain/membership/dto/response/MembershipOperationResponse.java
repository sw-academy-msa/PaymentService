package com.msa.payment.domain.membership.dto.response;

import java.util.UUID;

/** 금융 처리 접수와 회원 자격 반영 완료를 구분하는 응답입니다. */
public record MembershipOperationResponse(
        UUID subscriptionId, String operationStatus, boolean refreshRequired) {}
