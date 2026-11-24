package com.msa.payment.domain.membership.service.impl;

import com.msa.payment.domain.membership.service.MembershipCalendar;

import org.springframework.stereotype.Service;

import java.time.*;

/** 이전 실제 결제 성공일이 아닌 예정 월과 원 가입일을 기준으로 다음 기간을 정합니다. */
@Service
public class MembershipCalendarImpl implements MembershipCalendar {
    public Instant next(Instant scheduledAt, int anchorDay, LocalTime anchorTime, ZoneId zone) {
        if (anchorDay < 1 || anchorDay > 31)
            throw new IllegalArgumentException("가입일은 1~31일이어야 합니다.");
        var month = YearMonth.from(scheduledAt.atZone(zone)).plusMonths(1);
        return month.atDay(Math.min(anchorDay, month.lengthOfMonth()))
                .atTime(anchorTime)
                .atZone(zone)
                .toInstant();
    }
}
