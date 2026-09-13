package com.labor.sync.workspace;

import com.labor.sync.audit.AuditService;
import com.labor.sync.common.ApiResponse;
import com.labor.sync.common.BusinessException;
import com.labor.sync.security.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/labor/admin/workspace-settings")
public class ProjectSyncSettingController {
    private static final String FIXED_PUSH_TIME = "00:00";
    private final ProjectSyncSettingRepository repository;
    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("hasAuthority('integration:view')")
    @Transactional(readOnly = true)
    public ApiResponse<SettingView> get() {
        return ApiResponse.ok(SettingView.from(find()));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('integration:edit')")
    @Transactional
    public ApiResponse<SettingView> update(@Valid @RequestBody SettingWrite request) {
        validateZone(request.zoneId());
        validateTime(request.pushTime());
        ProjectSyncSetting setting = find();
        setting.setHikCollectionEnabled(request.hikCollectionEnabled());
        setting.setPushEnabled(request.pushEnabled());
        setting.setPushTime(FIXED_PUSH_TIME);
        setting.setZoneId(request.zoneId().trim());
        setting.setAttendanceCompletionEnabled(request.attendanceCompletionEnabled());
        setting.setAttendanceCompletenessRate(request.attendanceCompletenessRate());
        auditService.record("UPDATE", "PROJECT_SYNC_SETTING", setting.getId(),
                "自动推送时段 每小时40分；11:40、23:40半日考勤补全"
                        + (request.attendanceCompletionEnabled()
                        ? "已开启，半日完整率基准 " + request.attendanceCompletenessRate()
                        + "%（自动上下浮动1个百分点）" : "已关闭"));
        return ApiResponse.ok(SettingView.from(setting));
    }

    private ProjectSyncSetting find() {
        return repository.findByProjectId(WorkspaceContext.projectId())
                .orElseThrow(() -> new BusinessException("WORKSPACE_SETTING_NOT_FOUND", "项目同步设置不存在"));
    }

    private void validateZone(String zoneId) {
        try {
            ZoneId.of(zoneId.trim());
        } catch (DateTimeException exception) {
            throw new BusinessException("ZONE_ID_INVALID", "时区配置无效");
        }
    }

    private void validateTime(String pushTime) {
        try {
            LocalTime.parse(pushTime);
        } catch (DateTimeException exception) {
            throw new BusinessException("PUSH_TIME_INVALID", "每日推送时间格式无效");
        }
    }

    public record SettingWrite(boolean hikCollectionEnabled, boolean pushEnabled,
                               boolean attendanceCompletionEnabled,
                               @jakarta.validation.constraints.NotNull
                               @jakarta.validation.constraints.DecimalMin("0.00")
                               @jakarta.validation.constraints.DecimalMax("100.00")
                               BigDecimal attendanceCompletenessRate,
                               @NotBlank @Pattern(regexp = "(?:[01]\\d|2[0-3]):[0-5]\\d") String pushTime,
                               @NotBlank String zoneId) {
    }

    public record SettingView(Long id, boolean hikCollectionEnabled, boolean pushEnabled, boolean syncStarted,
                              String pushTime, String zoneId, boolean attendanceCompletionEnabled,
                              BigDecimal attendanceCompletenessRate) {
        static SettingView from(ProjectSyncSetting setting) {
            return new SettingView(setting.getId(), setting.isHikCollectionEnabled(), setting.isPushEnabled(),
                    setting.isSyncStarted(), FIXED_PUSH_TIME, setting.getZoneId(),
                    setting.isAttendanceCompletionEnabled(), setting.getAttendanceCompletenessRate());
        }
    }
}
