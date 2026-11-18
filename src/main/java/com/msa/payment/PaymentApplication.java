package com.msa.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 결제 원본·Kafka 명령·결과 Outbox를 소유합니다. 주문 Saga와 재고 변경은 다른 서비스의 책임입니다. */
@SpringBootApplication
public class PaymentApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
