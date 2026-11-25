package com.msa.payment.domain.membership.query.controller;

import com.msa.payment.domain.membership.dto.request.*;
import com.msa.payment.domain.membership.dto.response.*;
import com.msa.payment.domain.membership.exception.*;
import com.msa.payment.domain.membership.service.MembershipService;
import com.msa.payment.global.response.*;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.validation.Valid;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 검증한 사용자 sub로만 본인 카드 인증을 접수하며 모든 응답의 캐시를 금지합니다. */
@RestController
@RequestMapping(value = "/api/v1/payments/memberships", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize(
        "hasRole('BUYER') and !hasRole('ADMIN') and principal.claims['principal_type'] == 'user'")
public class MembershipController {
    private final MembershipService service;

    public MembershipController(MembershipService service) {
        this.service = service;
    }

    @Operation(summary = "멤버십 요금과 카드 인증 설정 조회")
    @GetMapping("/configuration")
    public ResponseEntity<ApiResponse<MembershipConfigurationResponse>> configuration() {
        return response(service.configuration(), HttpStatus.OK);
    }

    @Operation(summary = "현재 요금과 약관에 동의하고 본인 카드 인증 세션 생성")
    @PostMapping("/auth-sessions")
    public ResponseEntity<ApiResponse<MembershipSessionResponse>> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody MembershipAuthRequest request) {
        return response(service.create(account(jwt), key, request), HttpStatus.CREATED);
    }

    @Operation(summary = "본인 SDK 카드 인증 결과 접수")
    @PostMapping("/auth-sessions/{id}/completion")
    public ResponseEntity<ApiResponse<MembershipOperationResponse>> complete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody MembershipCompletionRequest request) {
        return response(service.complete(account(jwt), id, key, request), HttpStatus.ACCEPTED);
    }

    @Operation(summary = "본인 멤버십 금융 처리 상태 조회")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MembershipResponse>> get(@AuthenticationPrincipal Jwt jwt) {
        return response(service.get(account(jwt)), HttpStatus.OK);
    }

    private UUID account(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException failure) {
            throw new MembershipException(MembershipErrorCode.FORBIDDEN);
        }
    }

    private <T> ResponseEntity<ApiResponse<T>> response(T data, HttpStatus status) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiResponse.onSuccess(
                                data,
                                status == HttpStatus.CREATED
                                        ? SuccessCode.CREATED
                                        : SuccessCode.OK));
    }
}
