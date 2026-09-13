package com.labor.sync.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;

public interface CaptchaChallengeRepository extends JpaRepository<CaptchaChallenge, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select challenge from CaptchaChallenge challenge where challenge.captchaId = :captchaId")
    Optional<CaptchaChallenge> findByCaptchaIdForUpdate(@Param("captchaId") String captchaId);

    @Modifying
    @Query("delete from CaptchaChallenge challenge where challenge.expiresAt < :now or challenge.consumedAt < :consumedBefore")
    int deleteExpiredOrConsumed(@Param("now") Instant now, @Param("consumedBefore") Instant consumedBefore);
}
