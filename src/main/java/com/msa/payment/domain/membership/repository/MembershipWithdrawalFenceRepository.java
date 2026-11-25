package com.msa.payment.domain.membership.repository;

import com.msa.payment.domain.membership.entity.MembershipWithdrawalFence;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

/** 계정별 새 가입과 실행 시작이 영속 탈퇴 차단을 확인하도록 합니다. */
public interface MembershipWithdrawalFenceRepository
        extends JpaRepository<MembershipWithdrawalFence, UUID> {
    boolean existsByAccountIdAndCanceledFalse(UUID account);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from MembershipWithdrawalFence f where f.id=:id")
    Optional<MembershipWithdrawalFence> lock(@Param("id") UUID id);
}
