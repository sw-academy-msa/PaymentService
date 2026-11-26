package com.msa.payment.domain.advertising.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

/** 전용 내부 토픽에서만 수신하는 광고 준비 및 취소 명령입니다. */
public record AdvertisingCommand(
        @NotNull UUID eventId,
        @NotBlank String eventType,
        @Min(1) @Max(1) int schemaVersion,
        @NotNull UUID aggregateId,
        @Min(1) long aggregateVersion,
        @NotNull Instant occurredAt,
        @NotNull UUID correlationId,
        UUID causationId,
        @NotNull @Valid Payload payload) {
    public record Payload(
            @NotNull UUID applicationId,
            @NotNull UUID submissionId,
            @NotNull UUID productId,
            @NotNull UUID sellerId,
            UUID paymentId,
            Long productVersion,
            String quoteHash,
            Long amount,
            String currency,
            String formulaVersion,
            Instant expiresAt,
            String reasonCode) {
        @Override
        public String toString() {
            return "AdvertisingCommand.Payload[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "AdvertisingCommand[REDACTED]";
    }
}
