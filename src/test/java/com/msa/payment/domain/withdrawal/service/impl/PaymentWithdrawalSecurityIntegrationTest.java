package com.msa.payment.domain.withdrawal.service.impl;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.msa.payment.global.config.database.PaymentDatabaseTestSupport;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

import java.net.*;
import java.time.*;
import java.util.*;

/** Payment 사용자 JWT 경계와 Account 전용 내부 체인을 분리해 검증합니다. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PaymentWithdrawalSecurityIntegrationTest extends PaymentDatabaseTestSupport {
    static final UUID SUBJECT = UUID.randomUUID();
    static final RSAKey KEY;
    static final HttpServer JWKS;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("withdrawal-test").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext(
                    "/jwks",
                    e -> {
                        var body = new JWKSet(KEY.toPublicJWK()).toString().getBytes();
                        e.getResponseHeaders().set("Content-Type", "application/json");
                        e.sendResponseHeaders(200, body.length);
                        e.getResponseBody().write(body);
                        e.close();
                    });
            JWKS.start();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("ACCOUNT_WITHDRAWAL_SUBJECT", SUBJECT::toString);
        p.add(
                "payment.auth.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
        p.add("payment.auth.issuer", () -> "https://account.test");
    }

    @AfterAll
    static void stop() {
        JWKS.stop(0);
    }

    @Autowired MockMvc mvc;

    @Test
    void designatedServiceCanReadButCannotBecomeBuyer() throws Exception {
        String path = "/internal/v1/account-withdrawals/payments";
        String body =
                "{\"references\":[{\"orderId\":\"11111111-1111-1111-1111-111111111111\",\"paymentAttemptId\":\"22222222-2222-2222-2222-222222222222\",\"approved\":true}]}";
        mvc.perform(post(path).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post(path)
                                .contentType("application/json")
                                .content(body)
                                .header("Authorization", "Bearer " + token(null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingPayments").value(1));
        for (String missing : List.of("principal_type", "client_id", "scope", "sub"))
            mvc.perform(
                            post(path)
                                    .contentType("application/json")
                                    .content(body)
                                    .header("Authorization", "Bearer " + token(missing)))
                    .andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/api/v1/payments/client-configuration")
                                .header("Authorization", "Bearer " + token(null)))
                .andExpect(status().isUnauthorized());
    }

    String token(String missing) throws Exception {
        var claims =
                new JWTClaimsSet.Builder()
                        .issuer("https://account.test")
                        .audience("stockdrop-api")
                        .subject(SUBJECT.toString())
                        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                        .claim("token_use", "access")
                        .claim("principal_type", "service")
                        .claim("client_id", "account-service")
                        .claim("scope", "account.withdrawal");
        if (missing != null) claims.claim(missing, null);
        var signed =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                        claims.build());
        signed.sign(new RSASSASigner(KEY));
        return signed.serialize();
    }
}
