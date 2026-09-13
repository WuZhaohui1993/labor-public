package com.labor.sync.push;

import com.labor.sync.common.BusinessException;
import com.labor.sync.common.CryptoService;
import com.labor.sync.hik.HikAttendanceEvent;
import com.labor.sync.hik.HikAttendanceEventRepository;
import com.labor.sync.hik.MatchStatus;
import com.labor.sync.integration.IntegrationProperties;
import com.labor.sync.integration.LaborPushOperation;
import com.labor.sync.masterdata.CompanyRepository;
import com.labor.sync.masterdata.LaborCompany;
import com.labor.sync.masterdata.LaborPerson;
import com.labor.sync.masterdata.LaborProject;
import com.labor.sync.masterdata.LaborTeam;
import com.labor.sync.masterdata.PersonRepository;
import com.labor.sync.masterdata.ProjectRepository;
import com.labor.sync.masterdata.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PushPayloadService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Set<String> CHECK_TYPES = Set.of(
            "ZHENGCHANG_KAOQINLEIBIE", "QINGJIA_KAOQINLEIBIE", "XIUJIA_KAOQINLEIBIE",
            "HOUBU_KAOQINLEIBIE", "QITA_KAOQINLEIBIE");
    private static final Set<String> CHECK_WAYS = Set.of(
            "FACE_FANGSHI", "EYES_FANGSHI", "FINGERS_FANGSHI", "HAND_FANGSHI", "IDCARD_FANGSHI",
            "NAME_FANGSHI", "ERROR_FANGSHI", "SWITCH_FANGSHI", "EMERGENCY_FANGSHI",
            "QRCODE_FANGSHI", "OTHER_FANGSHI");

    private final ProjectRepository projectRepository;
    private final CompanyRepository companyRepository;
    private final TeamRepository teamRepository;
    private final PersonRepository personRepository;
    private final HikAttendanceEventRepository eventRepository;
    private final CryptoService cryptoService;
    private final IntegrationProperties properties;

    @Transactional(readOnly = true)
    public PayloadBundle build(PushTask task) {
        return switch (task.getTaskType()) {
            case PROJECT -> project(task);
            case COMPANY -> company(task);
            case TEAM -> team(task);
            case PERSON -> person(task);
            case ATTENDANCE -> attendance(task);
        };
    }

    private PayloadBundle project(PushTask task) {
        LaborProject item = projectRepository.findById(longId(task))
                .orElseThrow(() -> missing("项目"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proCode", item.getProCode());
        payload.put("collCompanyName", item.getProjectName());
        payload.put("sign", 1);
        return bundle(LaborPushOperation.PROJECT, payload);
    }

    private PayloadBundle company(PushTask task) {
        LaborCompany item = companyRepository.findById(longId(task))
                .orElseThrow(() -> missing("参建企业"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proCode", item.getProCode());
        payload.put("collCropCode", item.getCollCropCode());
        payload.put("collCompanyName", item.getCompanyName());
        payload.put("collCropType", item.getCollCropType());
        payload.put("isChina", item.getChinaFlag());
        payload.put("sign", 2);
        if (minimalPayload()) return bundle(LaborPushOperation.COMPANY, payload);
        put(payload, "entryTime", item.getEntryDate());
        put(payload, "exitTime", item.getExitDate());
        put(payload, "linkName", item.getContactName());
        put(payload, "idcardType", item.getContactIdType());
        put(payload, "idcardNumber", cryptoService.decrypt(item.getContactIdEncrypted()));
        put(payload, "linkMobile", item.getContactMobile());
        put(payload, "collCropStatus", item.getBlacklistFlag());
        return bundle(LaborPushOperation.COMPANY, payload);
    }

    private PayloadBundle team(PushTask task) {
        LaborTeam item = teamRepository.findById(longId(task))
                .orElseThrow(() -> missing("施工队"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("teamId", item.getTeamId());
        payload.put("proCode", item.getProCode());
        payload.put("collCropCode", item.getCollCropCode());
        payload.put("teamType", item.getTeamType());
        payload.put("teamName", item.getTeamName());
        payload.put("sign", 3);
        if (minimalPayload()) return bundle(LaborPushOperation.TEAM, payload);
        put(payload, "entryTime", item.getEntryDate());
        put(payload, "exitTime", item.getExitDate());
        put(payload, "teamLeaderName", item.getLeaderName());
        put(payload, "teamLeaderIdcardType", item.getLeaderIdType());
        put(payload, "teamLeaderIdcardNumber", cryptoService.decrypt(item.getLeaderIdEncrypted()));
        put(payload, "teamLeaderMobile", item.getLeaderMobile());
        return bundle(LaborPushOperation.TEAM, payload);
    }

    private PayloadBundle person(PushTask task) {
        LaborPerson item = personRepository.findById(longId(task))
                .orElseThrow(() -> missing("人员"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", item.getName());
        payload.put("idcardType", item.getIdcardType());
        payload.put("idcardNumber", cryptoService.decrypt(item.getIdcardEncrypted()));
        payload.put("idcardForever", item.getIdcardForever());
        payload.put("proCode", item.getProCode());
        payload.put("teamId", item.getTeamId());
        payload.put("userType", item.getUserType());
        payload.put("workType", item.getWorkType());
        if (minimalPayload()) {
            // V3 makes the end date conditional on a non-permanent document.
            if ("N".equals(item.getIdcardForever())) put(payload, "idcardEndDate", item.getIdcardEndDate());
            return bundle(LaborPushOperation.PERSON, payload);
        }
        put(payload, "idcardStartDate", item.getIdcardStartDate());
        put(payload, "idcardEndDate", item.getIdcardEndDate());
        put(payload, "entryTime", item.getEntryDate());
        put(payload, "exitTime", item.getExitDate());
        put(payload, "politicsStatus", item.getPoliticsStatus());
        put(payload, "eduLevel", item.getEduLevel());
        put(payload, "maritalStatus", item.getMaritalStatus());
        put(payload, "sex", item.getSex());
        put(payload, "idcardAddress", item.getIdcardAddress());
        put(payload, "homeAddress", item.getHomeAddress());
        put(payload, "birthday", item.getBirthday());
        put(payload, "nation", item.getNation());
        put(payload, "countryCode", item.getCountryCode());
        put(payload, "provinceCode", item.getProvinceCode());
        put(payload, "positiveIdcardImage", cryptoService.decrypt(item.getPositiveIdcardImageEncrypted()));
        put(payload, "negativeIdcardImage", cryptoService.decrypt(item.getNegativeIdcardImageEncrypted()));
        put(payload, "headImage", cryptoService.decrypt(item.getHeadImageEncrypted()));
        put(payload, "mobile", item.getMobile());
        put(payload, "teamLeaderFlag", item.getTeamLeaderFlag());
        return bundle(LaborPushOperation.PERSON, payload);
    }

    private PayloadBundle attendance(PushTask task) {
        HikAttendanceEvent event = eventRepository.findById(longId(task))
                .orElseThrow(() -> missing("考勤事件"));
        if (event.getMatchStatus() != MatchStatus.MATCHED || event.getMatchedPersonId() == null) {
            throw new BusinessException("ATTENDANCE_NOT_MATCHED", "考勤事件尚未完成实名人员匹配");
        }
        if (!List.of("JINCHANG_JINCHU", "TUICHANG_JINCHU").contains(event.getDirection())) {
            throw new BusinessException("ATTENDANCE_DIRECTION_INVALID", "考勤进出方向未确认");
        }
        if (!CHECK_TYPES.contains(event.getCheckType())) {
            throw new BusinessException("ATTENDANCE_CHECK_TYPE_INVALID", "考勤类别不在劳务平台字典中");
        }
        if (!CHECK_WAYS.contains(event.getCheckWay())) {
            throw new BusinessException("ATTENDANCE_CHECK_WAY_INVALID", "考勤方式不在劳务平台字典中");
        }
        LaborPerson person = personRepository.findById(event.getMatchedPersonId())
                .orElseThrow(() -> missing("匹配人员"));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(properties.getLaborPlatform().getCheckTimePattern());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proCode", event.getProCode());
        payload.put("userName", person.getName());
        payload.put("idcardType", person.getIdcardType());
        payload.put("idcardNumber", cryptoService.decrypt(person.getIdcardEncrypted()));
        payload.put("checkType", event.getCheckType());
        payload.put("checkTime", formatter.format(event.getEventTime().atZone(SHANGHAI)));
        payload.put("dierction", event.getDirection());
        payload.put("checkWay", event.getCheckWay());
        if (minimalPayload()) return bundle(LaborPushOperation.ATTENDANCE, payload);
        put(payload, "checkLocation", event.getCheckLocation());
        put(payload, "longitude", event.getLongitude());
        put(payload, "latitude", event.getLatitude());
        return bundle(LaborPushOperation.ATTENDANCE, payload);
    }

    private PayloadBundle bundle(LaborPushOperation operation, Map<String, Object> payload) {
        return new PayloadBundle(operation, List.of(payload));
    }

    private long longId(PushTask task) {
        try {
            return Long.parseLong(task.getAggregateId());
        } catch (NumberFormatException exception) {
            throw new BusinessException("TASK_AGGREGATE_INVALID", "推送任务关联数据编号非法");
        }
    }

    private BusinessException missing(String name) {
        return new BusinessException("TASK_DATA_NOT_FOUND", name + "数据不存在");
    }

    private void put(Map<String, Object> payload, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) payload.put(key, value.toString());
    }

    private boolean minimalPayload() {
        return properties.getLaborPlatform().isMinimalPayload();
    }

    public record PayloadBundle(LaborPushOperation operation, List<Map<String, Object>> payload) {
    }
}
