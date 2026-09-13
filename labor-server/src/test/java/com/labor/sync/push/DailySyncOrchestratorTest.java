package com.labor.sync.push;

import com.labor.sync.workspace.ProjectSyncSetting;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DailySyncOrchestratorTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void latestSlotUsesMidnightBeforeNoonAndNoonAfterNoon() {
        assertThat(DailySyncOrchestrator.latestDueSlot(at("2026-07-31T11:59:59+08:00")))
                .isEqualTo(at("2026-07-31T00:00:00+08:00"));
        assertThat(DailySyncOrchestrator.latestDueSlot(at("2026-07-31T12:00:00+08:00")))
                .isEqualTo(at("2026-07-31T12:00:00+08:00"));
    }

    @Test
    void halfDayCanBeFinalizedTwentyMinutesBeforePlatformUpdate() {
        assertThat(DailySyncOrchestrator.latestFinalizableSlot(at("2026-07-31T11:39:59+08:00")))
                .isEqualTo(at("2026-07-31T00:00:00+08:00"));
        assertThat(DailySyncOrchestrator.latestFinalizableSlot(at("2026-07-31T11:40:00+08:00")))
                .isEqualTo(at("2026-07-31T12:00:00+08:00"));
        assertThat(DailySyncOrchestrator.latestFinalizableSlot(at("2026-07-31T23:39:59+08:00")))
                .isEqualTo(at("2026-07-31T12:00:00+08:00"));
        assertThat(DailySyncOrchestrator.latestFinalizableSlot(at("2026-07-31T23:40:00+08:00")))
                .isEqualTo(at("2026-08-01T00:00:00+08:00"));
    }

    @Test
    void missingSlotsAreReturnedOldestFirstAndBootstrapStartsFromLatestSlot() {
        ProjectSyncSetting setting = new ProjectSyncSetting();
        ZonedDateTime now = at("2026-07-31T13:40:00+08:00");

        assertThat(DailySyncOrchestrator.nextDueSlot(setting, now))
                .isEqualTo(at("2026-07-31T12:00:00+08:00"));

        setting.setLastPushSlotAt(Instant.parse("2026-07-30T04:00:00Z"));
        assertThat(DailySyncOrchestrator.nextDueSlot(setting, now))
                .isEqualTo(at("2026-07-31T00:00:00+08:00"));

        setting.setLastPushSlotAt(Instant.parse("2026-07-30T16:00:00Z"));
        assertThat(DailySyncOrchestrator.nextDueSlot(setting, now))
                .isEqualTo(at("2026-07-31T12:00:00+08:00"));

        setting.setLastPushSlotAt(Instant.parse("2026-07-31T04:00:00Z"));
        assertThat(DailySyncOrchestrator.nextDueSlot(setting, now)).isNull();
    }

    private ZonedDateTime at(String value) {
        return ZonedDateTime.parse(value).withZoneSameInstant(ZONE);
    }
}
