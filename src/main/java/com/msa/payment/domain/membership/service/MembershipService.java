package com.msa.payment.domain.membership.service;

import com.msa.payment.domain.membership.dto.request.*;
import com.msa.payment.domain.membership.dto.response.*;

import java.util.UUID;

/** 회원제 HTTP 요청의 본인 소유권과 동의 및 멱등 접수를 담당합니다. */
public interface MembershipService {
    MembershipConfigurationResponse configuration();

    MembershipSessionResponse create(UUID account, UUID key, MembershipAuthRequest request);

    MembershipOperationResponse complete(
            UUID account, UUID session, UUID key, MembershipCompletionRequest request);

    MembershipResponse get(UUID account);
}
