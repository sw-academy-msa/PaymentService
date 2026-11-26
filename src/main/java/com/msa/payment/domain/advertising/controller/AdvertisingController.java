package com.msa.payment.domain.advertising.controller;

import com.msa.payment.domain.advertising.dto.request.AdvertisingCompletionRequest;
import com.msa.payment.domain.advertising.dto.response.*;
import com.msa.payment.domain.advertising.exception.*;
import com.msa.payment.domain.advertising.service.AdvertisingService;
import com.msa.payment.global.response.*;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.validation.Valid;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 인증된 판매자의 본인 광고 결제만 공개하고 금융 응답의 캐시를 금지합니다. */
@RestController
@RequestMapping(value = "/api/v1/payments/advertising", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize(
        "hasRole('SELLER') and !hasRole('ADMIN') and principal.claims['principal_type'] == 'user'")
public class AdvertisingController {
    private final AdvertisingService service;

    public AdvertisingController(AdvertisingService service) {
        this.service = service;
    }

    @Operation(
            summary = "본인 광고 테스트 결제 설정 조회",
            description = "서버 확정 금액과 공개 test_ 키를 반환합니다. 상태 충돌은 409, 기능 미준비는 503입니다.")
    @GetMapping("/{id}/checkout")
    public ResponseEntity<ApiResponse<AdvertisingCheckoutResponse>> checkout(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return response(service.checkout(account(jwt), id), HttpStatus.OK);
    }

    @Operation(
            summary = "본인 광고 결제 원본 조회",
            description = "PENDING은 확인 중이며 PAID도 상품 심사 접수 완료를 뜻하지 않습니다.")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AdvertisingPaymentResponse>> get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return response(service.get(account(jwt), id), HttpStatus.OK);
    }

    @Operation(
            summary = "본인 광고 결제 승인 접수",
            description =
                    "Idempotency-Key UUID가 필요합니다. 202는 비동기 승인 접수이며 결제 및 심사 완료가 아닙니다. 같은 키의 본문 충돌은"
                            + " 409입니다.")
    @PostMapping("/{id}/completion")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "202",
                description = "승인 접수"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "상태 또는 멱등 키 충돌"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "503",
                description = "금융 처리 미준비")
    })
    public ResponseEntity<ApiResponse<AdvertisingPaymentResponse>> complete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody AdvertisingCompletionRequest request) {
        return response(service.complete(account(jwt), id, key, request), HttpStatus.ACCEPTED);
    }

    private UUID account(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException failure) {
            throw new AdvertisingException(AdvertisingErrorCode.OWNERSHIP);
        }
    }

    private <T> ResponseEntity<ApiResponse<T>> response(T data, HttpStatus status) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.onSuccess(data, SuccessCode.OK));
    }
}
