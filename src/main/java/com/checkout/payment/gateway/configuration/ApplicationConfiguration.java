package com.checkout.payment.gateway.configuration;

import com.checkout.payment.gateway.properties.BankProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ApplicationConfiguration {

  /**
   * The client used to reach the acquiring bank.
   *
   * <p>Built here rather than inside the processor so that the timeouts are applied in one place
   * and so tests can supply their own {@link RestClient}.
   */
  @Bean
  public RestClient bankRestClient(RestClient.Builder builder, BankProperties bankProperties) {
    return builder
        .baseUrl(bankProperties.apiUrl())
        .requestFactory(ClientHttpRequestFactories.get(
            ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(bankProperties.connectTimeout())
                .withReadTimeout(bankProperties.readTimeout())))
        .build();
  }
}
