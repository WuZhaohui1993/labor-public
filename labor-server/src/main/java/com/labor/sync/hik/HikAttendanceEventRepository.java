package com.labor.sync.hik;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface HikAttendanceEventRepository extends JpaRepository<HikAttendanceEvent, Long>, JpaSpecificationExecutor<HikAttendanceEvent> {
    boolean existsByEventIdAndProCode(String eventId, String proCode);
    Optional<HikAttendanceEvent> findByEventIdAndProCode(String eventId, String proCode);
    List<HikAttendanceEvent> findByMatchStatusOrderByEventTimeAsc(MatchStatus status);
    List<HikAttendanceEvent> findByProCodeAndMatchStatusOrderByEventTimeAsc(String proCode, MatchStatus status);
    List<HikAttendanceEvent> findByProCodeAndMatchStatusOrderByEventTimeAsc(
            String proCode, MatchStatus status, Pageable pageable);
    List<HikAttendanceEvent> findByProCodeAndMatchedPersonIdAndAttendanceSourceAndEventTimeGreaterThanEqualAndEventTimeLessThan(
            String proCode, Long matchedPersonId, AttendanceSource attendanceSource, Instant start, Instant end);
    long countByMatchStatus(MatchStatus status);
    long countByProCodeAndMatchStatus(String proCode, MatchStatus status);

    @Query("select e.id from HikAttendanceEvent e where e.rawPayloadArchived = false "
            + "and e.attendanceSource = :source and e.matchStatus in :statuses "
            + "and e.eventTime < :cutoff order by e.eventTime asc, e.id asc")
    List<Long> findRawPayloadRetentionIds(@Param("source") AttendanceSource source,
                                          @Param("statuses") Set<MatchStatus> statuses,
                                          @Param("cutoff") Instant cutoff,
                                          Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update HikAttendanceEvent e set e.rawPayloadEncrypted = :archivedPayload, "
            + "e.rawPayloadArchived = true where e.id in :ids and e.rawPayloadArchived = false")
    int archiveRawPayloads(@Param("ids") List<Long> ids,
                           @Param("archivedPayload") String archivedPayload);

    @Query("select e.id as id, e.eventId as eventId, e.matchedPersonId as matchedPersonId, "
            + "e.eventTime as eventTime, e.attendanceSource as attendanceSource, "
            + "e.direction as direction, e.checkType as checkType, "
            + "e.checkWay as checkWay, e.checkLocation as checkLocation "
            + "from HikAttendanceEvent e where e.proCode = :proCode and e.matchStatus = :matchStatus "
            + "and e.matchedPersonId is not null and e.eventTime >= :start and e.eventTime < :end "
            + "order by e.eventTime asc, e.id asc")
    List<AttendanceStatisticsEvent> findStatisticsEvents(@Param("proCode") String proCode,
                                                         @Param("matchStatus") MatchStatus matchStatus,
                                                         @Param("start") Instant start,
                                                         @Param("end") Instant end);

    interface AttendanceStatisticsEvent {
        Long getId();
        String getEventId();
        Long getMatchedPersonId();
        Instant getEventTime();
        AttendanceSource getAttendanceSource();
        String getDirection();
        String getCheckType();
        String getCheckWay();
        String getCheckLocation();
    }
}
