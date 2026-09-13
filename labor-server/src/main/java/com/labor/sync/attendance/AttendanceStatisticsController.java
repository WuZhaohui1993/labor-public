package com.labor.sync.attendance;

import com.labor.sync.audit.AuditService;
import com.labor.sync.common.ApiResponse;
import com.labor.sync.security.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/labor/admin/attendance-statistics")
@PreAuthorize("hasAuthority('hik:view')")
public class AttendanceStatisticsController {
    private final AttendanceStatisticsService statisticsService;
    private final AttendanceDailyOperationService operationService;
    private final AuditService auditService;

    @GetMapping("/daily")
    public ApiResponse<List<AttendanceStatisticsService.DailySummary>> daily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(statisticsService.summaries(WorkspaceContext.proCode(), startDate, endDate));
    }

    @GetMapping("/details")
    public ApiResponse<AttendanceStatisticsService.DailyDetails> details(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String attendanceStatus,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(statisticsService.details(WorkspaceContext.proCode(), date,
                attendanceStatus, search, page, size));
    }

    @GetMapping("/completion-preview")
    @PreAuthorize("hasAuthority('hik:operate') and hasAuthority('push:operate')")
    public ApiResponse<AttendanceDailyOperationService.DailyCompletionPreview> completionPreview(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal targetRate) {
        return ApiResponse.ok(operationService.preview(WorkspaceContext.proCode(), date, targetRate));
    }

    @PostMapping("/complete-and-push")
    @PreAuthorize("hasAuthority('hik:operate') and hasAuthority('push:operate')")
    public ApiResponse<AttendanceDailyOperationService.DailyCompletionResult> completeAndPush(
            @Valid @RequestBody CompleteAndPushRequest request) {
        AttendanceDailyOperationService.DailyCompletionResult result = operationService.completeAndPush(
                WorkspaceContext.proCode(), request.date(), request.targetRate());
        auditService.record("COMPLETE_AND_PUSH", "ATTENDANCE_DAY", request.date(),
                "目标完整率 " + request.targetRate() + "% ，新增补全 " + result.newlyGeneratedCount());
        return ApiResponse.ok(result);
    }

    public record CompleteAndPushRequest(
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal targetRate) {
    }
}
