package com.checkout.payment.gateway.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Connection settings for the acquiring bank.
 *
 * <p>Both timeouts are required. Without them the underlying client waits indefinitely, so a bank
 * that accepts connections but never answers would hold gateway request threads open until the
 * container runs out of them — an outage at the bank becomes an outage here.
 */
@Validated
@ConfigurationProperties(prefix = "payments.bank")
public record BankProperties(
    @NotBlank String apiUrl,
    @NotNull Duration connectTimeout,
    @NotNull Duration readTimeout) {

}
