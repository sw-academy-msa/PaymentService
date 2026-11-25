package com.msa.payment.domain.analytics.dto.request;

import jakarta.validation.constraints.*;

import java.util.UUID;

/** Order 원본에서 온 분석 스냅샷이며 가명 ID는 전송 직전 동의를 다시 확인합니다. */
public record PaymentAnalytics(
        @NotNull UUID accountId,
        @NotNull UUID analyticsId,
        @NotBlank @Size(max = 100) @Pattern(regexp = "[0-9]+\\.[0-9]+") String clientId,
        @NotBlank @Size(max = 40) @Pattern(regexp = "[0-9]+") String sessionId,
        @NotNull UUID productId,
        @NotBlank @Size(max = 200) String productName,
        @Positive int quantity,
        @Positive long unitPrice,
        @Positive long itemsAmount,
        @PositiveOrZero long couponDiscount,
        @Size(max = 100) String couponName,
        @PositiveOrZero long shipping) {
    @AssertTrue
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isAmountValid() {
        try {
            return Math.multiplyExact(unitPrice, quantity) == itemsAmount
                    && couponDiscount <= Math.max(0, itemsAmount - 100);
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "PaymentAnalytics[REDACTED]";
    }
}
