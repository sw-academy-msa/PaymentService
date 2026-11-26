package com.msa.payment.domain.advertising.service;

/** 전용 내부 명령의 영수증과 결제 생성 및 취소 의도를 원자화합니다. */
public interface AdvertisingCommandService {
    void receive(String key, byte[] bytes);
}
