package io.cartogra.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.signup")
public record SignupProperties(@DefaultValue("true") boolean enabled) {}
