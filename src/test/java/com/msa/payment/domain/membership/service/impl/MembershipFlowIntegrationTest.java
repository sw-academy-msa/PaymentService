package com.msa.payment.domain.membership.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.msa.payment.domain.membership.dto.request.*;
import com.msa.payment.domain.membership.dto.response.*;
import com.msa.payment.domain.membership.dto.response.BillingReply.Status;
import com.msa.payment.domain.membership.exception.MembershipException;
import com.msa.payment.domain.membership.repository.*;
import com.msa.payment.domain.membership.service.*;
import com.msa.payment.global.config.database.PaymentDatabaseTestSupport;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 합성 키와 실제 DB로 임대 경합 및 늦은 승인과 해지의 금융 원본을 검증합니다. */
@SpringBootTest(
        properties = {
            "membership.billing.enabled=true",
            "membership.billing.client-key=test_ck_fixture",
            "membership.billing.secret-key=test_sk_fixture",
            "membership.crypto.data-key-v1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "membership.api-enabled=true",
            "membership.recovery-enabled=true",
            "membership.renewal-enabled=true",
            "membership.poll-delay=3600000"
        })
@ActiveProfiles("test")
@Import(PaymentDatabaseTestSupport.Time.class)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class MembershipFlowIntegrationTest extends PaymentDatabaseTestSupport {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired MembershipService api;
    @Autowired MembershipWorkStore store;
    @Autowired MembershipCommandService commands;
    @Autowired MembershipWithdrawalService withdrawals;
    @Autowired MembershipSubscriptionRepository subscriptions;
    @Autowired MembershipBillingAttemptRepository attempts;
    @Autowired MembershipAuthSessionRepository sessions;
    @Autowired MembershipBillingCredentialRepository credentials;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    UUID account;
    final Instant start = Instant.parse("2030-01-31T03:00:00Z");

    @BeforeEach
    void reset() {
        jdbc.execute(
                "truncate"
                    + " payment.membership_withdrawal_fences,payment.membership_command_receipts,payment.membership_billing_attempts,payment.membership_billing_cycles,payment.membership_auth_sessions,payment.membership_billing_credentials,payment.membership_subscriptions");
        jdbc.update(
                "delete from payment.outbox_events where"
                        + " topic='stockdrop.membership.events.private.v1'");
        clock.set(start);
        account = UUID.randomUUID();
    }

    private MembershipWork registered() {
        var session =
                api.create(
                        account,
                        UUID.randomUUID(),
                        new MembershipAuthRequest("monthly-v1", "recurring-v1", true));
        UUID key = UUID.randomUUID();
        var completion = new MembershipCompletionRequest("synthetic-auth", session.customerKey());
        api.complete(account, session.sessionId(), key, completion);
        api.complete(account, session.sessionId(), key, completion);
        var issue = store.claimIssue();
        assertThat(issue.action()).isEqualTo("ISSUE");
        store.complete(
                issue, new BillingReply(Status.ISSUED, "synthetic-billing", null, null, null));
        return store.claimCharge();
    }

    private void approve(MembershipWork work, Instant at) {
        store.complete(
                work,
                new BillingReply(
                        Status.APPROVED, null, "synthetic-payment-" + work.workId(), at, null));
    }

    private void cancel() {
        command("MEMBERSHIP_CANCEL_REQUESTED", 1);
    }

    private void command(String type, long version) {
        var s = subscriptions.findByAccountId(account).orElseThrow();
        var command =
                new MembershipCommand(
                        UUID.randomUUID(),
                        1,
                        type,
                        account,
                        s.id,
                        s.generation,
                        version,
                        clock.instant());
        commands.receive(account.toString(), json.writeValueAsBytes(command));
    }

    private List<String> resultTypes() {
        return jdbc
                .queryForList(
                        "select payload from payment.outbox_events where"
                                + " topic='stockdrop.membership.events.private.v1' order by"
                                + " created_at",
                        String.class)
                .stream()
                .map(p -> json.readTree(p).path("type").asText())
                .toList();
    }

    private MembershipWork declinedRenewal() {
        approve(registered(), start);
        clock.set(api.get(account).periodEnd());
        store.advance();
        var renewal = store.claimCharge();
        store.complete(
                renewal,
                new BillingReply(Status.DECLINED, null, null, null, "REJECT_CARD_PAYMENT"));
        assertThat(api.get(account).state()).isEqualTo("GRACE");
        return renewal;
    }

    @Test
    void monthEndRetryRetainsAnchorAndOriginalPrice() {
        approve(registered(), start);
        var first = api.get(account);
        assertThat(first.periodEnd()).isEqualTo(Instant.parse("2030-02-28T03:00:00Z"));
        clock.set(first.periodEnd());
        assertThat(store.advance()).isTrue();
        var renewal = store.claimCharge();
        store.complete(
                renewal,
                new BillingReply(Status.DECLINED, null, null, null, "REJECT_CARD_PAYMENT"));
        assertThat(api.get(account).state()).isEqualTo("GRACE");
        clock.set(first.periodEnd().plus(Duration.ofDays(1)));
        store.advance();
        var retry = store.claimCharge();
        assertThat(retry.workId()).isNotEqualTo(renewal.workId());
        assertThat(retry.charge().amount()).isEqualTo(4900);
        approve(retry, clock.instant());
        assertThat(api.get(account).periodEnd()).isEqualTo(Instant.parse("2030-03-31T03:00:00Z"));
    }

    @Test
    void expiredLeaseRejectsStaleResultAndUsesSameOrderForRecovery() {
        var first = registered();
        clock.set(start.plusSeconds(120));
        var recovery = store.claimCharge();
        assertThat(recovery.workId()).isEqualTo(first.workId());
        assertThat(recovery.action()).isEqualTo("LOOKUP");
        assertThat(recovery.charge()).isEqualTo(first.charge());
        approve(first, start);
        assertThat(api.get(account).state()).isEqualTo("PENDING");
        approve(recovery, start);
        assertThat(api.get(account).state()).isEqualTo("ACTIVE");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment.outbox_events where"
                                        + " topic='stockdrop.membership.events.private.v1'",
                                Long.class))
                .isEqualTo(1);
    }

    @Test
    void cancellationAfterExecutionStartKeepsApprovedPeriodAndDeletesProviderKeyAtExpiry() {
        var work = registered();
        cancel();
        assertThat(store.claimDelete()).isNull();
        approve(work, start);
        assertThat(api.get(account).state()).isEqualTo("CANCEL_SCHEDULED");
        assertThat(api.get(account).autoRenew()).isFalse();
        // 해지 예약 기간에는 철회할 수 있도록 공급자 키를 남깁니다.
        assertThat(store.claimDelete()).isNull();
        clock.set(api.get(account).entitlementUntil());
        store.advance();
        assertThat(api.get(account).state()).isEqualTo("EXPIRED");
        var delete = store.claimDelete();
        assertThat(delete.billingKey()).isEqualTo("synthetic-billing");
        store.complete(delete, BillingReply.unknown("BILLING_DELETE_UNKNOWN"));
        assertThat(credentials.findById(delete.subscriptionId()).orElseThrow().billingKeyCipher)
                .isNotNull();
        clock.set(clock.instant().plusSeconds(2));
        delete = store.claimDelete();
        store.complete(delete, new BillingReply(Status.DELETED, null, null, null, null));
        assertThat(credentials.findById(delete.subscriptionId()).orElseThrow().billingKeyCipher)
                .isNull();
    }

    @Test
    void cancellationWithdrawalBeforeExpiryRestoresRenewalWithSameKey() {
        approve(registered(), start);
        var periodEnd = api.get(account).periodEnd();
        cancel();
        assertThat(api.get(account).state()).isEqualTo("CANCEL_SCHEDULED");
        assertThat(store.claimDelete()).isNull();
        command("MEMBERSHIP_CANCEL_WITHDRAW_REQUESTED", 2);
        var restored = api.get(account);
        assertThat(restored.state()).isEqualTo("ACTIVE");
        assertThat(restored.autoRenew()).isTrue();
        assertThat(restored.nextChargeAt()).isEqualTo(periodEnd);
        assertThat(restored.entitlementUntil()).isEqualTo(periodEnd);
        assertThat(resultTypes()).endsWith("MEMBERSHIP_CANCEL_WITHDRAWN");
        assertThat(store.claimDelete()).isNull();
        clock.set(periodEnd);
        store.advance();
        var renewal = store.claimCharge();
        assertThat(renewal.billingKey()).isEqualTo("synthetic-billing");
        assertThat(renewal.charge().amount()).isEqualTo(4900);
    }

    @Test
    void cancellationWithdrawalAfterExpiryIsRejectedWithoutStateChange() {
        approve(registered(), start);
        cancel();
        clock.set(api.get(account).entitlementUntil());
        store.advance();
        command("MEMBERSHIP_CANCEL_WITHDRAW_REQUESTED", 2);
        assertThat(api.get(account).state()).isEqualTo("EXPIRED");
        assertThat(api.get(account).autoRenew()).isFalse();
        assertThat(resultTypes()).endsWith("MEMBERSHIP_CANCEL_WITHDRAW_REJECTED");
        assertThat(store.claimDelete()).isNotNull();
    }

    @Test
    void lateEarlierCancellationDoesNotOverrideLaterWithdrawal() {
        approve(registered(), start);
        command("MEMBERSHIP_CANCEL_WITHDRAW_REQUESTED", 2);
        assertThat(resultTypes()).endsWith("MEMBERSHIP_CANCEL_WITHDRAW_REJECTED");
        cancel();
        assertThat(api.get(account).state()).isEqualTo("ACTIVE");
        assertThat(api.get(account).autoRenew()).isTrue();
    }

    @Test
    void graceCardReplacementChargesSameCycleWithNewKeyAndDeletesRetiredKey() {
        var declined = declinedRenewal();
        var session =
                api.create(
                        account,
                        UUID.randomUUID(),
                        new MembershipAuthRequest("monthly-v1", "recurring-v1", true));
        api.complete(
                account,
                session.sessionId(),
                UUID.randomUUID(),
                new MembershipCompletionRequest("synthetic-auth-2", session.customerKey()));
        assertThat(api.get(account).state()).isEqualTo("GRACE");
        var issue = store.claimIssue();
        assertThat(issue.authKey()).isEqualTo("synthetic-auth-2");
        store.complete(
                issue, new BillingReply(Status.ISSUED, "replacement-billing", null, null, null));
        var charge = store.claimCharge();
        assertThat(charge.workId()).isNotEqualTo(declined.workId());
        assertThat(charge.billingKey()).isEqualTo("replacement-billing");
        assertThat(charge.customerKey()).isEqualTo(session.customerKey());
        assertThat(attempts.findById(charge.workId()).orElseThrow().attemptNumber).isEqualTo(1);
        approve(charge, clock.instant());
        var renewed = api.get(account);
        assertThat(renewed.state()).isEqualTo("ACTIVE");
        assertThat(renewed.periodEnd()).isEqualTo(Instant.parse("2030-03-31T03:00:00Z"));
        var retired = store.claimDelete();
        assertThat(retired.action()).isEqualTo("DELETE_RETIRED");
        assertThat(retired.billingKey()).isEqualTo("synthetic-billing");
        store.complete(retired, new BillingReply(Status.DELETED, null, null, null, null));
        var credential = credentials.findById(retired.subscriptionId()).orElseThrow();
        assertThat(credential.retiredBillingKeyCipher).isNull();
        assertThat(credential.billingKeyCipher).isNotNull();
        assertThat(credential.deleteState).isEqualTo("ACTIVE");
        assertThat(store.claimDelete()).isNull();
    }

    @Test
    void graceCardReplacementIsBlockedWhileRetryIsInFlightAndPausesOldKeyRetry() {
        var declined = declinedRenewal();
        var due = clock.instant();
        var request = new MembershipAuthRequest("monthly-v1", "recurring-v1", true);
        var session = api.create(account, UUID.randomUUID(), request);
        // 카드 교체 세션 중에는 이전 키로 다음 재시도를 만들지 않습니다.
        clock.set(due.plus(Duration.ofDays(1)));
        store.advance();
        assertThat(store.claimCharge()).isNull();
        assertThat(api.get(account).nextChargeAt()).isEqualTo(clock.instant().plusSeconds(60));
        // 세션이 만료되면 기존 일정의 재시도를 이전 키로 진행합니다.
        assertThat(store.claimIssue()).isNull();
        assertThat(sessions.findById(session.sessionId()).orElseThrow().state)
                .isEqualTo("EXPIRED");
        assertThat(api.get(account).state()).isEqualTo("GRACE");
        clock.set(clock.instant().plusSeconds(60));
        store.advance();
        var retry = store.claimCharge();
        assertThat(retry.workId()).isNotEqualTo(declined.workId());
        assertThat(retry.billingKey()).isEqualTo("synthetic-billing");
        // 재시도 결과가 나기 전에는 새 카드 등록으로 중복 결제를 만들 수 없습니다.
        assertThatThrownBy(() -> api.create(account, UUID.randomUUID(), request))
                .isInstanceOf(MembershipException.class);
    }

    @Test
    void graceCardReplacementIsRejectedAfterAllFourChargesAreUsed() {
        approve(registered(), start);
        var due = api.get(account).periodEnd();
        clock.set(due);
        store.advance();
        for (int day = 0; day < 4; day++) {
            store.complete(
                    store.claimCharge(),
                    new BillingReply(Status.DECLINED, null, null, null, "REJECT_CARD_PAYMENT"));
            if (day < 3) {
                clock.set(due.plus(Duration.ofDays(day + 1)));
                store.advance();
            }
        }
        assertThat(api.get(account).state()).isEqualTo("GRACE");
        assertThatThrownBy(
                        () ->
                                api.create(
                                        account,
                                        UUID.randomUUID(),
                                        new MembershipAuthRequest(
                                                "monthly-v1", "recurring-v1", true)))
                .isInstanceOf(MembershipException.class);
    }

    @Test
    void twoWorkersClaimOneRenewalOnce() throws Exception {
        approve(registered(), start);
        clock.set(api.get(account).periodEnd());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Void> advance =
                    () -> {
                        gate.await();
                        store.advance();
                        return null;
                    };
            var a = pool.submit(advance);
            var b = pool.submit(advance);
            gate.countDown();
            a.get();
            b.get();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from payment.membership_billing_cycles",
                                    Long.class))
                    .isEqualTo(2);
            var x = pool.submit(() -> store.claimCharge());
            var y = pool.submit(() -> store.claimCharge());
            var claimed = Arrays.asList(x.get(), y.get());
            assertThat(claimed.stream().filter(Objects::nonNull).count()).isEqualTo(1);
        }
    }

    @Test
    void unresolvedFifteenDayAttemptBlocksNewSubscriptionAndApprovalReplay() {
        var work = registered();
        clock.set(start.plus(Duration.ofDays(15)));
        assertThat(store.claimCharge()).isNull();
        assertThat(attempts.findById(work.workId()).orElseThrow().status)
                .isEqualTo("MANUAL_REVIEW");
        cancel();
        assertThatThrownBy(
                        () ->
                                api.create(
                                        account,
                                        UUID.randomUUID(),
                                        new MembershipAuthRequest(
                                                "monthly-v1", "recurring-v1", true)))
                .isInstanceOf(MembershipException.class);
        assertThat(store.claimDelete()).isNull();
    }

    @Test
    void sessionOwnerPayloadAndExpiryAreBoundToCompletion() {
        UUID key = UUID.randomUUID();
        var request = new MembershipAuthRequest("monthly-v1", "recurring-v1", true);
        var session = api.create(account, key, request);
        assertThat(api.create(account, key, request).customerKey())
                .isEqualTo(session.customerKey());
        assertThatThrownBy(
                        () ->
                                api.complete(
                                        UUID.randomUUID(),
                                        session.sessionId(),
                                        UUID.randomUUID(),
                                        new MembershipCompletionRequest(
                                                "synthetic-auth", session.customerKey())))
                .isInstanceOf(MembershipException.class);
        clock.set(start.plusSeconds(600));
        assertThatThrownBy(
                        () ->
                                api.complete(
                                        account,
                                        session.sessionId(),
                                        UUID.randomUUID(),
                                        new MembershipCompletionRequest(
                                                "synthetic-auth", session.customerKey())))
                .isInstanceOf(MembershipException.class);
        assertThat(store.claimIssue()).isNull();
        assertThat(api.create(account, UUID.randomUUID(), request).sessionId())
                .isNotEqualTo(session.sessionId());
    }

    @Test
    void graceCancellationEndsBenefitAndFourthDeclineDoesNotCreateFifthCharge() {
        approve(registered(), start);
        var due = api.get(account).periodEnd();
        clock.set(due);
        store.advance();
        for (int day = 0; day < 4; day++) {
            var work = store.claimCharge();
            assertThat(work).isNotNull();
            store.complete(
                    work,
                    new BillingReply(Status.DECLINED, null, null, null, "REJECT_CARD_PAYMENT"));
            if (day < 3) {
                clock.set(due.plus(Duration.ofDays(day + 1)));
                store.advance();
            }
        }
        clock.set(due.plus(Duration.ofDays(3)).plusSeconds(120));
        store.advance();
        assertThat(api.get(account).state()).isEqualTo("EXPIRED");
        assertThat(store.claimCharge()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment.membership_billing_attempts",
                                Long.class))
                .isEqualTo(5);
    }

    @Test
    void serviceAndAdminCannotUseBuyerCardAuthentication() throws Exception {
        var authority =
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_BUYER");
        for (String principal : List.of("service", "user")) {
            var token =
                    org.springframework.security.test.web.servlet.request
                            .SecurityMockMvcRequestPostProcessors.jwt()
                            .jwt(
                                    j ->
                                            j.subject(account.toString())
                                                    .claim("principal_type", principal))
                            .authorities(
                                    principal.equals("user")
                                            ? List.of(
                                                    authority,
                                                    new org.springframework.security.core.authority
                                                            .SimpleGrantedAuthority("ROLE_ADMIN"))
                                            : List.of(authority));
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            "/api/v1/payments/memberships/configuration")
                                    .with(token))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isForbidden());
        }
    }

    @Test
    void userConfigurationReturnsOnlyPublicSettingsWithNoStore() throws Exception {
        var token =
                org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(j -> j.subject(account.toString()).claim("principal_type", "user"))
                        .authorities(
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("ROLE_BUYER"));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/v1/payments/memberships/configuration")
                                .with(token))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                                .string("Cache-Control", "no-store"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.data.amount")
                                .value(4900))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.data.secretKey")
                                .doesNotExist());
    }

    @Test
    void withdrawalWaitsForStartedPaymentAndThenEndsPaidBenefit() {
        var work = registered();
        UUID operation = UUID.randomUUID();
        assertThat(withdrawals.prepare(account, operation)).isEqualTo(1);
        assertThat(withdrawals.finalizeWithdrawal(account, operation)).isEqualTo(1);
        approve(work, start);
        assertThat(withdrawals.finalizeWithdrawal(account, operation)).isZero();
        assertThat(api.get(account).state()).isEqualTo("EXPIRED");
        assertThat(api.get(account).entitlementUntil()).isEqualTo(clock.instant());
        assertThat(api.get(account).autoRenew()).isFalse();
        assertThatThrownBy(
                        () ->
                                api.create(
                                        account,
                                        UUID.randomUUID(),
                                        new MembershipAuthRequest(
                                                "monthly-v1", "recurring-v1", true)))
                .isInstanceOf(MembershipException.class);
        assertThat(store.claimDelete()).isNotNull();
    }

    @Test
    void withdrawalReleaseTombstoneRejectsDelayedPrepare() {
        UUID operation = UUID.randomUUID();
        withdrawals.release(account, operation);
        assertThatThrownBy(() -> withdrawals.prepare(account, operation))
                .isInstanceOf(MembershipException.class);
        assertThat(
                        api.create(
                                account,
                                UUID.randomUUID(),
                                new MembershipAuthRequest("monthly-v1", "recurring-v1", true)))
                .isNotNull();
    }
}
