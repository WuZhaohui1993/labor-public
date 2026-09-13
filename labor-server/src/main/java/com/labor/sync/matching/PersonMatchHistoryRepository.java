package com.labor.sync.matching;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PersonMatchHistoryRepository extends JpaRepository<PersonMatchHistory, Long> {
    List<PersonMatchHistory> findByAttendanceEventIdOrderByCreatedAtDesc(Long attendanceEventId);
}
