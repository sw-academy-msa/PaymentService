package com.msa.payment.domain.withdrawal.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.*;

/** Order 원본에서 소속을 확인한 결제 ID만 Account가 전달합니다. 결제키나 계좌는 받지 않습니다. */
public record PaymentClearanceRequest(
        @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Reference> references) {
    public record Reference(
            @NotNull UUID orderId, @NotNull UUID paymentAttemptId, boolean approved) {}
}
