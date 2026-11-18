package com.msa.payment.domain.withdrawal.controller;

import com.msa.payment.domain.withdrawal.dto.request.PaymentClearanceRequest;
import com.msa.payment.domain.withdrawal.dto.response.PaymentClearanceResponse;
import com.msa.payment.domain.withdrawal.service.PaymentClearanceService;
import com.msa.payment.global.response.*;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

/** 공개 Gateway 경로와 구분된 Account 전용 원본 확인 API입니다. */
@io.swagger.v3.oas.annotations.Hidden
@RestController
@RequestMapping(value = "/internal/v1/account-withdrawals/payments", produces = "application/json")
public class PaymentClearanceController {
    private final PaymentClearanceService service;

    public PaymentClearanceController(PaymentClearanceService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<PaymentClearanceResponse> inspect(
            @Valid @RequestBody PaymentClearanceRequest request) {
        return ApiResponse.onSuccess(service.inspect(request), SuccessCode.OK);
    }
}
