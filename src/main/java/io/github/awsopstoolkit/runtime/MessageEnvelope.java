package io.github.awsopstoolkit.runtime;

import java.util.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class MessageEnvelope {
    private MessageEnvelope() {}

    static String encode(Message message, ObjectMapper json) {
        return encode(message, json, SqliteJournal.now(), 120);
    }

    static String encode(
            Message message, ObjectMapper json, long receivedAt, int visibilitySeconds) {
        validateBodyMd5(message);
        Map<String, Object> attributes = new TreeMap<>();
        message.messageAttributes()
                .forEach(
                        (key, value) -> {
                            Map<String, String> data = new TreeMap<>();
                            data.put("type", value.dataType());
                            if (value.stringValue() != null)
                                data.put("string", value.stringValue());
                            if (value.binaryValue() != null)
                                data.put(
                                        "binary",
                                        Base64.getEncoder()
                                                .encodeToString(value.binaryValue().asByteArray()));
                            attributes.put(key, data);
                        });
        return json.writeValueAsString(
                Map.of(
                        "id",
                        message.messageId(),
                        "receipt",
                        message.receiptHandle(),
                        "body",
                        message.body(),
                        "attributes",
                        attributes,
                        "receiveCount",
                        receiveCount(message),
                        "receivedAt",
                        receivedAt,
                        "leaseUntil",
                        receivedAt + visibilitySeconds));
    }

    private static int receiveCount(Message message) {
        String value =
                message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        if (value == null || value.isBlank()) return 1;
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid SQS receive count", invalid);
        }
    }

    private static void validateBodyMd5(Message message) {
        String expected = message.md5OfBody();
        if (expected == null || expected.isBlank()) return;
        try {
            var digest = java.security.MessageDigest.getInstance("MD5");
            String actual =
                    HexFormat.of()
                            .formatHex(
                                    digest.digest(
                                            Objects.toString(message.body(), "")
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)));
            if (!expected.equalsIgnoreCase(actual))
                throw new IllegalArgumentException("SQS body MD5 mismatch");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("MD5 unavailable", impossible);
        }
    }

    static Map<String, MessageAttributeValue> attributes(JsonNode node) {
        Map<String, MessageAttributeValue> result = new TreeMap<>();
        node.path("attributes")
                .properties()
                .forEach(
                        entry -> {
                            var data = entry.getValue();
                            var value =
                                    MessageAttributeValue.builder()
                                            .dataType(data.path("type").asText());
                            if (data.has("string")) value.stringValue(data.path("string").asText());
                            if (data.has("binary"))
                                value.binaryValue(
                                        SdkBytes.fromByteArray(
                                                Base64.getDecoder()
                                                        .decode(data.path("binary").asText())));
                            result.put(entry.getKey(), value.build());
                        });
        return Map.copyOf(result);
    }
}
