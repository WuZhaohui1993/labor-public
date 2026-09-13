package com.labor.sync.hik;

import com.labor.sync.common.BusinessException;
import com.labor.sync.common.CryptoService;
import com.labor.sync.masterdata.LaborPerson;
import com.labor.sync.masterdata.MasterDataWriteRequest;
import com.labor.sync.masterdata.MasterDataWriteService;
import com.labor.sync.masterdata.PersonRepository;
import com.labor.sync.push.PushTaskRepository;
import com.labor.sync.push.PushTaskType;
import com.labor.sync.push.IntegrationCallLogRepository;
import com.labor.sync.matching.PersonMatchingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class HikCollectionIntegrationTest {
    @Autowired MasterDataWriteService writeService;
    @Autowired HikOrganizationMappingRepository mappingRepository;
    @Autowired HikCollectionCursorRepository cursorRepository;
    @Autowired HikAttendanceEventRepository eventRepository;
    @Autowired HikCollectionService collectionService;
    @Autowired PushTaskRepository taskRepository;
    @Autowired IntegrationCallLogRepository callLogRepository;
    @Autowired PersonMatchingService matchingService;
    @Autowired PersonRepository personRepository;
    @Autowired CryptoService cryptoService;

    @Test
    void firstCollectionStartsAtBeginningOfProjectDay() {
        writeService.createProject(new MasterDataWriteRequest.Project("P-HIK-FIRST-DAY", "首次当天采集项目", null));

        HikOrganizationMapping mapping = new HikOrganizationMapping();
        mapping.setProCode("P-HIK-FIRST-DAY");
        mapping.setOrgIndexCode("ORG-HIK-FIRST-DAY");
        mapping.setOrgName("首次当天采集组织");
        mapping.setEnabled(true);
        mapping = mappingRepository.save(mapping);

        HikCollectionService.CollectionResult result = collectionService.collectMapping(mapping.getId(), null,
                Instant.parse("2026-07-28T10:30:00Z"), false);

        assertThat(result.startTime()).isEqualTo(Instant.parse("2026-07-27T16:00:00Z"));
        assertThat(result.endTime()).isEqualTo(Instant.parse("2026-07-28T10:30:00Z"));
        assertThat(cursorRepository.findByMappingId(mapping.getId())).isEmpty();
    }

    @Test
    void overlappingCollectionDeduplicatesAndMatchesByHikPersonId() {
        writeService.createProject(new MasterDataWriteRequest.Project("P-HIK-001", "海康采集测试项目", null));
        writeService.createCompany(new MasterDataWriteRequest.Company("P-HIK-001", "91110000999999999X", "海康测试企业",
                "LAOWU_CANJIAN", "Y", null, null, null, null, null, null, "N", null));
        writeService.createTeam(new MasterDataWriteRequest.Team("TEAM-HIK-001", "P-HIK-001", "91110000999999999X",
                "CANJIAN_TEAM", "海康测试施工队", null, null, null, null, null, null, null));
        LaborPerson person = writeService.createPerson(new MasterDataWriteRequest.Person("海康匹配人员", "SHENFEN_ZHENGJIAN",
                "110101199202021234", null, LocalDate.of(2035, 1, 1), "N", "P-HIK-001", "TEAM-HIK-001",
                "LAB_USER_BULIDER", "WORK_TYPE_PG", null, null, "M", null, null, "N", "MOCK-HIK-001", null));

        HikOrganizationMapping mapping = new HikOrganizationMapping();
        mapping.setProCode("P-HIK-001");
        mapping.setOrgIndexCode("ORG-HIK-001");
        mapping.setOrgName("测试组织");
        mapping.setEnabled(true);
        mappingRepository.save(mapping);

        Instant start = Instant.parse("2026-07-26T02:00:00Z");
        Instant end = Instant.parse("2026-07-26T02:05:45Z");
        HikCollectionService.CollectionResult first = collectionService.collectMapping(mapping.getId(), start, end, true);
        HikAttendanceEvent event = eventRepository.findAll().get(0);
        event.setPersonName(null);
        event.setHikPersonId(null);
        eventRepository.saveAndFlush(event);
        HikCollectionService.CollectionResult second = collectionService.collectMapping(mapping.getId(), start, end, true);

        assertThat(first.inserted()).isEqualTo(1);
        assertThat(second.inserted()).isZero();
        assertThat(second.enriched()).isEqualTo(1);
        assertThat(second.duplicates()).isEqualTo(1);
        assertThat(eventRepository.count()).isEqualTo(1);
        event = eventRepository.findAll().get(0);
        assertThat(event.getPersonName()).isEqualTo("模拟人员");
        assertThat(event.getHikPersonId()).isEqualTo("MOCK-HIK-001");
        assertThat(event.getMatchedPersonId()).isEqualTo(person.getId());
        assertThat(event.getMatchMethod()).isEqualTo(MatchMethod.HIK_PERSON_ID);
        assertThat(event.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(cursorRepository.findByMappingId(mapping.getId()).orElseThrow().getLastEventTime())
                .isEqualTo(end);
        assertThat(taskRepository.findAll()).anyMatch(task -> task.getTaskType() == PushTaskType.ATTENDANCE);

        matchingService.release(event.getId(), "验证解除重绑");
        matchingService.confirm(event.getId(), person.getId(), "验证重新绑定");
        var attendanceTasks = taskRepository.findAll().stream()
                .filter(task -> task.getTaskType() == PushTaskType.ATTENDANCE).toList();
        assertThat(attendanceTasks).hasSize(2);
        assertThat(attendanceTasks).extracting(task -> task.getIdempotencyKey()).doesNotHaveDuplicates();
        assertThat(attendanceTasks).anyMatch(task -> task.getStatus() == com.labor.sync.push.PushTaskStatus.PAUSED)
                .anyMatch(task -> task.getStatus() == com.labor.sync.push.PushTaskStatus.PENDING);
    }

    @Test
    void projectCollectionQueriesOnceAndAdvancesEveryEnabledMappingWatermark() {
        writeService.createProject(new MasterDataWriteRequest.Project(
                "P-HIK-PROJECT-ONCE", "项目级单次采集测试", null));
        HikOrganizationMapping first = new HikOrganizationMapping();
        first.setProCode("P-HIK-PROJECT-ONCE");
        first.setOrgIndexCode("ORG-HIK-PROJECT-A");
        first.setOrgName("项目级组织甲");
        first.setEnabled(true);
        first = mappingRepository.save(first);
        HikOrganizationMapping second = new HikOrganizationMapping();
        second.setProCode("P-HIK-PROJECT-ONCE");
        second.setOrgIndexCode("ORG-HIK-PROJECT-B");
        second.setOrgName("项目级组织乙");
        second.setEnabled(true);
        second = mappingRepository.save(second);

        Instant before = Instant.now();
        HikCollectionService.ProjectCollectionResult result = collectionService.collectProject("P-HIK-PROJECT-ONCE");
        Instant after = Instant.now();

        assertThat(result.mappings()).isEqualTo(2);
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.inserted()).isEqualTo(1);
        assertThat(callLogRepository.findAll()).hasSize(1);
        assertThat(cursorRepository.findByMappingId(first.getId()).orElseThrow().getLastEventTime())
                .isBetween(before, after);
        assertThat(cursorRepository.findByMappingId(second.getId()).orElseThrow().getLastEventTime())
                .isBetween(before, after);
    }

    @Test
    void projectRangeCollectionDoesNotAdvanceCursor() {
        writeService.createProject(new MasterDataWriteRequest.Project(
                "P-HIK-PROJECT-RANGE", "项目级历史补查测试", null));
        HikOrganizationMapping mapping = new HikOrganizationMapping();
        mapping.setProCode("P-HIK-PROJECT-RANGE");
        mapping.setOrgIndexCode("ORG-HIK-PROJECT-RANGE");
        mapping.setOrgName("历史补查组织");
        mapping.setEnabled(true);
        mapping = mappingRepository.save(mapping);

        HikCollectionService.ProjectCollectionResult result = collectionService.collectProjectRange(
                "P-HIK-PROJECT-RANGE", Instant.parse("2026-07-26T02:00:00Z"),
                Instant.parse("2026-07-26T02:05:45Z"));

        assertThat(result.mappings()).isEqualTo(1);
        assertThat(result.inserted()).isEqualTo(1);
        assertThat(cursorRepository.findByMappingId(mapping.getId())).isEmpty();
        assertThat(callLogRepository.findAll()).hasSize(1);
    }

    @Test
    void batchRematchAndConfirmationRequireSameHikPersonIdentity() {
        writeService.createProject(new MasterDataWriteRequest.Project("P-HIK-BATCH", "海康批量匹配项目", null));
        writeService.createCompany(new MasterDataWriteRequest.Company("P-HIK-BATCH", "91110000888888888X", "批量匹配企业",
                "LAOWU_CANJIAN", "Y", null, null, null, null, null, null, "N", null));
        writeService.createTeam(new MasterDataWriteRequest.Team("TEAM-HIK-BATCH", "P-HIK-BATCH", "91110000888888888X",
                "CANJIAN_TEAM", "批量匹配施工队", null, null, null, null, null, null, null));
        LaborPerson person = writeService.createPerson(new MasterDataWriteRequest.Person("批量匹配目标人员", "SHENFEN_ZHENGJIAN",
                "110101199303031234", null, null, "Y", "P-HIK-BATCH", "TEAM-HIK-BATCH",
                "LAB_USER_BULIDER", "WORK_TYPE_PG", null, null, "M", null, null, "N", "TARGET-HIK-ID", null));

        HikOrganizationMapping mapping = new HikOrganizationMapping();
        mapping.setProCode("P-HIK-BATCH");
        mapping.setOrgIndexCode("ORG-HIK-BATCH");
        mapping.setOrgName("批量匹配组织");
        mapping.setMappingSource("MANUAL");
        mapping.setIncludeChildren(false);
        mapping.setEnabled(true);
        mappingRepository.save(mapping);

        collectionService.collectMapping(mapping.getId(), Instant.parse("2026-07-26T03:00:00Z"),
                Instant.parse("2026-07-26T03:01:30Z"), false);
        collectionService.collectMapping(mapping.getId(), Instant.parse("2026-07-26T03:01:31Z"),
                Instant.parse("2026-07-26T03:02:30Z"), false);
        List<HikAttendanceEvent> events = eventRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(HikAttendanceEvent::getEventTime)).toList();
        List<Long> eventIds = events.stream().map(HikAttendanceEvent::getId).toList();

        var rematchResult = matchingService.batchRematch(eventIds, "P-HIK-BATCH", "批量自动重匹配测试");
        assertThat(rematchResult.total()).isEqualTo(2);
        assertThat(rematchResult.unmatched()).isEqualTo(2);

        events.get(1).setHikPersonId("DIFFERENT-HIK-ID");
        eventRepository.saveAndFlush(events.get(1));
        assertThatThrownBy(() -> matchingService.batchConfirm(eventIds, person.getId(), "P-HIK-BATCH", "错误批量确认"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("同一海康人员编号");
        events.get(1).setHikPersonId("MOCK-HIK-001");
        eventRepository.saveAndFlush(events.get(1));

        var confirmResult = matchingService.batchConfirm(eventIds, person.getId(), "P-HIK-BATCH", "批量人工确认测试");
        assertThat(confirmResult.matched()).isEqualTo(2);
        assertThat(eventRepository.findAll()).allSatisfy(event -> {
            assertThat(event.getMatchedPersonId()).isEqualTo(person.getId());
            assertThat(event.getMatchMethod()).isEqualTo(MatchMethod.MANUAL);
        });
    }

    @Test
    void certificateFallbackRepairsHistoricalHashAndMatches() {
        writeService.createProject(new MasterDataWriteRequest.Project("P-HIK-HASH", "摘要兼容测试项目", null));
        writeService.createCompany(new MasterDataWriteRequest.Company("P-HIK-HASH", "91110000777777777X", "摘要测试企业",
                "LAOWU_CANJIAN", "Y", null, null, null, null, null, null, "N", null));
        writeService.createTeam(new MasterDataWriteRequest.Team("TEAM-HIK-HASH", "P-HIK-HASH", "91110000777777777X",
                "CANJIAN_TEAM", "摘要测试施工队", null, null, null, null, null, null, null));
        String idcard = "61232319710119291X";
        LaborPerson person = writeService.createPerson(new MasterDataWriteRequest.Person("许永强", "SHENFEN_ZHENGJIAN",
                idcard, null, null, "Y", "P-HIK-HASH", "TEAM-HIK-HASH", "LAB_USER_BULIDER",
                "WORK_TYPE_PG", null, null, "M", null, null, "N", null, null));
        person.setIdcardHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        personRepository.saveAndFlush(person);

        HikAttendanceEvent event = new HikAttendanceEvent();
        event.setEventId("EVENT-HISTORICAL-HASH");
        event.setProCode("P-HIK-HASH");
        event.setOrgIndexCode("ORG-HIK-HASH");
        event.setPersonName("许永强");
        event.setHikPersonId("HIK-HASH-001");
        event.setIdcardType("SHENFEN_ZHENGJIAN");
        event.setIdcardEncrypted(cryptoService.encrypt(idcard));
        event.setIdcardHash(cryptoService.hmac("SHENFEN_ZHENGJIAN:" + idcard));
        event.setEventTime(Instant.parse("2026-07-27T03:56:55Z"));
        event.setDirection("JINCHANG_JINCHU");
        event.setCheckType("ZHENGCHANG_KAOQINLEIBIE");
        event.setCheckWay("FACE_FANGSHI");
        event.setRawPayloadEncrypted(cryptoService.encrypt("{}"));
        event = eventRepository.saveAndFlush(event);

        HikAttendanceEvent matched = matchingService.autoMatch(event.getId());

        assertThat(matched.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(matched.getMatchMethod()).isEqualTo(MatchMethod.CERTIFICATE);
        assertThat(matched.getMatchedPersonId()).isEqualTo(person.getId());
        assertThat(matched.getMatchReason()).contains("历史摘要");
        assertThat(personRepository.findById(person.getId()).orElseThrow().getIdcardHash())
                .isEqualTo(event.getIdcardHash());
    }
}
