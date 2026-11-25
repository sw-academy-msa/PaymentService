package com.msa.payment.domain.analytics.service.impl;

import com.msa.payment.domain.analytics.client.GaClient;
import com.msa.payment.domain.analytics.service.*;

import org.springframework.stereotype.Service;

/** 분석 실패를 결제 처리와 분리하고 HTTP 전후의 짧은 TX만 사용합니다. */
@Service
public class GaProcessorImpl implements GaProcessor {
    private final GaDispatchStore store;
    private final GaClient client;

    public GaProcessorImpl(GaDispatchStore store, GaClient client) {
        this.store = store;
        this.client = client;
    }

    public boolean processNext() {
        var work = store.claim();
        if (work == null) return false;
        String status;
        try {
            status = client.send(work);
        } catch (RuntimeException failure) {
            status = "RETRY";
        }
        store.complete(work, status);
        return true;
    }
}
