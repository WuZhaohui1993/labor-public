package com.labor.sync.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labor.sync.common.CryptoService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealHikvisionGatewayTest {
    @Test
    void tolerantRowsParsingAndTotalBasedPaginationAreSupported() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        IntegrationProperties properties = new IntegrationProperties();
        HikvisionHttpSupport support = mock(HikvisionHttpSupport.class);
        when(support.post(any(), anyString(), any(), anyString())).thenReturn(mapper.readTree("""
                {"code":"0","data":{"rows":[
                  {"eventIndexCode":"E-001","personIndexCode":"H-001","name":"张三",
                   "orgIndexCode":"ORG-001",
                   "idCardNo":"110101199001011234","eventTime":"2026-07-26T01:02:03Z",
                   "inAndOutType":"1","doorName":"一号门"}
                ],"total":2}}
                """));
        RealHikvisionGateway gateway = new RealHikvisionGateway(support, properties, mapper);
        IntegrationConfig config = new IntegrationConfig();

        HikEventPage first = gateway.queryAttendanceEvents(config, new HikEventQuery("P-001", java.util.List.of("ORG-001"),
                Instant.parse("2026-07-26T00:00:00Z"), Instant.parse("2026-07-26T02:00:00Z"), 1, 1));
        HikEventPage second = gateway.queryAttendanceEvents(config, new HikEventQuery("P-001", java.util.List.of("ORG-001"),
                Instant.parse("2026-07-26T00:00:00Z"), Instant.parse("2026-07-26T02:00:00Z"), 2, 1));

        ArgumentCaptor<Object> requestBodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(support, times(2)).post(any(), eq("/artemis/api/acs/v2/door/events"),
                requestBodyCaptor.capture(), anyString());
        @SuppressWarnings("unchecked")
        Map<String, Object> firstRequest = (Map<String, Object>) requestBodyCaptor.getAllValues().get(0);
        assertThat(firstRequest)
                .containsEntry("pageNo", 1)
                .containsEntry("pageSize", 1)
                .containsEntry("startTime", "2026-07-26T08:00:00+08:00")
                .containsEntry("endTime", "2026-07-26T10:00:00+08:00")
                .containsEntry("receiveStartTime", "2026-07-26T08:00:00+08:00")
                .containsEntry("receiveEndTime", "2026-07-26T10:00:00+08:00")
                .containsEntry("sort", "eventTime")
                .containsEntry("order", "asc")
                .doesNotContainKey("orgIndexCodes");

        assertThat(first.lastPage()).isFalse();
        assertThat(second.lastPage()).isTrue();
        assertThat(first.items()).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo("E-001");
            assertThat(event.hikPersonId()).isEqualTo("H-001");
            assertThat(event.direction()).isEqualTo("JINCHANG_JINCHU");
            assertThat(event.checkWay()).isEqualTo("FACE_FANGSHI");
        });
    }

    @Test
    void actualDoorEventPersonDetailCertificateIsNormalized() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        IntegrationProperties properties = new IntegrationProperties();
        HikvisionHttpSupport support = mock(HikvisionHttpSupport.class);
        when(support.post(any(), anyString(), any(), anyString())).thenReturn(mapper.readTree("""
                {"code":"0","data":{"list":[
                  {"eventId":"E-ACTUAL-001","personId":"H-ACTUAL-001","personName":"测试人员",
                   "orgIndexCode":"ORG-001","devIndexCode":"DEV-001",
                   "eventTime":"2026-07-27T09:14:57+08:00","inAndOutType":0,
                   "personDetail":{"certType":111,"certNo":"342201198705094777"}}
                ],"total":1}}
                """));
        RealHikvisionGateway gateway = new RealHikvisionGateway(support, properties, mapper);

        HikEventPage result = gateway.queryAttendanceEvents(new IntegrationConfig(), new HikEventQuery(
                "P-001", java.util.List.of("ORG-001"), Instant.parse("2026-07-27T01:00:00Z"),
                Instant.parse("2026-07-27T02:00:00Z"), 1, 100));

        assertThat(result.items()).singleElement().satisfies(event -> {
            assertThat(event.idcardType()).isEqualTo("SHENFEN_ZHENGJIAN");
            assertThat(event.idcardNumber()).isEqualTo("342201198705094777");
            assertThat(event.direction()).isEqualTo("TUICHANG_JINCHU");
            assertThat(event.deviceIndexCode()).isEqualTo("DEV-001");
        });
    }

    @Test
    void v2EventsAreFilteredByMappedPersonOrganizationLocally() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HikvisionHttpSupport support = mock(HikvisionHttpSupport.class);
        when(support.post(any(), anyString(), any(), anyString())).thenReturn(mapper.readTree("""
                {"code":"0","data":{"list":[
                  {"eventId":"E-IN-SCOPE","personId":"H-001","orgIndexCode":"ORG-001",
                   "eventTime":"2026-07-27T09:14:57+08:00","inAndOutType":1},
                  {"eventId":"E-OUT-OF-SCOPE","personId":"H-002","orgIndexCode":"ORG-OTHER",
                   "eventTime":"2026-07-27T09:15:57+08:00","inAndOutType":1}
                ],"total":2,"totalPage":1}}
                """));
        RealHikvisionGateway gateway = new RealHikvisionGateway(support, new IntegrationProperties(), mapper);

        HikEventPage result = gateway.queryAttendanceEvents(new IntegrationConfig(), new HikEventQuery(
                "P-001", java.util.List.of("ORG-001"), Instant.parse("2026-07-27T01:00:00Z"),
                Instant.parse("2026-07-27T02:00:00Z"), 1, 100));

        assertThat(result.items()).singleElement()
                .extracting(HikEventRecord::eventId).isEqualTo("E-IN-SCOPE");
        assertThat(result.lastPage()).isTrue();
    }

    @Test
    void organizationCatalogIsParsedWithHierarchyAndPath() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HikvisionHttpSupport support = mock(HikvisionHttpSupport.class);
        when(support.post(any(), anyString(), any(), anyString())).thenReturn(mapper.readTree("""
                {"code":"0","data":{"list":[
                  {"orgIndexCode":"ROOT","orgName":"项目组织","parentOrgIndexCode":null,"orgPath":"项目组织","sort":1},
                  {"orgIndexCode":"CHILD","orgName":"施工区域","parentOrgIndexCode":"ROOT","orgPath":"项目组织/施工区域","sort":2}
                ],"total":2}}
                """));
        RealHikvisionGateway gateway = new RealHikvisionGateway(support, new IntegrationProperties(), mapper);

        assertThat(gateway.queryOrganizations(new IntegrationConfig())).containsExactly(
                new HikOrganization("ROOT", "项目组织", null, "项目组织", 1),
                new HikOrganization("CHILD", "施工区域", "ROOT", "项目组织/施工区域", 2));
    }

    @Test
    void nestedCertificateAndPictureUrisRemainVisibleInLogs() {
        HikvisionHttpSupport support = new HikvisionHttpSupport(new ObjectMapper(), mock(CryptoService.class),
                new IntegrationProperties());
        String visible = ReflectionTestUtils.invokeMethod(support, "safeBody",
                "{\"personDetail\":{\"certNo\":\"342201198705094777\",\"mobile\":\"13800138000\"},\"picUri\":\"/pic?secret\"}");
        assertThat(visible).contains("342201198705094777", "13800138000", "/pic?secret");
    }
}
