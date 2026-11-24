package com.msa.payment.domain.membership.dto.request;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

/** 계정 서비스가 본인 확인 후 발행하는 세대별 해지 의도입니다. */
public record MembershipCommand(
        @NotNull UUID eventId,
        @Min(1) @Max(1) int schemaVersion,
        @Pattern(regexp = "MEMBERSHIP_CANCEL_REQUESTED") String type,
        @NotNull UUID accountId,
        @NotNull UUID subscriptionId,
        @Min(1) long generation,
        @Min(1) long commandVersion,
        @NotNull Instant occurredAt) {}
