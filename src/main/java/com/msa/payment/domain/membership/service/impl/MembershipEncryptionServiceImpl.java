package com.msa.payment.domain.membership.service.impl;

import com.msa.payment.domain.membership.service.MembershipEncryptionService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 매번 새 nonce를 사용하며 다른 구독이나 필드로 암호문을 옮기면 복호화를 거절합니다. */
@Service
@ConditionalOnProperty(name = "membership.billing.enabled", havingValue = "true")
public class MembershipEncryptionServiceImpl implements MembershipEncryptionService {
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public MembershipEncryptionServiceImpl(@Qualifier("membershipDataKey") SecretKey key) {
        this.key = key;
    }

    public String encrypt(String value, UUID subscription, long generation, String field) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            byte[] encrypted =
                    cipher(Cipher.ENCRYPT_MODE, nonce, subscription, generation, field)
                            .doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v1:"
                    + Base64.getEncoder().encodeToString(nonce)
                    + ":"
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception failure) {
            throw new IllegalStateException("멤버십 결제 정보를 암호화할 수 없습니다.");
        }
    }

    public String decrypt(String envelope, UUID subscription, long generation, String field) {
        try {
            if (envelope == null || envelope.length() > 8192) throw new IllegalArgumentException();
            String[] parts = envelope.split(":", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            if (nonce.length != 12) throw new IllegalArgumentException();
            byte[] plaintext =
                    cipher(Cipher.DECRYPT_MODE, nonce, subscription, generation, field)
                            .doFinal(Base64.getDecoder().decode(parts[2]));
            try {
                return new String(plaintext, StandardCharsets.UTF_8);
            } finally {
                java.util.Arrays.fill(plaintext, (byte) 0);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("멤버십 결제 정보를 복호화할 수 없습니다.");
        }
    }

    private Cipher cipher(int mode, byte[] nonce, UUID subscription, long generation, String field)
            throws Exception {
        if (subscription == null
                || generation < 1
                || !java.util.Set.of("billingKey", "customerKey", "authKey").contains(field))
            throw new IllegalArgumentException();
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(
                (subscription + ":" + generation + ":" + field).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
