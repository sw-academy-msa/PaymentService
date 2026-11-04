package com.msa.payment.global.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** 보안 실패 응답에도 추적 ID를 부여하며 헤더·본문·토큰 등의 요청 원문은 기록하지 않습니다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {
    private static final String TRACE_ATTRIBUTE = RequestTraceFilter.class.getName() + ".traceId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /** 원래 필터 호출 중 중첩된 ERROR 디스패치도 동일한 MDC를 복원합니다. */
    @Override
    protected void doFilterNestedErrorDispatch(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        doFilterInternal(request, response, chain);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object assigned = request.getAttribute(TRACE_ATTRIBUTE);
        String incoming =
                assigned instanceof String value ? value : request.getHeader("X-Request-Id");
        // 신뢰할 수 없는 긴 문자열·개행을 MDC에 넣지 않도록 형식과 길이를 제한합니다.
        String traceId =
                incoming != null && SAFE_ID.matcher(incoming).matches()
                        ? incoming
                        : UUID.randomUUID().toString();
        // 오류 재디스패치에서도 최초 요청과 같은 ID를 사용합니다.
        request.setAttribute(TRACE_ATTRIBUTE, traceId);
        String previous = MDC.get("traceId");
        MDC.put("traceId", traceId);
        response.setHeader("X-Request-Id", traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 요청 스레드 재사용과 중첩 실행 모두 이전 MDC 상태로 복원합니다.
            if (previous == null) {
                MDC.remove("traceId");
            } else {
                MDC.put("traceId", previous);
            }
        }
    }
}
