package com.msa.payment.domain.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.msa.payment.domain.analytics.dto.request.PaymentAnalytics;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

/** Order 전용 Kafka 요청 계약입니다. 공개 HTTP 요청 DTO가 아닙니다. */
public record PaymentCommand(
        @NotNull UUID eventId,
        @Min(1) @Max(1) int schemaVersion,
        @NotBlank @Pattern(regexp = "PAYMENT_COMMAND") String type,
        @NotBlank @Pattern(regexp = "APPROVE|CHECK|CANCEL") String action,
        @NotNull UUID orderId,
        @NotNull UUID paymentAttemptId,
        @Positive long commandVersion,
        @NotNull Instant occurredAt,
        @Positive long amount,
        @NotNull Instant expiresAt,
        @NotNull Boolean cancellationRequested,
        @Size(max = 200) String paymentKey,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Valid PaymentAnalytics analytics) {
    public PaymentCommand(
            UUID eventId,
            int schemaVersion,
            String type,
            String action,
            UUID orderId,
            UUID paymentAttemptId,
            long commandVersion,
            Instant occurredAt,
            long amount,
            Instant expiresAt,
            Boolean cancellationRequested,
            String paymentKey) {
        this(
                eventId,
                schemaVersion,
                type,
                action,
                orderId,
                paymentAttemptId,
                commandVersion,
                occurredAt,
                amount,
                expiresAt,
                cancellationRequested,
                paymentKey,
                null);
    }

    @Override
    public String toString() {
        return "PaymentCommand[eventId=" + eventId + "]";
    }
}
