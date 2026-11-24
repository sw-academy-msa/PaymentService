package com.msa.payment.domain.withdrawal.controller;

import com.msa.payment.domain.membership.service.MembershipWithdrawalService;
import com.msa.payment.global.response.*;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 기존 Account 전용 서비스 토큰으로만 회원제 탈퇴 차단과 금융 종료를 요청합니다. */
@io.swagger.v3.oas.annotations.Hidden
@RestController
@RequestMapping(
        value = "/internal/v1/account-withdrawals/{account}/memberships",
        produces = "application/json")
public class MembershipWithdrawalController {
    private final MembershipWithdrawalService service;

    public MembershipWithdrawalController(MembershipWithdrawalService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Map<String, Long>> inspect(@PathVariable UUID account) {
        return response(service.inspect(account));
    }

    @PutMapping("/fences/{operation}")
    public ApiResponse<Map<String, Long>> prepare(
            @PathVariable UUID account, @PathVariable UUID operation) {
        return response(service.prepare(account, operation));
    }

    @PostMapping("/fences/{operation}/completion")
    public ApiResponse<Map<String, Long>> complete(
            @PathVariable UUID account, @PathVariable UUID operation) {
        return response(service.finalizeWithdrawal(account, operation));
    }

    @DeleteMapping("/fences/{operation}")
    public ApiResponse<Map<String, Long>> release(
            @PathVariable UUID account, @PathVariable UUID operation) {
        service.release(account, operation);
        return response(0);
    }

    private ApiResponse<Map<String, Long>> response(long pending) {
        return ApiResponse.onSuccess(Map.of("pendingMemberships", pending), SuccessCode.OK);
    }
}
