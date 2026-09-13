package com.labor.sync.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface AttendanceCompletionRunRepository extends JpaRepository<AttendanceCompletionRun, Long> {
    Optional<AttendanceCompletionRun> findByProCodeAndWindowStartAtAndWindowEndAt(
            String proCode, Instant windowStartAt, Instant windowEndAt);
}
