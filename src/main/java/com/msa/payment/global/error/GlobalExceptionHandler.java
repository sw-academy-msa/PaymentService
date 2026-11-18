package com.msa.payment.global.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** MVC 예외를 Account와 같은 형식으로 변환하며 SQL·요청값이 담긴 예외 원문은 기록하지 않습니다. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> business(BusinessException exception) {
        return response(exception.getErrorCode());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException exception) {
        // 거절된 입력값은 포함하지 않습니다. DTO 검증 메시지도 원문 값을 삽입하지 않아야 합니다.
        var fields =
                exception.getBindingResult().getFieldErrors().stream()
                        .map(
                                error ->
                                        new ErrorResponse.FieldViolation(
                                                error.getField(), error.getDefaultMessage()))
                        .toList();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.validation(MDC.get("traceId"), fields));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class
    })
    public ResponseEntity<ErrorResponse> invalid(Exception exception) {
        return response(CommonErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> forbidden(AccessDeniedException exception) {
        return response(CommonErrorCode.FORBIDDEN);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> unauthorized(AuthenticationException exception) {
        return response(CommonErrorCode.UNAUTHORIZED);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> missing(NoResourceFoundException exception) {
        return response(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> method(Exception exception) {
        return response(CommonErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> media(Exception exception) {
        return response(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    /** Accept 협상이 실패해도 JSON 오류 응답을 기록할 수 있도록 형식을 명시합니다. */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> notAcceptable(Exception exception) {
        return ResponseEntity.status(CommonErrorCode.NOT_ACCEPTABLE.status())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .body(ErrorResponse.of(CommonErrorCode.NOT_ACCEPTABLE, MDC.get("traceId")));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> optimistic(Exception exception) {
        return response(CommonErrorCode.OPTIMISTIC_LOCK_CONFLICT);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> conflict(Exception exception) {
        return response(CommonErrorCode.DATA_CONFLICT);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception exception) {
        log.error(
                "요청 처리 실패: type={}, traceId={}",
                exception.getClass().getSimpleName(),
                MDC.get("traceId"));
        return response(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ErrorResponse> response(ErrorCode code) {
        return ResponseEntity.status(code.status())
                .header("Cache-Control", "no-store")
                .body(ErrorResponse.of(code, MDC.get("traceId")));
    }
}
