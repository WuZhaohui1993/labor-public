package com.labor.sync.push;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PushReplayItemRepository extends JpaRepository<PushReplayItem, Long> {
    List<PushReplayItem> findByJobIdOrderByTaskTypeAscIdAsc(Long jobId);

    List<PushReplayItem> findByJobIdAndStatusIn(Long jobId, java.util.Collection<PushReplayItemStatus> statuses);
}
