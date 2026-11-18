package com.msa.payment.global.logging;

import static org.assertj.core.api.Assertions.*;

import jakarta.servlet.DispatcherType;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.*;

/** 별도·중첩 ERROR 디스패치와 MDC 정리를 실제 필터 진입점에서 검증합니다. */
class RequestTraceFilterTest {
    @Test
    void errorDispatchKeepsOriginallyGeneratedTraceAndRestoresMdc() throws Exception {
        var filter = new RequestTraceFilter();
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        request.addHeader("X-Request-Id", "invalid trace");
        filter.doFilter(
                request, response, (req, res) -> assertThat(MDC.get("traceId")).isNotBlank());
        String original = response.getHeader("X-Request-Id");
        assertThat(original).matches("[A-Za-z0-9._-]{1,64}");
        assertThat(MDC.get("traceId")).isNull();
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute("jakarta.servlet.error.request_uri", "/original");
        MDC.put("traceId", "previous-context");
        try {
            filter.doFilter(
                    request,
                    response,
                    (req, res) -> assertThat(MDC.get("traceId")).isEqualTo(original));
            assertThat(response.getHeader("X-Request-Id")).isEqualTo(original);
            assertThat(MDC.get("traceId")).isEqualTo("previous-context");
        } finally {
            MDC.clear();
        }
    }

    @Test
    void nestedErrorDispatchUsesValidatedIncomingTrace() throws Exception {
        var filter = new RequestTraceFilter();
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        request.addHeader("X-Request-Id", "safe-request-1");
        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    request.setAttribute("jakarta.servlet.error.request_uri", "/original");
                    filter.doFilter(
                            request,
                            response,
                            (innerReq, innerRes) ->
                                    assertThat(MDC.get("traceId")).isEqualTo("safe-request-1"));
                    assertThat(MDC.get("traceId")).isEqualTo("safe-request-1");
                });
        assertThat(MDC.get("traceId")).isNull();
    }
}
