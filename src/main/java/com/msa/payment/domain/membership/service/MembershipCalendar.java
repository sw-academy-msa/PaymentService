package com.msa.payment.domain.membership.service;

import java.time.*;

/** 짧은 달 뒤에도 원 가입일로 복귀하는 청구 날짜를 계산합니다. */
public interface MembershipCalendar {
    Instant next(Instant scheduledAt, int anchorDay, LocalTime anchorTime, ZoneId zone);
}
