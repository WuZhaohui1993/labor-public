package com.labor.sync.hik;

import com.labor.sync.masterdata.MasterDataWriteRequest;
import com.labor.sync.masterdata.MasterDataWriteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HikCollectionConcurrencyIntegrationTest {
    @Autowired MasterDataWriteService writeService;
    @Autowired HikOrganizationMappingRepository mappingRepository;
    @Autowired HikCollectionCursorRepository cursorRepository;
    @Autowired HikAttendanceEventRepository eventRepository;
    @Autowired HikCollectionService collectionService;

    @Test
    void concurrentCollectionForSameProjectIsSerializedAndDeduplicated() throws Exception {
        writeService.createProject(new MasterDataWriteRequest.Project(
                "P-HIK-CONCURRENT", "海康并发采集测试项目", null));
        HikOrganizationMapping mapping = new HikOrganizationMapping();
        mapping.setProCode("P-HIK-CONCURRENT");
        mapping.setOrgIndexCode("ORG-HIK-CONCURRENT");
        mapping.setOrgName("海康并发采集测试组织");
        mapping.setEnabled(true);
        mapping = mappingRepository.save(mapping);

        Long mappingId = mapping.getId();
        Instant start = Instant.parse("2026-07-28T09:00:00Z");
        Instant end = Instant.parse("2026-07-28T10:30:00Z");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch begin = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HikCollectionService.CollectionResult> first = executor.submit(() -> {
                ready.countDown();
                begin.await();
                return collectionService.collectMapping(mappingId, start, end, true);
            });
            Future<HikCollectionService.CollectionResult> second = executor.submit(() -> {
                ready.countDown();
                begin.await();
                return collectionService.collectMapping(mappingId, start, end, true);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            begin.countDown();

            List<HikCollectionService.CollectionResult> results = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results).extracting(HikCollectionService.CollectionResult::inserted)
                    .containsExactlyInAnyOrder(0, 1);
            assertThat(results).extracting(HikCollectionService.CollectionResult::duplicates)
                    .containsExactlyInAnyOrder(0, 1);
            assertThat(eventRepository.findAll().stream()
                    .filter(event -> "P-HIK-CONCURRENT".equals(event.getProCode())))
                    .hasSize(1);
            assertThat(cursorRepository.findByMappingId(mappingId)).isPresent();
        } finally {
            executor.shutdownNow();
        }
    }
}
