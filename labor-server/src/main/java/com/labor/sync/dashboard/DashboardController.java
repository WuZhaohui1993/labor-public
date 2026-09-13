package com.labor.sync.dashboard;

import com.labor.sync.common.ApiResponse;
import com.labor.sync.imports.ImportBatch;
import com.labor.sync.imports.ImportBatchRepository;
import com.labor.sync.imports.ImportBatchStatus;
import com.labor.sync.hik.HikAttendanceEventRepository;
import com.labor.sync.hik.MatchStatus;
import com.labor.sync.masterdata.CompanyRepository;
import com.labor.sync.masterdata.PersonRepository;
import com.labor.sync.masterdata.ProjectRepository;
import com.labor.sync.masterdata.TeamRepository;
import com.labor.sync.push.PushTaskRepository;
import com.labor.sync.push.PushTaskStatus;
import com.labor.sync.masterdata.MasterDataStatus;
import com.labor.sync.security.WorkspaceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/labor/admin/dashboard")
public class DashboardController {
    private final ProjectRepository projectRepository;
    private final CompanyRepository companyRepository;
    private final TeamRepository teamRepository;
    private final PersonRepository personRepository;
    private final ImportBatchRepository batchRepository;
    private final HikAttendanceEventRepository attendanceEventRepository;
    private final PushTaskRepository pushTaskRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('dashboard:view')")
    public ApiResponse<DashboardView> dashboard() {
        Long projectId = WorkspaceContext.projectId();
        String proCode = WorkspaceContext.proCode();
        List<RecentBatch> recent = batchRepository.findTop5ByProjectIdOrderByCreatedAtDesc(projectId)
                .stream().map(RecentBatch::from).toList();
        return ApiResponse.ok(new DashboardView(1,
                companyRepository.countByProCodeAndStatusNot(proCode, MasterDataStatus.DISABLED),
                teamRepository.countByProCodeAndStatusNot(proCode, MasterDataStatus.DISABLED),
                personRepository.countByProCodeAndStatusNot(proCode, MasterDataStatus.DISABLED),
                batchRepository.countByProjectIdAndStatus(projectId, ImportBatchStatus.WAITING_REVIEW),
                batchRepository.countByProjectIdAndStatus(projectId, ImportBatchStatus.APPROVED),
                attendanceEventRepository.countByProCodeAndMatchStatus(proCode, MatchStatus.UNMATCHED),
                attendanceEventRepository.countByProCodeAndMatchStatus(proCode, MatchStatus.CONFLICT),
                pushTaskRepository.countByProCodeAndStatus(proCode, PushTaskStatus.WAITING_CONFIRM)
                        + pushTaskRepository.countByProCodeAndStatus(proCode, PushTaskStatus.PENDING),
                pushTaskRepository.countByProCodeAndStatus(proCode, PushTaskStatus.FAILED),
                pushTaskRepository.countByProCodeAndStatus(proCode, PushTaskStatus.PAUSED), recent));
    }

    public record DashboardView(long projects, long companies, long teams, long persons,
                                long waitingReview, long waitingPublish, long unmatchedAttendance,
                                long conflictMatches, long pendingPush, long failedPush, long pausedPush,
                                List<RecentBatch> recentBatches) {}

    public record RecentBatch(Long id, String fileName, ImportBatchStatus status, int totalCount,
                              int errorCount, Instant createdAt) {
        static RecentBatch from(ImportBatch batch) {
            return new RecentBatch(batch.getId(), batch.getFileName(), batch.getStatus(),
                    batch.getTotalCount(), batch.getErrorCount(), batch.getCreatedAt());
        }
    }
}
