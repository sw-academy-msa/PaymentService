package com.msa.payment.domain.advertising.service.impl;

import com.msa.payment.domain.advertising.service.*;
import com.msa.payment.domain.payment.client.TossClient;

import org.springframework.stereotype.Service;

/** 공급자 호출 중 DB 트랜잭션과 행 잠금을 유지하지 않습니다. */
@Service
public class AdvertisingProcessorImpl implements AdvertisingProcessor {
    private final AdvertisingWorkStore store;
    private final TossClient toss;

    public AdvertisingProcessorImpl(AdvertisingWorkStore store, TossClient toss) {
        this.store = store;
        this.toss = toss;
    }

    public boolean processNext() {
        var work = store.claim();
        if (work == null) return false;
        var reply = toss.reconcile(work.request());
        store.complete(work, reply);
        return true;
    }
}
