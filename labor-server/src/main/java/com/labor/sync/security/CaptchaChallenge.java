package com.labor.sync.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "auth_captcha_challenge")
public class CaptchaChallenge {
    @Id
    @Column(name = "captcha_id", length = 64)
    private String captchaId;

    @Column(name = "answer_hmac", nullable = false, length = 64)
    private String answerHmac;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
