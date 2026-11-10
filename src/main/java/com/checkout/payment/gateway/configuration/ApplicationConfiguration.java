package com.checkout.payment.gateway.configuration;

import com.checkout.payment.gateway.properties.BankProperties;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class ApplicationConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(ApplicationConfiguration.class);

  @Bean
  public WebClient bankWebClient(WebClient.Builder builder, BankProperties bankProperties) {
    LOG.info("Calling the acquiring bank at {} (connect timeout {}, read timeout {})",
        bankProperties.apiUrl(), bankProperties.connectTimeout(), bankProperties.readTimeout());
    HttpClient httpClient = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
            Math.toIntExact(bankProperties.connectTimeout().toMillis()))
        .responseTimeout(bankProperties.readTimeout());
    return builder
        .baseUrl(bankProperties.apiUrl())
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
  }
}
