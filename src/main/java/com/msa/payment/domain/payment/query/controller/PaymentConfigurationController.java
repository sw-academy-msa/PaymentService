package com.msa.payment.domain.payment.query.controller;

import com.msa.payment.domain.payment.exception.PaymentErrorCode;
import com.msa.payment.domain.payment.exception.PaymentException;
import com.msa.payment.domain.payment.query.dto.response.ClientConfigurationResponse;
import com.msa.payment.global.config.toss.TossProperties;
import com.msa.payment.global.response.ApiResponse;
import com.msa.payment.global.response.SuccessCode;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 공개 결제 승인 API를 만들지 않습니다. 승인은 Order의 Kafka 명령만 받으며 브라우저는 SDK 키만 조회합니다. */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentConfigurationController {
    private final TossProperties props;

    public PaymentConfigurationController(TossProperties props) {
        this.props = props;
    }

    @GetMapping("/client-configuration")
    @PreAuthorize("hasRole('BUYER')")
    public ResponseEntity<ApiResponse<ClientConfigurationResponse>> configuration() {
        if (!props.enabled()) throw new PaymentException(PaymentErrorCode.SERVICE_UNAVAILABLE);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiResponse.onSuccess(
                                ClientConfigurationResponse.builder()
                                        .clientKey(props.clientKey())
                                        .build(),
                                SuccessCode.OK));
    }
}
