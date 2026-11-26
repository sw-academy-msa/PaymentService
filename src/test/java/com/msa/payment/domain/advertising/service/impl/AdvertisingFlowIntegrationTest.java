package com.msa.payment.domain.advertising.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.msa.payment.domain.advertising.dto.request.*;
import com.msa.payment.domain.advertising.dto.response.*;
import com.msa.payment.domain.advertising.exception.AdvertisingException;
import com.msa.payment.domain.advertising.repository.AdvertisingPaymentRepository;
import com.msa.payment.domain.advertising.service.*;
import com.msa.payment.domain.membership.service.MembershipWithdrawalService;
import com.msa.payment.domain.payment.dto.response.ProviderReply;
import com.msa.payment.domain.payment.entity.PaymentAttempt.Status;
import com.msa.payment.global.config.database.PaymentDatabaseTestSupport;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;

/** 실제 격리 DB에서 회차, 금융 임대, 취소 선도착, 권한 및 Outbox 원자성을 검사합니다. */
@SpringBootTest(
        properties = {
            "payment.advertising.enabled=true",
            "payment.advertising.consumer-enabled=true",
            "payment.advertising.processing-enabled=true",
            "payment.advertising.recovery-delay=3600000",
            "payment.toss.enabled=true",
            "payment.toss.client-key=test_ck_synthetic",
            "payment.toss.secret-key=test_sk_synthetic",
            "spring.kafka.listener.auto-startup=false"
        })
@ActiveProfiles("test")
@Import(PaymentDatabaseTestSupport.Time.class)
@AutoConfigureMockMvc
class AdvertisingFlowIntegrationTest extends PaymentDatabaseTestSupport {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    @Autowired AdvertisingCommandService commands;
    @Autowired AdvertisingService api;
    @Autowired AdvertisingWorkStore work;
    @Autowired AdvertisingPaymentRepository payments;
    @Autowired MembershipWithdrawalService withdrawals;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired MutableClock clock;
    @Autowired MockMvc mvc;
    UUID application;
    UUID submission;
    UUID product;
    UUID seller;

    @BeforeEach
    void reset() {
        jdbc.execute(
                "truncate payment.advertising_payments, payment.advertising_command_receipts,"
                        + " payment.membership_withdrawal_fences");
        jdbc.update(
                "delete from payment.outbox_events where topic='payment.advertising.results.v1'");
        clock.set(NOW);
        application = UUID.randomUUID();
        submission = UUID.randomUUID();
        product = UUID.randomUUID();
        seller = UUID.randomUUID();
    }

    private AdvertisingCommand command(String type, UUID event) {
        var payload =
                new AdvertisingCommand.Payload(
                        application,
                        submission,
                        product,
                        seller,
                        null,
                        3L,
                        HASH,
                        200000L,
                        "KRW",
                        "V1",
                        NOW.plusSeconds(300),
                        "AdvertisingPaymentCancellationRequested".equals(type)
                                ? "PRODUCT_REJECTED"
                                : null);
        return new AdvertisingCommand(
                event,
                type,
                1,
                application,
                "AdvertisingPaymentCancellationRequested".equals(type) ? 2 : 1,
                NOW,
                application,
                null,
                payload);
    }

    private void send(AdvertisingCommand c) {
        commands.receive(application.toString(), json.writeValueAsBytes(c));
    }

    private AdvertisingPaymentResponse prepared() {
        send(command("AdvertisingPaymentRequested", UUID.randomUUID()));
        return api.get(seller, payments.findByApplicationId(application).orElseThrow().id);
    }

    private AdvertisingWork approval() {
        var p = prepared();
        api.complete(
                seller,
                p.paymentId(),
                UUID.randomUUID(),
                new AdvertisingCompletionRequest(
                        "synthetic-ad-key-" + p.paymentId(), "AD-" + p.paymentId()));
        return work.claim();
    }

    private ProviderReply reply(Status status) {
        return ProviderReply.builder()
                .status(status)
                .paymentKey("synthetic-ad-key")
                .approvedAt(status == Status.APPROVED || status == Status.CANCELED ? NOW : null)
                .build();
    }

    @Test
    void duplicatePreparationCreatesOnePaymentAndOneOutbox() {
        var c = command("AdvertisingPaymentRequested", UUID.randomUUID());
        send(c);
        send(c);
        assertThatThrownBy(() -> send(command("AdvertisingPaymentRequested", UUID.randomUUID())))
                .isInstanceOf(AdvertisingException.class);
        assertThat(payments.count()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment.outbox_events where"
                                        + " topic='payment.advertising.results.v1'",
                                Long.class))
                .isEqualTo(1);
        var p = payments.findByApplicationId(application).orElseThrow();
        assertThat(p.tossOrderId).isEqualTo("AD-" + p.id);
        assertThat(api.checkout(seller, p.id).amount()).isEqualTo(200000);
    }

    @Test
    void completionIsIdempotentAndRejectsDifferentBodyOrSeller() {
        var p = prepared();
        var key = UUID.randomUUID();
        var request = new AdvertisingCompletionRequest("synthetic-ad-key", "AD-" + p.paymentId());
        assertThat(api.complete(seller, p.paymentId(), key, request).paymentStatus())
                .isEqualTo("PENDING");
        api.complete(seller, p.paymentId(), key, request);
        assertThatThrownBy(
                        () ->
                                api.complete(
                                        seller,
                                        p.paymentId(),
                                        key,
                                        new AdvertisingCompletionRequest(
                                                "changed-key", request.tossOrderId())))
                .isInstanceOf(AdvertisingException.class);
        assertThatThrownBy(() -> api.get(UUID.randomUUID(), p.paymentId()))
                .isInstanceOf(AdvertisingException.class);
    }

    @Test
    void cancellationBeforePreparationPersistsTombstoneAndBlocksApproval() {
        send(command("AdvertisingPaymentCancellationRequested", UUID.randomUUID()));
        var p = prepared();
        assertThat(p.paymentStatus()).isEqualTo("FAILED");
        assertThat(p.cancellationRequested()).isTrue();
        assertThat(work.claim()).isNull();
        assertThatThrownBy(() -> api.checkout(seller, p.paymentId()))
                .isInstanceOf(AdvertisingException.class);
    }

    @Test
    void cancellationDuringApprovalKeepsPaidAndRecoversToCanceled() {
        var first = approval();
        send(command("AdvertisingPaymentCancellationRequested", UUID.randomUUID()));
        work.complete(first, reply(Status.APPROVED));
        var paid = api.get(seller, first.paymentId());
        assertThat(paid.paymentStatus()).isEqualTo("PAID");
        assertThat(paid.cancellationRequested()).isTrue();
        clock.set(NOW.plusSeconds(3));
        var cancel = work.claim();
        assertThat(cancel.request().cancellationRequested()).isTrue();
        assertThat(cancel.request().firstApproval()).isFalse();
        work.complete(cancel, ProviderReply.unknown("CANCEL_UNKNOWN"));
        assertThat(api.get(seller, first.paymentId()).paymentStatus()).isEqualTo("PAID");
        clock.set(NOW.plusSeconds(10));
        cancel = work.claim();
        work.complete(cancel, reply(Status.CANCELED));
        assertThat(api.get(seller, first.paymentId()).paymentStatus()).isEqualTo("CANCELED");
        assertThat(api.get(seller, first.paymentId()).canceledAt()).isNotNull();
    }

    @Test
    void expiredLeaseRejectsOldResultAndReclaimsWithSameProviderKey() {
        var first = approval();
        clock.set(NOW.plusSeconds(31));
        work.complete(first, reply(Status.APPROVED));
        assertThat(api.get(seller, first.paymentId()).paymentStatus()).isEqualTo("PENDING");
        var second = work.claim();
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(second.request().approvalIdempotencyKey())
                .isEqualTo(first.request().approvalIdempotencyKey());
        assertThat(second.request().firstApproval()).isFalse();
        work.complete(first, reply(Status.APPROVED));
        work.complete(second, reply(Status.APPROVED));
        assertThat(api.get(seller, first.paymentId()).paymentStatus()).isEqualTo("PAID");
    }

    @Test
    void expiryWithoutApprovalFailsLocallyAndLateUnknownApprovalKeepsReconciling() {
        var p = prepared();
        clock.set(NOW.plusSeconds(301));
        assertThat(work.claim()).isNull();
        assertThat(api.get(seller, p.paymentId()).paymentStatus()).isEqualTo("FAILED");
        clock.set(NOW);
        application = UUID.randomUUID();
        submission = UUID.randomUUID();
        var started = approval();
        work.complete(started, ProviderReply.unknown("APPROVAL_UNKNOWN"));
        clock.set(NOW.plusSeconds(301));
        var recovered = work.claim();
        assertThat(recovered.request().firstApproval()).isFalse();
        work.complete(recovered, reply(Status.APPROVED));
        assertThat(api.get(seller, recovered.paymentId()).paymentStatus()).isEqualTo("PAID");
    }

    @Test
    void oldRoundCancellationCannotCancelNewRound() {
        var old = approval();
        work.complete(old, reply(Status.APPROVED));
        var cancellation = command("AdvertisingPaymentCancellationRequested", UUID.randomUUID());
        application = UUID.randomUUID();
        submission = UUID.randomUUID();
        var next = approval();
        work.complete(next, reply(Status.APPROVED));
        commands.receive(
                cancellation.aggregateId().toString(), json.writeValueAsBytes(cancellation));
        assertThat(api.get(seller, old.paymentId()).cancellationRequested()).isTrue();
        assertThat(api.get(seller, next.paymentId()).cancellationRequested()).isFalse();
    }

    @Test
    void withdrawalIncludesAdvertisingWithoutMembershipAndFencesNewPreparation() {
        var p = prepared();
        var operation = UUID.randomUUID();
        assertThat(withdrawals.prepare(seller, operation)).isEqualTo(1);
        assertThat(withdrawals.finalizeWithdrawal(seller, operation)).isEqualTo(1);
        application = UUID.randomUUID();
        submission = UUID.randomUUID();
        assertThat(prepared().paymentStatus()).isEqualTo("FAILED");
        assertThat(api.get(seller, p.paymentId()).paymentStatus()).isEqualTo("PENDING");
    }

    @Test
    void wrongKeyAndSnapshotChangesRollbackWithoutExtraPayments() {
        var c = command("AdvertisingPaymentRequested", UUID.randomUUID());
        send(c);
        assertThatThrownBy(
                        () ->
                                commands.receive(
                                        UUID.randomUUID().toString(), json.writeValueAsBytes(c)))
                .isInstanceOf(AdvertisingException.class);
        var bad = json.writeValueAsString(c).replace("200000", "200001");
        assertThatThrownBy(
                        () ->
                                commands.receive(
                                        application.toString(),
                                        bad.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(AdvertisingException.class);
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void sellerApiRejectsBuyerAnonymousAndAnotherSellerAndRedactsPaymentKey() throws Exception {
        var p = prepared();
        String path = "/api/v1/payments/advertising/" + p.paymentId();
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(
                        get(path)
                                .with(
                                        jwt().jwt(
                                                        j ->
                                                                j.subject(seller.toString())
                                                                        .claim(
                                                                                "principal_type",
                                                                                "user"))
                                                .authorities(
                                                        new SimpleGrantedAuthority("ROLE_BUYER"))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get(path)
                                .with(
                                        jwt().jwt(
                                                        j ->
                                                                j.subject(
                                                                                UUID.randomUUID()
                                                                                        .toString())
                                                                        .claim(
                                                                                "principal_type",
                                                                                "user"))
                                                .authorities(
                                                        new SimpleGrantedAuthority("ROLE_SELLER"))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get(path)
                                .with(
                                        jwt().jwt(
                                                        j ->
                                                                j.subject(seller.toString())
                                                                        .claim(
                                                                                "principal_type",
                                                                                "user"))
                                                .authorities(
                                                        new SimpleGrantedAuthority("ROLE_SELLER"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.paymentKey").doesNotExist());
    }
}
