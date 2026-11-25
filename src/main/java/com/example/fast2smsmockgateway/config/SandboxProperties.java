package com.example.fast2smsmockgateway.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Validated
@ConfigurationProperties("sandbox")
public record SandboxProperties(
        @NotBlank String apiKeys,
        @NotBlank @Email String emailFrom,
        @NotBlank @Email String emailTo,
        @NotBlank String mailPassword,
        @PositiveOrZero BigDecimal walletBalance, String dltExcelOrigin,
        @Min(1) @Max(100) int otpVerifyAttempts,
        @Min(0) @Max(3600) int otpResendWaitSeconds) {
}
