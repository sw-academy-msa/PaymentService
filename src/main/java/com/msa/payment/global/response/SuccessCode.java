package com.msa.payment.global.response;

/** 성공 본문의 코드와 메시지입니다. 실제 HTTP 상태는 컨트롤러의 상태 선언을 따릅니다. */
public enum SuccessCode {
    OK("COMMON_SUCCESS", "요청이 성공했습니다."),
    CREATED("COMMON_CREATED", "생성이 완료되었습니다.");

    private final String code;
    private final String message;

    SuccessCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
