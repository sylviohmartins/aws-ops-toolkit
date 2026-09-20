package io.github.awsopstoolkit.integration;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;

public interface PaymentLookup {
    @GetExchange("/payments/{reference}")
    PaymentState get(
            @PathVariable String reference, @RequestHeader("X-Correlation-ID") String operationId);

    record PaymentState(String reference, String status, long version) {}
}
