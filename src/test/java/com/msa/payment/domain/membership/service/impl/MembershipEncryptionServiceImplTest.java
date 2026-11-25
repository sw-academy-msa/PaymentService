package com.msa.payment.domain.membership.service.impl;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

/** 합성 키만 사용해 nonce 다양성과 구독/세대/필드 결합 및 암호문 변조 거절을 검증합니다. */
class MembershipEncryptionServiceImplTest {
    private final MembershipEncryptionServiceImpl service =
            new MembershipEncryptionServiceImpl(new SecretKeySpec(new byte[32], "AES"));

    @Test
    void authenticatesContextAndNeverIncludesPlaintextInEnvelope() {
        var id = UUID.randomUUID();
        var encrypted = service.encrypt("synthetic-billing-key", id, 1, "billingKey");
        assertThat(encrypted).doesNotContain("synthetic-billing-key");
        assertThat(service.encrypt("synthetic-billing-key", id, 1, "billingKey"))
                .isNotEqualTo(encrypted);
        assertThat(service.decrypt(encrypted, id, 1, "billingKey"))
                .isEqualTo("synthetic-billing-key");
        assertThatThrownBy(() -> service.decrypt(encrypted, UUID.randomUUID(), 1, "billingKey"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.decrypt(encrypted, id, 2, "billingKey"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.decrypt(encrypted, id, 1, "authKey"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.decrypt("v2:bad:bad", id, 1, "billingKey"))
                .isInstanceOf(IllegalStateException.class);
        var parts = encrypted.split(":");
        byte[] bytes = java.util.Base64.getDecoder().decode(parts[2]);
        bytes[0] ^= 1;
        String tampered =
                parts[0]
                        + ":"
                        + parts[1]
                        + ":"
                        + java.util.Base64.getEncoder().encodeToString(bytes);
        assertThatThrownBy(() -> service.decrypt(tampered, id, 1, "billingKey"))
                .isInstanceOf(IllegalStateException.class);
    }
}
