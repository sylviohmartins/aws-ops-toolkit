package io.github.awsopstoolkit.development;

import static org.junit.jupiter.api.Assertions.*;

import io.github.awsopstoolkit.configuration.DynamoProperties;
import io.github.awsopstoolkit.dynamodb.DynamoTableDescriptor;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

class ExtensionModelExampleTest {
    @Test
    void typedTableAndModelsStaySeparatedByReasonToChange() {
        var properties = new DynamoProperties(100, Map.of("payments", "dev-payments"));
        var descriptor =
                new DynamoTableDescriptor<>(
                        "payments",
                        TableSchema.fromBean(PaymentDynamoItem.class),
                        "paymentId",
                        Set.of("status-index"));

        assertEquals("dev-payments", properties.requireTable(descriptor.logicalName()));

        var item = new PaymentDynamoItem();
        item.setPaymentId("p-123");
        item.setStatus("PENDING");
        item.setAmount("10.50");

        Payment domain = PaymentMapper.toDomain(item);
        PaymentReportRow report = PaymentMapper.toReport(domain);

        assertEquals(new PaymentId("p-123"), domain.id());
        assertEquals("PENDING", report.status());
        assertEquals(new BigDecimal("10.50"), report.amount());
    }

    record PaymentId(String value) {
        PaymentId {
            if (value == null || value.isBlank())
                throw new IllegalArgumentException("payment id cannot be blank");
        }
    }

    record Payment(PaymentId id, BigDecimal amount, String status) {}

    record PaymentCorrectionRequest(PaymentId id, String targetStatus) {}

    record PaymentCorrectionResponse(PaymentId id, String outcome) {}

    record CustomerApiResponse(String customerReference, boolean active) {}

    record PaymentReportRow(String paymentId, BigDecimal amount, String status) {}

    static final class PaymentMapper {
        private PaymentMapper() {}

        static Payment toDomain(PaymentDynamoItem item) {
            return new Payment(
                    new PaymentId(item.getPaymentId()),
                    new BigDecimal(item.getAmount()),
                    item.getStatus());
        }

        static PaymentReportRow toReport(Payment payment) {
            return new PaymentReportRow(payment.id().value(), payment.amount(), payment.status());
        }
    }

    @DynamoDbBean
    public static class PaymentDynamoItem {
        private String paymentId;
        private String amount;
        private String status;

        @DynamoDbPartitionKey
        public String getPaymentId() {
            return paymentId;
        }

        public void setPaymentId(String paymentId) {
            this.paymentId = paymentId;
        }

        public String getAmount() {
            return amount;
        }

        public void setAmount(String amount) {
            this.amount = amount;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
