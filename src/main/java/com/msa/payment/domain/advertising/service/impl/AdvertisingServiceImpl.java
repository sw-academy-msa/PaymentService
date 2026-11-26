package com.msa.payment.domain.advertising.service.impl;

import com.msa.payment.domain.advertising.dto.request.AdvertisingCompletionRequest;
import com.msa.payment.domain.advertising.dto.response.*;
import com.msa.payment.domain.advertising.entity.AdvertisingPayment;
import com.msa.payment.domain.advertising.exception.*;
import com.msa.payment.domain.advertising.repository.AdvertisingPaymentRepository;
import com.msa.payment.domain.advertising.service.AdvertisingService;
import com.msa.payment.global.config.advertising.AdvertisingProperties;
import com.msa.payment.global.config.toss.TossProperties;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.util.*;

/** 서버 원본 금액을 고정하고 접수 플래그가 꺼져도 이미 시작한 승인은 복구합니다. */
@Service
@Transactional(timeout = 5)
public class AdvertisingServiceImpl implements AdvertisingService {
    private final AdvertisingPaymentRepository payments;
    private final AdvertisingProperties policy;
    private final TossProperties toss;
    private final Clock clock;

    public AdvertisingServiceImpl(
            AdvertisingPaymentRepository payments,
            AdvertisingProperties policy,
            TossProperties toss,
            Clock clock) {
        this.payments = payments;
        this.policy = policy;
        this.toss = toss;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdvertisingCheckoutResponse checkout(UUID seller, UUID id) {
        var p =
                owned(
                        seller,
                        payments.findById(id)
                                .orElseThrow(() -> error(AdvertisingErrorCode.NOT_FOUND)));
        if (!policy.enabled()
                || !policy.consumerEnabled()
                || !policy.processingEnabled()
                || !toss.enabled()
                || toss.clientKey() == null
                || !toss.clientKey().startsWith("test_"))
            throw error(AdvertisingErrorCode.UNAVAILABLE);
        if (!"PENDING".equals(p.status)
                || p.cancellationRequested
                || p.confirmationStarted
                || !clock.instant().isBefore(p.expiresAt))
            throw error(AdvertisingErrorCode.CONFLICT);
        return new AdvertisingCheckoutResponse(
                p.id,
                p.applicationId,
                p.tossOrderId,
                p.amount,
                p.currency,
                toss.clientKey(),
                p.expiresAt,
                returnUrl(policy.successUrl(), "/seller/advertising/toss/success"),
                returnUrl(policy.failUrl(), "/seller/advertising/toss/fail"),
                p.status);
    }

    @Transactional(readOnly = true)
    public AdvertisingPaymentResponse get(UUID seller, UUID id) {
        return response(
                owned(
                        seller,
                        payments.findById(id)
                                .orElseThrow(() -> error(AdvertisingErrorCode.NOT_FOUND))));
    }

    public AdvertisingPaymentResponse complete(
            UUID seller, UUID id, UUID key, AdvertisingCompletionRequest request) {
        // 같은 공급자 키를 다른 원본에 동시에 묶는 요청을 DB 제약 오류 전에 직렬화합니다.
        payments.lockIdentity("advertising-key:" + request.paymentKey());
        var p =
                owned(
                        seller,
                        payments.lock(id).orElseThrow(() -> error(AdvertisingErrorCode.NOT_FOUND)));
        if (!p.tossOrderId.equals(request.tossOrderId()) || key == null)
            throw error(AdvertisingErrorCode.CONFLICT);
        if (p.completionKey != null) {
            if (!p.completionKey.equals(key) || !Objects.equals(p.paymentKey, request.paymentKey()))
                throw error(AdvertisingErrorCode.CONFLICT);
            return response(p);
        }
        // 준비된 원본의 completion은 신규 접수 OFF 중에도 허용하며 취소 의도와 기한은 별도로 검증합니다.
        if (!policy.processingEnabled() || !toss.enabled())
            throw error(AdvertisingErrorCode.UNAVAILABLE);
        if (!"PENDING".equals(p.status)
                || p.cancellationRequested
                || !clock.instant().isBefore(p.expiresAt))
            throw error(AdvertisingErrorCode.CONFLICT);
        if (payments.existsByPaymentKeyAndIdNot(request.paymentKey(), id))
            throw error(AdvertisingErrorCode.CONFLICT);
        p.completionKey = key;
        p.paymentKey = request.paymentKey();
        p.approvalRequested = true;
        p.nextAttemptAt = clock.instant();
        p.updatedAt = clock.instant();
        return response(p);
    }

    private AdvertisingPayment owned(UUID seller, AdvertisingPayment p) {
        if (!p.sellerId.equals(seller)) throw error(AdvertisingErrorCode.OWNERSHIP);
        return p;
    }

    private AdvertisingPaymentResponse response(AdvertisingPayment p) {
        return new AdvertisingPaymentResponse(
                p.id,
                p.applicationId,
                p.submissionId,
                p.productId,
                p.amount,
                p.currency,
                p.status,
                p.cancellationRequested,
                p.approvedAt,
                p.canceledAt,
                p.expiresAt,
                p.resultVersion);
    }

    private String returnUrl(URI uri, String route) {
        if (uri == null
                || !"https".equals(uri.getScheme())
                || !"stockdrop-app.vercel.app".equals(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || uri.getQuery() != null
                || !route.equals(uri.getFragment())
                || !"/".equals(uri.getPath())) throw error(AdvertisingErrorCode.UNAVAILABLE);
        return uri.toString();
    }

    private AdvertisingException error(AdvertisingErrorCode code) {
        return new AdvertisingException(code);
    }
}
