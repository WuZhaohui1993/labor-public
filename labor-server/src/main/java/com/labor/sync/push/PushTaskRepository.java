package com.labor.sync.push;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PushTaskRepository extends JpaRepository<PushTask, Long>, JpaSpecificationExecutor<PushTask> {
    Optional<PushTask> findByIdempotencyKey(String idempotencyKey);
    boolean existsByIdempotencyKey(String idempotencyKey);
    long countByStatus(PushTaskStatus status);
    long countByProCode(String proCode);
    long countByProCodeAndReplayJobIdIsNull(String proCode);
    long countByProCodeAndStatus(String proCode, PushTaskStatus status);
    List<PushTask> findByProCodeAndTaskTypeAndBusinessDateAndReplayJobIdIsNull(
            String proCode, PushTaskType taskType, LocalDate businessDate);
    List<PushTask> findByProCodeAndTaskTypeAndBusinessAtGreaterThanEqualAndBusinessAtLessThanAndReplayJobIdIsNull(
            String proCode, PushTaskType taskType, Instant start, Instant end);
    List<PushTask> findByStatusAndStartedAtBeforeAndReplayJobIdIsNull(PushTaskStatus status, Instant startedAt);
    List<PushTask> findByTaskTypeAndAggregateIdAndReplayJobIdIsNullOrderByCreatedAtDesc(
            PushTaskType taskType, String aggregateId);
    List<PushTask> findByProCode(String proCode);
    List<PushTask> findByProCodeAndReplayJobIdIsNull(String proCode);
    List<PushTask> findByProCodeAndStatusAndReplayJobIdIsNull(String proCode, PushTaskStatus status);
    List<PushTask> findByProCodeAndCompanyCodeAndReplayJobIdIsNull(String proCode, String companyCode);
    List<PushTask> findByProCodeAndStatus(String proCode, PushTaskStatus status);
    boolean existsByProCodeAndTaskTypeAndStatusInAndReplayJobIdIsNull(
            String proCode, PushTaskType taskType, Collection<PushTaskStatus> statuses);

    @Query("select t.taskType, t.status, count(t.id) from PushTask t "
            + "where t.proCode = :proCode and t.replayJobId is null group by t.taskType, t.status")
    List<Object[]> summarizeByProject(@Param("proCode") String proCode);

    @Query("select distinct t.aggregateId from PushTask t where t.replayJobId is null "
            + "and t.proCode = :proCode and t.taskType = :taskType and t.status = :status")
    List<String> findSuccessfulAggregateIds(@Param("proCode") String proCode,
                                            @Param("taskType") PushTaskType taskType,
                                            @Param("status") PushTaskStatus status);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PushTask t set t.status = :targetStatus, t.nextRetryAt = null, t.manualReason = :reason "
            + "where t.replayJobId is null and t.proCode = :proCode and t.status = :sourceStatus")
    int activateByProjectAndStatus(@Param("proCode") String proCode,
                                   @Param("sourceStatus") PushTaskStatus sourceStatus,
                                   @Param("targetStatus") PushTaskStatus targetStatus,
                                   @Param("reason") String reason);

    @Query("select t from PushTask t where t.replayJobId is null and t.status in :statuses "
            + "and (t.nextRetryAt is null or t.nextRetryAt <= :now) order by t.priority asc, t.createdAt asc")
    List<PushTask> findDispatchable(@Param("statuses") Collection<PushTaskStatus> statuses,
                                    @Param("now") Instant now,
                                    Pageable pageable);

    @Query("select t from PushTask t where t.replayJobId is null and t.taskType <> :excludedType "
            + "and t.status in :statuses and (t.nextRetryAt is null or t.nextRetryAt <= :now) "
            + "order by t.priority asc, t.createdAt asc")
    List<PushTask> findDispatchableExcludingType(@Param("excludedType") PushTaskType excludedType,
                                                 @Param("statuses") Collection<PushTaskStatus> statuses,
                                                 @Param("now") Instant now,
                                                 Pageable pageable);

    @Query("select t from PushTask t where t.replayJobId is null and t.proCode = :proCode and t.status in :statuses "
            + "and (t.nextRetryAt is null or t.nextRetryAt <= :now) order by t.priority asc, t.createdAt asc")
    List<PushTask> findDispatchableByProject(@Param("proCode") String proCode,
                                             @Param("statuses") Collection<PushTaskStatus> statuses,
                                             @Param("now") Instant now,
                                             Pageable pageable);

    @Query("select t from PushTask t where t.replayJobId is null and t.proCode = :proCode and t.taskType = :taskType "
            + "and t.status in :statuses and (t.nextRetryAt is null or t.nextRetryAt <= :now) "
            + "order by t.priority asc, t.createdAt asc")
    List<PushTask> findDispatchableByProjectAndType(@Param("proCode") String proCode,
                                                    @Param("taskType") PushTaskType taskType,
                                                    @Param("statuses") Collection<PushTaskStatus> statuses,
                                                    @Param("now") Instant now,
                                                    Pageable pageable);

    @Query("select t from PushTask t where t.replayJobId is null and t.proCode = :proCode "
            + "and t.taskType = :taskType and t.businessAt < :windowEnd and t.status in :statuses "
            + "and (t.nextRetryAt is null or t.nextRetryAt <= :now) "
            + "order by t.businessAt asc, t.createdAt asc")
    List<PushTask> findDispatchableAttendanceThrough(@Param("proCode") String proCode,
                                                      @Param("taskType") PushTaskType taskType,
                                                      @Param("windowEnd") Instant windowEnd,
                                                      @Param("statuses") Collection<PushTaskStatus> statuses,
                                                      @Param("now") Instant now,
                                                      Pageable pageable);

    @Query("select t from PushTask t where t.replayJobId is null and t.proCode = :proCode "
            + "and t.taskType = :taskType and t.businessAt >= :windowStart and t.businessAt < :windowEnd "
            + "and t.status in :statuses and (t.nextRetryAt is null or t.nextRetryAt <= :now) "
            + "order by t.businessAt asc, t.createdAt asc")
    List<PushTask> findDispatchableAttendanceInWindow(@Param("proCode") String proCode,
                                                       @Param("taskType") PushTaskType taskType,
                                                       @Param("windowStart") Instant windowStart,
                                                       @Param("windowEnd") Instant windowEnd,
                                                       @Param("statuses") Collection<PushTaskStatus> statuses,
                                                       @Param("now") Instant now,
                                                       Pageable pageable);

    @Query("select count(t.id) from PushTask t where t.replayJobId is null and t.proCode = :proCode "
            + "and t.taskType = :taskType and t.businessAt < :windowEnd and t.status in :statuses")
    long countAttendanceThroughByStatus(@Param("proCode") String proCode,
                                        @Param("taskType") PushTaskType taskType,
                                        @Param("windowEnd") Instant windowEnd,
                                        @Param("statuses") Collection<PushTaskStatus> statuses);

    @Query("select count(t.id) from PushTask t where t.replayJobId is null and t.proCode = :proCode "
            + "and t.taskType = :taskType and t.businessAt >= :windowStart and t.businessAt < :windowEnd "
            + "and t.status in :statuses")
    long countAttendanceInWindowByStatus(@Param("proCode") String proCode,
                                          @Param("taskType") PushTaskType taskType,
                                          @Param("windowStart") Instant windowStart,
                                          @Param("windowEnd") Instant windowEnd,
                                          @Param("statuses") Collection<PushTaskStatus> statuses);
}
