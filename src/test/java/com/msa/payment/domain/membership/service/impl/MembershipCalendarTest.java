package com.msa.payment.domain.membership.service.impl;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.*;

/** 말일 보정은 다음 달에 원 가입일을 복원하고 지연 승인일을 청구 기준으로 쓰지 않습니다. */
class MembershipCalendarTest {
    private final MembershipCalendarImpl calendar = new MembershipCalendarImpl();
    private final ZoneId zone = ZoneId.of("Asia/Seoul");
    private final LocalTime time = LocalTime.of(9, 30);

    @Test
    void restoresThirtyFirstAfterShortMonthAndHandlesLeapYear() {
        for (int year : new int[] {2027, 2028}) {
            var january = LocalDate.of(year, 1, 31).atTime(time).atZone(zone).toInstant();
            var february = calendar.next(january, 31, time, zone);
            assertThat(february.atZone(zone).getDayOfMonth()).isEqualTo(year == 2028 ? 29 : 28);
            assertThat(calendar.next(february, 31, time, zone).atZone(zone).toLocalDate())
                    .isEqualTo(LocalDate.of(year, 3, 31));
        }
    }

    @Test
    void preservesKoreanTimeAcrossYearBoundary() {
        var start = LocalDate.of(2026, 12, 25).atTime(time).atZone(zone).toInstant();
        var next = calendar.next(start, 25, time, zone).atZone(zone);
        assertThat(next.toLocalDate()).isEqualTo(LocalDate.of(2027, 1, 25));
        assertThat(next.toLocalTime()).isEqualTo(time);
        assertThatThrownBy(() -> calendar.next(start, 32, time, zone))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
