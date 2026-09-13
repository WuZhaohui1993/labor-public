package com.labor.sync.security;

import com.labor.sync.common.CryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CaptchaService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 4;
    private static final Duration TTL = Duration.ofMinutes(5);
    private final CaptchaChallengeRepository repository;
    private final CryptoService cryptoService;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public CaptchaView issue(String clientIp) {
        return issueInternal(clientIp, null);
    }

    /** Test-only helper; production callers use {@link #issue(String)}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CaptchaView issueForTest(String clientIp, String fixedCode) {
        return issueInternal(clientIp, fixedCode);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireForTest(String captchaId) {
        CaptchaChallenge challenge = repository.findById(captchaId).orElseThrow();
        challenge.setExpiresAt(Instant.now().minusSeconds(1));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ValidationResult consume(String captchaId, String submittedCode, String clientIp) {
        if (captchaId == null || captchaId.isBlank() || submittedCode == null || submittedCode.isBlank()) {
            return ValidationResult.REQUIRED;
        }
        CaptchaChallenge challenge = repository.findByCaptchaIdForUpdate(captchaId.trim()).orElse(null);
        Instant now = Instant.now();
        if (challenge == null || challenge.getConsumedAt() != null || !challenge.getExpiresAt().isAfter(now)) {
            return ValidationResult.EXPIRED;
        }
        challenge.setConsumedAt(now);
        boolean validIp = challenge.getClientIp() == null || challenge.getClientIp().equals(clientIp);
        boolean validCode = cryptoService.hmac(submittedCode).equals(challenge.getAnswerHmac());
        if (!validIp || !validCode) {
            return ValidationResult.INVALID;
        }
        return ValidationResult.VALID;
    }

    @Scheduled(fixedDelay = 600_000L)
    @Transactional
    public void cleanup() {
        Instant now = Instant.now();
        repository.deleteExpiredOrConsumed(now, now.minus(Duration.ofMinutes(10)));
    }

    private CaptchaView issueInternal(String clientIp, String fixedCode) {
        String code = fixedCode == null ? randomCode() : fixedCode.trim().toUpperCase();
        if (code.length() != CODE_LENGTH) throw new IllegalArgumentException("验证码长度必须为4位");
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = Instant.now().plus(TTL);
        CaptchaChallenge challenge = new CaptchaChallenge();
        challenge.setCaptchaId(captchaId);
        challenge.setAnswerHmac(cryptoService.hmac(code));
        challenge.setClientIp(clientIp);
        challenge.setExpiresAt(expiresAt);
        repository.save(challenge);
        return new CaptchaView(captchaId, renderPng(code), expiresAt);
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private String renderPng(String code) {
        int width = 144;
        int height = 48;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(new Color(238, 247, 247));
            graphics.fillRect(0, 0, width, height);

            graphics.setStroke(new BasicStroke(1.2f));
            for (int i = 0; i < 10; i++) {
                graphics.setColor(randomNoiseColor());
                graphics.drawLine(random.nextInt(width), random.nextInt(height),
                        random.nextInt(width), random.nextInt(height));
            }
            for (int i = 0; i < 70; i++) {
                graphics.setColor(randomNoiseColor());
                graphics.fillOval(random.nextInt(width), random.nextInt(height), 2, 2);
            }

            Font font = new Font(Font.SANS_SERIF, Font.BOLD, 27);
            graphics.setFont(font);
            for (int i = 0; i < code.length(); i++) {
                int x = 13 + i * 32;
                int y = 34 + random.nextInt(5) - 2;
                double angle = Math.toRadians(random.nextInt(25) - 12);
                graphics.rotate(angle, x + 10, y - 10);
                graphics.setColor(new Color(23 + random.nextInt(25), 65 + random.nextInt(25), 70 + random.nextInt(25)));
                graphics.drawString(String.valueOf(code.charAt(i)), x, y);
                graphics.rotate(-angle, x + 10, y - 10);
            }
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG验证码编码器不可用");
            }
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("生成验证码图片失败", ex);
        }
    }

    private Color randomNoiseColor() {
        return new Color(70 + random.nextInt(130), 90 + random.nextInt(120), 100 + random.nextInt(110));
    }

    public record CaptchaView(String captchaId, String image, Instant expiresAt) {}

    public enum ValidationResult { REQUIRED, EXPIRED, INVALID, VALID }
}
