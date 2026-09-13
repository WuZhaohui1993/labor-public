package com.labor.sync.push;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PushReplayJobRepository extends JpaRepository<PushReplayJob, Long> {
    List<PushReplayJob> findByProCodeOrderByCreatedAtDesc(String proCode);

    List<PushReplayJob> findByStatusIn(Collection<PushReplayJobStatus> statuses);

    Optional<PushReplayJob> findFirstByProCodeAndStatusIn(String proCode, Collection<PushReplayJobStatus> statuses);
}
