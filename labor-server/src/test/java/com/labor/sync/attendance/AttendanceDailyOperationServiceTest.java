package com.labor.sync.attendance;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AttendanceDailyOperationServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void currentDayCanCompleteMorningBeforePushCutoff() {
        var morningWindow = AttendanceDailyOperationService.dayWindows(
                LocalDate.of(2026, 7, 31), ZONE,
                ZonedDateTime.parse("2026-07-31T09:00:00+08:00"));
        assertThat(morningWindow.get(0).closed()).isTrue();
        assertThat(morningWindow.get(0).beforePushCutoff()).isTrue();
        assertThat(morningWindow.get(1).closed()).isFalse();

        var atCutoff = AttendanceDailyOperationService.dayWindows(
                LocalDate.of(2026, 7, 31), ZONE,
                ZonedDateTime.parse("2026-07-31T11:40:00+08:00"));
        assertThat(atCutoff.get(0).closed()).isTrue();
        assertThat(atCutoff.get(0).beforePushCutoff()).isFalse();
        assertThat(atCutoff.get(1).closed()).isFalse();
    }

    @Test
    void currentDayCanCompleteAfternoonFromPushCutoffBeforeMidnight() {
        var windows = AttendanceDailyOperationService.dayWindows(
                LocalDate.of(2026, 7, 31), ZONE,
                ZonedDateTime.parse("2026-07-31T20:00:00+08:00"));

        assertThat(windows).hasSize(2);
        assertThat(windows.get(0).closed()).isTrue();
        assertThat(windows.get(1).closed()).isTrue();
        assertThat(windows.get(1).beforePushCutoff()).isTrue();
    }

    @Test
    void historicalDayClosesBothWindowsAndFutureDayClosesNone() {
        ZonedDateTime now = ZonedDateTime.parse("2026-07-31T15:00:00+08:00");

        assertThat(AttendanceDailyOperationService.dayWindows(
                LocalDate.of(2026, 7, 30), ZONE, now)).allMatch(
                AttendanceDailyOperationService.DayWindow::closed);
        assertThat(AttendanceDailyOperationService.dayWindows(
                LocalDate.of(2026, 8, 1), ZONE, now)).noneMatch(
                AttendanceDailyOperationService.DayWindow::closed);
    }
}
