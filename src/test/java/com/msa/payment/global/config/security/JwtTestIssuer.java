package com.msa.payment.global.config.security;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;

/** 테스트 메모리 키만 사용하며 실제 Account나 외부 네트워크 없이 JWKS 조회를 검증합니다. */
final class JwtTestIssuer implements AutoCloseable {
    private final RSAKey key;
    private final HttpServer server;

    JwtTestIssuer() {
        try {
            key = new RSAKeyGenerator(2048).keyID("isolated-payment-test").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] jwks =
                    JsonMapper.builder()
                            .build()
                            .writeValueAsBytes(new JWKSet(key.toPublicJWK()).toJSONObject());
            server.createContext(
                    "/jwks",
                    exchange -> {
                        try (exchange) {
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, jwks.length);
                            exchange.getResponseBody().write(jwks);
                        }
                    });
            server.start();
        } catch (Exception exception) {
            throw new IllegalStateException("테스트 JWKS 준비 실패", exception);
        }
    }

    String uri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    String token(JwtClaimsSet claims) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256)
                                        .keyId(key.getKeyID())
                                        .build(),
                                claims))
                .getTokenValue();
    }

    public void close() {
        server.stop(0);
    }
}
