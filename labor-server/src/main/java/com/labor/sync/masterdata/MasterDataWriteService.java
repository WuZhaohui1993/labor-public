package com.labor.sync.masterdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labor.sync.audit.AuditService;
import com.labor.sync.common.BusinessException;
import com.labor.sync.common.CryptoService;
import com.labor.sync.integration.ProjectIntegrationProvisioner;
import com.labor.sync.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MasterDataWriteService {
    private static final Set<String> COMPANY_TYPES = Set.of(
            "ZONGCHENGBAO_CANJIAN", "SHIGONGZONGCHENGBO_CANJIAN", "SHIGONG_FENBAO_CANJIAN",
            "ZHUANYE_CANJIAN", "LAOWU_CANJIAN", "HOUQIN_CANJIAN", "QITA_CANJIAN");
    private static final Set<String> ID_TYPES = Set.of(
            "SHENFEN_ZHENGJIAN", "JUNGUAN_ZHENGJIAN", "HUZHAO_ZHENGJIAN", "TAIWAN_ZHENGJIAN",
            "HONGKONG_ZHENGJIAN", "JINGGUAN_ZHENGJIAN", "QITA_ZHENGJIAN");
    private static final Set<String> TEAM_TYPES = Set.of("CANJIAN_TEAM", "ZHISHU_TEAM", "WAIPIN_TEAM", "TEAM_OTHER");
    private static final Set<String> USER_TYPES = Set.of("LAB_USER_MANAGE", "LAB_USER_BULIDER", "LAB_USER_OTHER");
    private static final Set<String> EDU_LEVELS = Set.of(
            "EDU_LEVEL_PRIMARY", "EDU_LEVEL_MIDDLE", "EDU_LEVEL_HIGH", "EDU_LEVEL_TECHNICAL",
            "EDU_LEVEL_JUNIOR", "EDU_LEVEL_BACHELOR", "EDU_LEVEL_MASTER", "EDU_LEVEL_DOCTORATE",
            "EDU_LEVEL_OTHER");
    private static final Set<String> MARITAL_STATUSES = Set.of("UNMARRIED", "MARRIED", "DIVORCED", "WIDOWED");
    private static final Set<String> WORK_TYPES = Set.of(
            "WORK_TYPE_GLRY", "WORK_TYPE_GJG", "WORK_TYPE_MG", "WORK_TYPE_WG", "WORK_TYPE_JZG",
            "WORK_TYPE_DG", "WORK_TYPE_HG1", "WORK_TYPE_QG", "WORK_TYPE_MAOG", "WORK_TYPE_QZG",
            "WORK_TYPE_YBG", "WORK_TYPE_FFG", "WORK_TYPE_PG", "WORK_TYPE_BA", "WORK_TYPE_CLRY",
            "WORK_TYPE_JSRY", "WORK_TYPE_JCRY", "WORK_TYPE_HQRY", "WORK_TYPE_GG", "WORK_TYPE_HNTG",
            "WORK_TYPE_WXDG", "WORK_TYPE_YBTSG", "WORK_TYPE_OTHER");
    private static final int MAX_IMAGE_BYTES = 3 * 1024 * 1024;

    private final ProjectRepository projectRepository;
    private final CompanyRepository companyRepository;
    private final TeamRepository teamRepository;
    private final PersonRepository personRepository;
    private final MasterDataVersionRepository versionRepository;
    private final DomainOutboxRepository outboxRepository;
    private final CryptoService cryptoService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ProjectIntegrationProvisioner integrationProvisioner;

    @Transactional
    public LaborProject createProject(MasterDataWriteRequest.Project request) {
        String proCode = required(request.proCode());
        if (projectRepository.existsByProCode(proCode)) throw conflict("项目编码已存在");
        LaborProject entity = new LaborProject();
        entity.setProCode(proCode);
        return saveProject(entity, request, Map.of(), "CREATE");
    }

    @Transactional
    public LaborProject updateProject(Long id, MasterDataWriteRequest.Project request) {
        LaborProject entity = projectRepository.findById(id).orElseThrow(() -> notFound("项目"));
        immutable("项目编码", entity.getProCode(), request.proCode());
        return saveProject(entity, request, projectMap(entity), "UPDATE");
    }

    private LaborProject saveProject(LaborProject entity, MasterDataWriteRequest.Project request,
                                     Map<String, String> before, String action) {
        entity.setProjectName(required(request.projectName()));
        entity.setInternalRemark(optional(request.internalRemark()));
        prepare(entity, !before.isEmpty());
        projectRepository.save(entity);
        integrationProvisioner.provision(entity);
        recordChange("PROJECT", entity.getId(), entity.getProCode(), entity.getDataVersionNo(), before, projectMap(entity));
        auditService.record(action, "PROJECT", entity.getId(), "手工维护版本 " + entity.getDataVersionNo());
        return entity;
    }

    @Transactional
    public LaborCompany createCompany(MasterDataWriteRequest.Company request) {
        String proCode = required(request.proCode());
        String companyCode = required(request.collCropCode());
        requireProject(proCode);
        if (companyRepository.existsByProCodeAndCollCropCode(proCode, companyCode)) throw conflict("项目下统一社会信用代码已存在");
        LaborCompany entity = new LaborCompany();
        entity.setProCode(proCode);
        entity.setCollCropCode(companyCode);
        return saveCompany(entity, request, Map.of(), "CREATE");
    }

    @Transactional
    public LaborCompany updateCompany(Long id, MasterDataWriteRequest.Company request) {
        LaborCompany entity = companyRepository.findById(id).orElseThrow(() -> notFound("参建企业"));
        immutable("项目编码", entity.getProCode(), request.proCode());
        immutable("统一社会信用代码", entity.getCollCropCode(), request.collCropCode());
        requireProject(entity.getProCode());
        return saveCompany(entity, request, companyMap(entity), "UPDATE");
    }

    @Transactional
    public LaborCompany updateCompanyPersonPushSetting(Long id, boolean enabled) {
        LaborCompany entity = companyRepository.findById(id).orElseThrow(() -> notFound("参建企业"));
        entity.setPersonPushEnabled(enabled);
        companyRepository.save(entity);
        auditService.record("UPDATE_PUSH_SETTING", "COMPANY", entity.getId(),
                enabled ? "开启人员及考勤推送" : "关闭人员及考勤推送");
        return entity;
    }

    private LaborCompany saveCompany(LaborCompany entity, MasterDataWriteRequest.Company request,
                                     Map<String, String> before, String action) {
        requireCode("参建企业类型", request.collCropType(), COMPANY_TYPES);
        validateDateOrder(request.entryDate(), request.exitDate(), "退场日期不能早于进场日期");
        String contactIdType = optional(request.contactIdType());
        if (contactIdType != null) requireCode("联系人证件类型", contactIdType, ID_TYPES);
        String currentId = before.isEmpty() ? null : cryptoService.decrypt(entity.getContactIdEncrypted());
        String contactId = sensitive(request.contactIdNumber(), currentId);
        String contactMobile = preserveOnEdit(request.contactMobile(), entity.getContactMobile(), !before.isEmpty());
        validatePair(contactIdType, contactId, "联系人证件类型和证件号码必须同时填写");

        entity.setCompanyName(required(request.companyName()));
        entity.setCollCropType(required(request.collCropType()));
        entity.setChinaFlag(required(request.chinaFlag()));
        entity.setEntryDate(request.entryDate());
        entity.setExitDate(request.exitDate());
        entity.setContactName(optional(request.contactName()));
        entity.setContactIdType(contactIdType);
        entity.setContactIdEncrypted(cryptoService.encrypt(contactId));
        entity.setContactMobile(contactMobile);
        entity.setBlacklistFlag(optional(request.blacklistFlag()));
        entity.setInternalRemark(optional(request.internalRemark()));
        prepare(entity, !before.isEmpty());
        companyRepository.save(entity);
        Map<String, String> after = companyMap(entity);
        recordChange("COMPANY", entity.getId(), entity.getProCode() + "|" + entity.getCollCropCode(),
                entity.getDataVersionNo(), before, after);
        auditService.record(action, "COMPANY", entity.getId(), "手工维护版本 " + entity.getDataVersionNo());
        return entity;
    }

    @Transactional
    public LaborTeam createTeam(MasterDataWriteRequest.Team request) {
        String proCode = required(request.proCode());
        String teamId = required(request.teamId());
        requireCompany(proCode, required(request.collCropCode()));
        if (teamRepository.existsByProCodeAndTeamId(proCode, teamId)) throw conflict("项目下施工队编码已存在");
        LaborTeam entity = new LaborTeam();
        entity.setTeamId(teamId);
        entity.setProCode(proCode);
        return saveTeam(entity, request, Map.of(), "CREATE");
    }

    @Transactional
    public LaborTeam updateTeam(Long id, MasterDataWriteRequest.Team request) {
        LaborTeam entity = teamRepository.findById(id).orElseThrow(() -> notFound("施工队"));
        immutable("项目编码", entity.getProCode(), request.proCode());
        immutable("施工队编码", entity.getTeamId(), request.teamId());
        requireCompany(entity.getProCode(), required(request.collCropCode()));
        return saveTeam(entity, request, teamMap(entity), "UPDATE");
    }

    private LaborTeam saveTeam(LaborTeam entity, MasterDataWriteRequest.Team request,
                               Map<String, String> before, String action) {
        requireCode("施工队类型", request.teamType(), TEAM_TYPES);
        validateDateOrder(request.entryDate(), request.exitDate(), "退场日期不能早于进场日期");
        String leaderIdType = optional(request.leaderIdType());
        if (leaderIdType != null) requireCode("队长证件类型", leaderIdType, ID_TYPES);
        String currentId = before.isEmpty() ? null : cryptoService.decrypt(entity.getLeaderIdEncrypted());
        String leaderId = sensitive(request.leaderIdNumber(), currentId);
        String leaderMobile = preserveOnEdit(request.leaderMobile(), entity.getLeaderMobile(), !before.isEmpty());
        validatePair(leaderIdType, leaderId, "队长证件类型和证件号码必须同时填写");

        entity.setCollCropCode(required(request.collCropCode()));
        entity.setTeamType(required(request.teamType()));
        entity.setTeamName(required(request.teamName()));
        entity.setEntryDate(request.entryDate());
        entity.setExitDate(request.exitDate());
        entity.setLeaderName(optional(request.leaderName()));
        entity.setLeaderIdType(leaderIdType);
        entity.setLeaderIdEncrypted(cryptoService.encrypt(leaderId));
        entity.setLeaderMobile(leaderMobile);
        entity.setInternalRemark(optional(request.internalRemark()));
        prepare(entity, !before.isEmpty());
        teamRepository.save(entity);
        Map<String, String> after = teamMap(entity);
        recordChange("TEAM", entity.getId(), entity.getProCode() + "|" + entity.getTeamId(),
                entity.getDataVersionNo(), before, after);
        auditService.record(action, "TEAM", entity.getId(), "手工维护版本 " + entity.getDataVersionNo());
        return entity;
    }

    @Transactional
    public LaborPerson createPerson(MasterDataWriteRequest.Person request) {
        requireTeam(required(request.proCode()), required(request.teamId()));
        LaborPerson entity = new LaborPerson();
        return savePerson(entity, request, Map.of(), "CREATE");
    }

    @Transactional
    public LaborPerson updatePerson(Long id, MasterDataWriteRequest.Person request) {
        LaborPerson entity = personRepository.findById(id).orElseThrow(() -> notFound("人员"));
        requireTeam(required(request.proCode()), required(request.teamId()));
        return savePerson(entity, request, personMap(entity), "UPDATE");
    }

    @Transactional
    public void deleteProject(Long id) {
        LaborProject entity = projectRepository.findById(id).orElseThrow(() -> notFound("项目"));
        if (companyRepository.existsByProCodeAndStatusNot(entity.getProCode(), MasterDataStatus.DISABLED)
                || teamRepository.existsByProCodeAndStatusNot(entity.getProCode(), MasterDataStatus.DISABLED)
                || personRepository.existsByProCodeAndStatusNot(entity.getProCode(), MasterDataStatus.DISABLED)) {
            throw new BusinessException("CHILD_DATA_EXISTS", "项目下仍有参建企业、施工队或人员，不能删除");
        }
        Map<String, String> before = projectMap(entity);
        disable(entity);
        projectRepository.save(entity);
        recordChange("PROJECT", entity.getId(), entity.getProCode(), entity.getDataVersionNo(),
                before, projectMap(entity), "MASTER_DATA_DISABLED");
        auditService.record("DELETE", "PROJECT", entity.getId(), "手工停用版本 " + entity.getDataVersionNo());
    }

    @Transactional
    public void deleteCompany(Long id) {
        LaborCompany entity = companyRepository.findById(id).orElseThrow(() -> notFound("参建企业"));
        if (teamRepository.existsByProCodeAndCollCropCodeAndStatusNot(
                entity.getProCode(), entity.getCollCropCode(), MasterDataStatus.DISABLED)) {
            throw new BusinessException("CHILD_DATA_EXISTS", "参建企业下仍有施工队，不能删除");
        }
        Map<String, String> before = companyMap(entity);
        disable(entity);
        companyRepository.save(entity);
        recordChange("COMPANY", entity.getId(), entity.getProCode() + "|" + entity.getCollCropCode(),
                entity.getDataVersionNo(), before, companyMap(entity), "MASTER_DATA_DISABLED");
        auditService.record("DELETE", "COMPANY", entity.getId(), "手工停用版本 " + entity.getDataVersionNo());
    }

    @Transactional
    public void deleteTeam(Long id) {
        LaborTeam entity = teamRepository.findById(id).orElseThrow(() -> notFound("施工队"));
        if (personRepository.existsByProCodeAndTeamIdAndStatusNot(
                entity.getProCode(), entity.getTeamId(), MasterDataStatus.DISABLED)) {
            throw new BusinessException("CHILD_DATA_EXISTS", "施工队下仍有人员，不能删除");
        }
        Map<String, String> before = teamMap(entity);
        disable(entity);
        teamRepository.save(entity);
        recordChange("TEAM", entity.getId(), entity.getProCode() + "|" + entity.getTeamId(),
                entity.getDataVersionNo(), before, teamMap(entity), "MASTER_DATA_DISABLED");
        auditService.record("DELETE", "TEAM", entity.getId(), "手工停用版本 " + entity.getDataVersionNo());
    }

    @Transactional
    public void deletePerson(Long id) {
        LaborPerson entity = personRepository.findById(id).orElseThrow(() -> notFound("人员"));
        Map<String, String> before = personMap(entity);
        disable(entity);
        personRepository.save(entity);
        recordChange("PERSON", entity.getId(), entity.getIdcardType() + "|" + entity.getIdcardHash(),
                entity.getDataVersionNo(), before, personMap(entity), "MASTER_DATA_DISABLED");
        auditService.record("DELETE", "PERSON", entity.getId(), "手工停用版本 " + entity.getDataVersionNo());
    }

    private LaborPerson savePerson(LaborPerson entity, MasterDataWriteRequest.Person request,
                                   Map<String, String> before, String action) {
        requireCode("人员证件类型", request.idcardType(), ID_TYPES);
        requireCode("人员类型", request.userType(), USER_TYPES);
        requireCode("工种", request.workType(), WORK_TYPES);
        String eduLevel = optional(request.eduLevel());
        String maritalStatus = optional(request.maritalStatus());
        if (eduLevel != null) requireCode("文化程度", eduLevel, EDU_LEVELS);
        if (maritalStatus != null) requireCode("婚姻状况", maritalStatus, MARITAL_STATUSES);
        validateDateOrder(request.entryDate(), request.exitDate(), "退场日期不能早于进场日期");
        if ("N".equals(request.idcardForever()) && request.idcardEndDate() == null) {
            throw new BusinessException("CONDITION_REQUIRED", "证件非永久有效时必须填写截止日期");
        }
        validateDateOrder(request.idcardStartDate(), request.idcardEndDate(), "证件截止日期不能早于起始日期");
        String currentId = before.isEmpty() ? null : cryptoService.decrypt(entity.getIdcardEncrypted());
        String idcard = sensitive(request.idcardNumber(), currentId);
        String mobile = preserveOnEdit(request.mobile(), entity.getMobile(), !before.isEmpty());
        if (idcard == null) throw new BusinessException("IDCARD_REQUIRED", "证件号码不能为空");
        if ("SHENFEN_ZHENGJIAN".equals(request.idcardType()) && !idcard.matches("(?:\\d{15}|\\d{17}[0-9Xx])")) {
            throw new BusinessException("IDCARD_FORMAT", "身份证号码格式不正确");
        }
        String idHash = cryptoService.hmac(required(request.idcardType()) + ":" + idcard);
        personRepository.findByProCodeAndIdcardHash(required(request.proCode()), idHash).ifPresent(existing -> {
            if (entity.getId() == null || !existing.getId().equals(entity.getId())) throw conflict("证件号码已存在");
        });

        entity.setName(required(request.name()));
        entity.setIdcardType(required(request.idcardType()));
        entity.setIdcardEncrypted(cryptoService.encrypt(idcard));
        entity.setIdcardHash(idHash);
        entity.setIdcardStartDate(request.idcardStartDate());
        entity.setIdcardEndDate(request.idcardEndDate());
        entity.setIdcardForever(required(request.idcardForever()));
        entity.setProCode(required(request.proCode()));
        entity.setTeamId(required(request.teamId()));
        entity.setUserType(required(request.userType()));
        entity.setWorkType(required(request.workType()));
        entity.setEntryDate(request.entryDate());
        entity.setExitDate(request.exitDate());
        entity.setPoliticsStatus(optional(request.politicsStatus()));
        entity.setEduLevel(eduLevel);
        entity.setMaritalStatus(maritalStatus);
        entity.setSex(optional(request.sex()));
        entity.setIdcardAddress(optional(request.idcardAddress()));
        entity.setHomeAddress(optional(request.homeAddress()));
        entity.setBirthday(request.birthday());
        entity.setNation(optional(request.nation()));
        entity.setCountryCode(optional(request.countryCode()));
        entity.setProvinceCode(optional(request.provinceCode()));
        entity.setPositiveIdcardImageEncrypted(image(request.positiveIdcardImage(),
                entity.getPositiveIdcardImageEncrypted(), !before.isEmpty(), request.clearPositiveIdcardImage(), "证件正面照"));
        entity.setNegativeIdcardImageEncrypted(image(request.negativeIdcardImage(),
                entity.getNegativeIdcardImageEncrypted(), !before.isEmpty(), request.clearNegativeIdcardImage(), "证件反面照"));
        entity.setHeadImageEncrypted(image(request.headImage(), entity.getHeadImageEncrypted(),
                !before.isEmpty(), request.clearHeadImage(), "近照"));
        entity.setMobile(mobile);
        entity.setTeamLeaderFlag(optional(request.teamLeaderFlag()));
        entity.setHikPersonId(optional(request.hikPersonId()));
        entity.setInternalRemark(optional(request.internalRemark()));
        prepare(entity, !before.isEmpty());
        personRepository.save(entity);
        Map<String, String> after = personMap(entity);
        recordChange("PERSON", entity.getId(), entity.getIdcardType() + "|" + entity.getIdcardHash(),
                entity.getDataVersionNo(), before, after);
        auditService.record(action, "PERSON", entity.getId(), "手工维护版本 " + entity.getDataVersionNo());
        return entity;
    }

    private void prepare(MasterDataEntity entity, boolean existing) {
        entity.setStatus(MasterDataStatus.PUBLISHED);
        entity.setDataVersionNo(existing ? entity.getDataVersionNo() + 1 : 1);
        entity.setSourceBatchId(null);
        entity.setPublishedAt(Instant.now());
    }

    private void disable(MasterDataEntity entity) {
        if (entity.getStatus() == MasterDataStatus.DISABLED) {
            throw new BusinessException("ALREADY_DISABLED", "该数据已删除");
        }
        entity.setStatus(MasterDataStatus.DISABLED);
        entity.setDataVersionNo(entity.getDataVersionNo() + 1);
        entity.setSourceBatchId(null);
        entity.setPublishedAt(Instant.now());
    }

    private void recordChange(String type, Long id, String businessKey, int versionNo,
                              Map<String, String> before, Map<String, String> after) {
        recordChange(type, id, businessKey, versionNo, before, after, "MASTER_DATA_PUBLISHED");
    }

    private void recordChange(String type, Long id, String businessKey, int versionNo,
                              Map<String, String> before, Map<String, String> after, String eventType) {
        Map<String, Object> changes = new LinkedHashMap<>();
        after.forEach((field, value) -> {
            if (!Objects.equals(emptyToNull(before.get(field)), emptyToNull(value))) {
                changes.put(field, Map.of("before", safe(field, before.get(field)), "after", safe(field, value)));
            }
        });
        Map<String, String> snapshot = new LinkedHashMap<>();
        after.forEach((field, value) -> snapshot.put(field, safe(field, value)));

        MasterDataVersion version = new MasterDataVersion();
        version.setEntityType(type);
        version.setEntityId(id);
        version.setBusinessKey(businessKey);
        version.setVersionNo(versionNo);
        version.setSnapshotJson(json(snapshot));
        version.setChangedFieldsJson(json(changes));
        version.setSourceBatchId(null);
        version.setChangedBy(SecurityUtils.currentUsername());
        versionRepository.save(version);

        DomainOutbox outbox = new DomainOutbox();
        outbox.setAggregateType(type);
        outbox.setAggregateId(String.valueOf(id));
        outbox.setEventType(eventType);
        outbox.setPayloadJson(json(Map.of("entityType", type, "entityId", id, "versionNo", versionNo, "manual", true)));
        outboxRepository.save(outbox);
    }

    private void requireProject(String proCode) {
        if (!projectRepository.existsByProCode(proCode)) throw new BusinessException("PROJECT_NOT_FOUND", "项目编码不存在");
    }

    private void requireCompany(String proCode, String companyCode) {
        requireProject(proCode);
        if (!companyRepository.existsByProCodeAndCollCropCode(proCode, companyCode)) {
            throw new BusinessException("COMPANY_NOT_FOUND", "项目下参建企业不存在");
        }
    }

    private void requireTeam(String proCode, String teamId) {
        requireProject(proCode);
        if (!teamRepository.existsByProCodeAndTeamId(proCode, teamId)) {
            throw new BusinessException("TEAM_NOT_FOUND", "项目下施工队不存在");
        }
    }

    private void validateDateOrder(LocalDate start, LocalDate end, String message) {
        if (start != null && end != null && end.isBefore(start)) throw new BusinessException("DATE_ORDER", message);
    }

    private void validatePair(String first, String second, String message) {
        if ((first == null) != (second == null)) throw new BusinessException("FIELD_PAIR_REQUIRED", message);
    }

    private void requireCode(String field, String value, Set<String> allowed) {
        if (!allowed.contains(required(value))) throw new BusinessException("DICTIONARY_INVALID", field + "不在数据字典中");
    }

    private void immutable(String field, String current, String incoming) {
        if (!Objects.equals(current, required(incoming))) throw new BusinessException("BUSINESS_KEY_IMMUTABLE", field + "创建后不能修改");
    }

    private String required(String value) {
        return value == null ? null : value.trim();
    }

    private String optional(String value) {
        String result = required(value);
        return result == null || result.isBlank() ? null : result;
    }

    private String sensitive(String incoming, String current) {
        String value = optional(incoming);
        return value == null ? current : value;
    }

    private String preserveOnEdit(String incoming, String current, boolean existing) {
        String value = optional(incoming);
        return existing && value == null ? current : value;
    }

    private String image(String incoming, String currentEncrypted, boolean existing, Boolean clear, String field) {
        if (Boolean.TRUE.equals(clear)) return null;
        String value = optional(incoming);
        if (value == null) return existing ? currentEncrypted : null;
        int comma = value.indexOf(',');
        if (value.startsWith("data:") && comma > 0) value = value.substring(comma + 1);
        value = value.replaceAll("\\s", "");
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 0 || decoded.length > MAX_IMAGE_BYTES) {
                throw new BusinessException("IMAGE_SIZE_INVALID", field + "必须小于等于3MB");
            }
            if (!isSupportedImage(decoded)) {
                throw new BusinessException("IMAGE_FORMAT_INVALID", field + "仅支持 JPG 或 PNG 图片");
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("IMAGE_FORMAT_INVALID", field + "不是有效的Base64图片");
        }
        return cryptoService.encrypt(value);
    }

    private boolean isSupportedImage(byte[] bytes) {
        boolean jpeg = bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff;
        boolean png = bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4e && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a
                && bytes[6] == 0x1a && bytes[7] == 0x0a;
        return jpeg || png;
    }

    private String safe(String field, String value) {
        return value == null ? "" : value;
    }

    private Map<String, String> projectMap(LaborProject item) {
        return mapOf("proCode", item.getProCode(), "projectName", item.getProjectName(), "internalRemark", item.getInternalRemark(),
                "status", item.getStatus().name());
    }

    private Map<String, String> companyMap(LaborCompany item) {
        return mapOf("proCode", item.getProCode(), "collCropCode", item.getCollCropCode(), "companyName", item.getCompanyName(),
                "collCropType", item.getCollCropType(), "chinaFlag", item.getChinaFlag(), "entryDate", text(item.getEntryDate()),
                "exitDate", text(item.getExitDate()), "contactName", item.getContactName(), "contactIdType", item.getContactIdType(),
                "contactIdNumber", cryptoService.decrypt(item.getContactIdEncrypted()), "contactMobile", item.getContactMobile(),
                "blacklistFlag", item.getBlacklistFlag(), "internalRemark", item.getInternalRemark(), "status", item.getStatus().name());
    }

    private Map<String, String> teamMap(LaborTeam item) {
        return mapOf("teamId", item.getTeamId(), "proCode", item.getProCode(), "collCropCode", item.getCollCropCode(),
                "teamType", item.getTeamType(), "teamName", item.getTeamName(), "entryDate", text(item.getEntryDate()),
                "exitDate", text(item.getExitDate()), "leaderName", item.getLeaderName(), "leaderIdType", item.getLeaderIdType(),
                "leaderIdNumber", cryptoService.decrypt(item.getLeaderIdEncrypted()), "leaderMobile", item.getLeaderMobile(),
                "internalRemark", item.getInternalRemark(), "status", item.getStatus().name());
    }

    private Map<String, String> personMap(LaborPerson item) {
        return mapOf("name", item.getName(), "idcardType", item.getIdcardType(),
                "idcardNumber", cryptoService.decrypt(item.getIdcardEncrypted()), "idcardStartDate", text(item.getIdcardStartDate()),
                "idcardEndDate", text(item.getIdcardEndDate()), "idcardForever", item.getIdcardForever(), "proCode", item.getProCode(),
                "teamId", item.getTeamId(), "userType", item.getUserType(), "workType", item.getWorkType(),
                "entryDate", text(item.getEntryDate()), "exitDate", text(item.getExitDate()),
                "politicsStatus", item.getPoliticsStatus(), "eduLevel", item.getEduLevel(),
                "maritalStatus", item.getMaritalStatus(), "sex", item.getSex(),
                "idcardAddress", item.getIdcardAddress(), "homeAddress", item.getHomeAddress(),
                "birthday", text(item.getBirthday()), "nation", item.getNation(), "countryCode", item.getCountryCode(),
                "provinceCode", item.getProvinceCode(),
                "positiveIdcardImage", present(item.getPositiveIdcardImageEncrypted()),
                "negativeIdcardImage", present(item.getNegativeIdcardImageEncrypted()),
                "headImage", present(item.getHeadImageEncrypted()),
                "mobile", item.getMobile(), "teamLeaderFlag", item.getTeamLeaderFlag(),
                "hikPersonId", item.getHikPersonId(), "internalRemark", item.getInternalRemark(), "status", item.getStatus().name());
    }

    private String present(String encrypted) {
        return encrypted == null || encrypted.isBlank() ? null : "已上传";
    }

    private Map<String, String> mapOf(String... entries) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) result.put(entries[index], emptyToNull(entries[index + 1]));
        return result;
    }

    private String text(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
        }
    }

    private BusinessException notFound(String name) {
        return new BusinessException("NOT_FOUND", name + "不存在", HttpStatus.NOT_FOUND);
    }

    private BusinessException conflict(String message) {
        return new BusinessException("BUSINESS_KEY_CONFLICT", message, HttpStatus.CONFLICT);
    }
}
