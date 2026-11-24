package com.msa.payment.domain.membership.service;

import java.util.UUID;

/** 구독과 세대 및 필드에 결합된 암호문만 생성하고 복호화합니다. */
public interface MembershipEncryptionService {
    String encrypt(String value, UUID subscription, long generation, String field);

    String decrypt(String envelope, UUID subscription, long generation, String field);
}
