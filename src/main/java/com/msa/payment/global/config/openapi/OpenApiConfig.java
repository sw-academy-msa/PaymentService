package com.msa.payment.global.config.openapi;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;

import org.springframework.context.annotation.*;

/** 실제 공개 컨트롤러만 문서화하며 Kafka 승인·보상 명령을 HTTP API라고 표시하지 않습니다. */
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI paymentOpenApi(
            @org.springframework.beans.factory.annotation.Value(
                            "${payment.openapi.gateway-base-url:http://localhost:8000}")
                    String gatewayBaseUrl) {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("STOCKDROP Payment")
                                .version("v1")
                                .description(
                                        "BUYER의 구매 SDK 설정과 SELLER 본인의 광고 테스트 결제 조회 및 승인 접수를 제공합니다."
                                                + " 구매 결제는 Order 전용 Kafka 명령으로 처리하며 광고는 별도 회차와 토픽을"
                                                + " 사용합니다."))
                // 기본 진입점은 일반 Gateway이며 직접 서비스 주소는 개발 확인용입니다.
                .servers(
                        java.util.List.of(
                                new io.swagger.v3.oas.models.servers.Server()
                                        .url(gatewayBaseUrl)
                                        .description("일반 Gateway"),
                                new io.swagger.v3.oas.models.servers.Server()
                                        .url("http://localhost:8084")
                                        .description("로컬 개발 서비스")))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
