package com.labor.sync.imports;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labor.sync.audit.AuditService;
import com.labor.sync.common.BusinessException;
import com.labor.sync.common.CryptoService;
import com.labor.sync.masterdata.CompanyRepository;
import com.labor.sync.masterdata.DomainOutbox;
import com.labor.sync.masterdata.DomainOutboxRepository;
import com.labor.sync.masterdata.LaborCompany;
import com.labor.sync.masterdata.LaborPerson;
import com.labor.sync.masterdata.LaborProject;
import com.labor.sync.masterdata.LaborTeam;
import com.labor.sync.masterdata.MasterDataStatus;
import com.labor.sync.masterdata.MasterDataVersion;
import com.labor.sync.masterdata.MasterDataVersionRepository;
import com.labor.sync.masterdata.PersonRepository;
import com.labor.sync.masterdata.ProjectRepository;
import com.labor.sync.masterdata.TeamRepository;
import com.labor.sync.integration.ProjectIntegrationProvisioner;
import com.labor.sync.security.SecurityUtils;
import com.labor.sync.security.WorkspaceContext;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ImportService {
    private static final int HEADER_ROW = 4;
    private static final int DATA_START_ROW = 5;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Set<String> SENSITIVE_FIELDS = Set.of("idcardNumber", "teamLeaderIdcardNumber");
    private static final Set<String> MOBILE_FIELDS = Set.of("linkMobile", "teamLeaderMobile", "mobile");

    private static final List<SheetSpec> SPECS = List.of(
            new SheetSpec("项目基本信息", "PROJECT",
                    List.of("proCode", "collCompanyName", "internalRemark"),
                    Set.of("proCode", "collCompanyName"), Set.of(), Map.of()),
            new SheetSpec("参建企业", "COMPANY",
                    List.of("proCode", "collCropCode", "collCompanyName", "collCropType", "isChina", "entryTime", "exitTime",
                            "linkName", "idcardType", "idcardNumber", "linkMobile", "collCropStatus", "internalRemark"),
                    Set.of("proCode", "collCropCode", "collCompanyName", "collCropType", "isChina"),
                    Set.of("entryTime", "exitTime"),
                    Map.of("collCropType", "CANJIAN_TYPE", "isChina", "YN_FLAG", "idcardType", "ZHENGJIAN_TYPE", "collCropStatus", "YN_FLAG")),
            new SheetSpec("施工队", "TEAM",
                    List.of("teamId", "proCode", "collCropCode", "teamType", "teamName", "entryTime", "exitTime",
                            "teamLeaderName", "teamLeaderIdcardType", "teamLeaderIdcardNumber", "teamLeaderMobile", "internalRemark"),
                    Set.of("teamId", "proCode", "collCropCode", "teamType", "teamName"),
                    Set.of("entryTime", "exitTime"),
                    Map.of("teamType", "TEAM_TYPE", "teamLeaderIdcardType", "ZHENGJIAN_TYPE")),
            new SheetSpec("人员信息", "PERSON",
                    List.of("name", "idcardType", "idcardNumber", "idcardStartDate", "idcardEndDate", "idcardForever", "proCode", "teamId",
                            "userType", "workType", "entryTime", "exitTime", "sex", "birthday", "mobile", "teamLeaderFlag", "hikPersonId", "internalRemark"),
                    Set.of("name", "idcardType", "idcardNumber", "idcardForever", "proCode", "teamId", "userType", "workType"),
                    Set.of("idcardStartDate", "idcardEndDate", "entryTime", "exitTime", "birthday"),
                    Map.of("idcardType", "ZHENGJIAN_TYPE", "idcardForever", "YN_FLAG", "userType", "LAB_USER_TYPE",
                            "workType", "LAB_WORK_TYPE", "sex", "SEX", "teamLeaderFlag", "YN_FLAG"))
    );

    private final ImportBatchRepository batchRepository;
    private final ImportRowRepository rowRepository;
    private final ImportErrorRepository errorRepository;
    private final ProjectRepository projectRepository;
    private final CompanyRepository companyRepository;
    private final TeamRepository teamRepository;
    private final PersonRepository personRepository;
    private final MasterDataVersionRepository versionRepository;
    private final DomainOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final ProjectIntegrationProvisioner integrationProvisioner;
    private final CryptoService cryptoService;
    private final AuditService auditService;

    @Value("${app.import.max-rows}")
    private int maxRows;

    @Transactional
    public ImportBatch upload(MultipartFile file) {
        return upload(file, ImportScope.ALL);
    }

    @Transactional
    public ImportBatch upload(MultipartFile file, ImportScope scope) {
        validateFile(file);
        ImportScope effectiveScope = scope == null ? ImportScope.ALL : scope;
        try {
            byte[] bytes = file.getBytes();
            ImportBatch batch = new ImportBatch();
            WorkspaceContext.WorkspaceRef workspace = WorkspaceContext.current();
            if (workspace != null) batch.setProject(projectRepository.getReferenceById(workspace.projectId()));
            batch.setFileName(Objects.requireNonNullElse(file.getOriginalFilename(), "import.xlsx"));
            batch.setFileHash(sha256(bytes));
            batch.setUploadedBy(SecurityUtils.currentUsername());
            batch.setScope(effectiveScope);
            batch.setStatus(ImportBatchStatus.UPLOADED);
            batchRepository.save(batch);

            try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                DictionaryCatalog dictionaries = readDictionaries(workbook);
                ParseContext context = new ParseContext();
                for (SheetSpec spec : SPECS.stream().filter(item -> effectiveScope.includes(item.entityType())).toList()) {
                    parseSheet(workbook, spec, dictionaries, batch, context);
                }
            }
            updateCounts(batch);
            auditService.record("UPLOAD", "IMPORT_BATCH", batch.getId(),
                    effectiveScope.displayName() + "导入：" + batch.getFileName());
            return batch;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("INVALID_WORKBOOK", "Excel 文件无法解析: " + ex.getMessage());
        }
    }

    @Transactional
    public ImportBatch submit(Long id) {
        ImportBatch batch = get(id);
        requireStatus(batch, ImportBatchStatus.UPLOADED);
        if (batch.getErrorCount() > 0) {
            throw new BusinessException("IMPORT_HAS_ERRORS", "批次仍有校验错误，不能提交复核");
        }
        batch.setStatus(ImportBatchStatus.WAITING_REVIEW);
        auditService.record("SUBMIT_REVIEW", "IMPORT_BATCH", id, null);
        return batch;
    }

    @Transactional
    public ImportBatch approve(Long id, String comment) {
        ImportBatch batch = get(id);
        requireStatus(batch, ImportBatchStatus.WAITING_REVIEW);
        batch.setStatus(ImportBatchStatus.APPROVED);
        batch.setReviewComment(comment);
        batch.setReviewedBy(SecurityUtils.currentUsername());
        batch.setReviewedAt(Instant.now());
        auditService.record("APPROVE", "IMPORT_BATCH", id, comment);
        return batch;
    }

    @Transactional
    public ImportBatch reject(Long id, String comment) {
        ImportBatch batch = get(id);
        requireStatus(batch, ImportBatchStatus.WAITING_REVIEW);
        if (comment == null || comment.isBlank()) {
            throw new BusinessException("REJECT_REASON_REQUIRED", "驳回时必须填写原因");
        }
        batch.setStatus(ImportBatchStatus.REJECTED);
        batch.setReviewComment(comment);
        batch.setReviewedBy(SecurityUtils.currentUsername());
        batch.setReviewedAt(Instant.now());
        auditService.record("REJECT", "IMPORT_BATCH", id, comment);
        return batch;
    }

    @Transactional
    public ImportBatch publish(Long id) {
        ImportBatch batch = get(id);
        requireStatus(batch, ImportBatchStatus.APPROVED);
        if (batch.getErrorCount() > 0) {
            throw new BusinessException("IMPORT_HAS_ERRORS", "存在校验错误，不能发布");
        }
        List<ImportRow> rows = rowRepository.findByBatchIdOrderByIdAsc(id).stream()
                .filter(row -> row.getValidationStatus() == RowValidationStatus.VALID)
                .filter(row -> row.getChangeType() != ChangeType.UNCHANGED)
                .sorted(Comparator.comparingInt(row -> entityOrder(row.getEntityType())))
                .toList();
        for (ImportRow row : rows) {
            Map<String, String> values = reveal(readMap(row.getNormalizedJson()), row.getEntityType());
            switch (row.getEntityType()) {
                case "PROJECT" -> publishProject(batch, row, values);
                case "COMPANY" -> publishCompany(batch, row, values);
                case "TEAM" -> publishTeam(batch, row, values);
                case "PERSON" -> publishPerson(batch, row, values);
                default -> throw new BusinessException("ENTITY_TYPE_INVALID", "未知导入数据类型");
            }
        }
        batch.setStatus(ImportBatchStatus.PUBLISHED);
        batch.setPublishedAt(Instant.now());
        auditService.record("PUBLISH", "IMPORT_BATCH", id, "发布记录 " + rows.size() + " 条");
        return batch;
    }

    @Transactional(readOnly = true)
    public ImportBatch get(Long id) {
        ImportBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new BusinessException("IMPORT_NOT_FOUND", "导入批次不存在", HttpStatus.NOT_FOUND));
        WorkspaceContext.WorkspaceRef workspace = WorkspaceContext.current();
        if (workspace != null && (batch.getProject() == null || !workspace.projectId().equals(batch.getProject().getId()))) {
            throw new BusinessException("IMPORT_NOT_FOUND", "导入批次不存在", HttpStatus.NOT_FOUND);
        }
        return batch;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException("FILE_REQUIRED", "请选择 Excel 文件");
        String name = Objects.requireNonNullElse(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") || name.endsWith(".xlsm")) {
            throw new BusinessException("FILE_TYPE_INVALID", "只允许上传不含宏的 .xlsx 文件");
        }
    }

    private void parseSheet(Workbook workbook, SheetSpec spec, DictionaryCatalog dictionaries,
                            ImportBatch batch, ParseContext context) {
        Sheet sheet = workbook.getSheet(spec.sheetName());
        if (sheet == null) {
            saveBatchError(batch, spec.sheetName(), 0, "sheet", "SHEET_MISSING", "缺少工作表", null);
            return;
        }
        Row header = sheet.getRow(HEADER_ROW);
        boolean headerValid = true;
        for (int column = 0; column < spec.fields().size(); column++) {
            String actual = cellText(header == null ? null : header.getCell(column));
            String expected = spec.fields().get(column);
            if (!expected.equals(actual)) {
                saveBatchError(batch, spec.sheetName(), HEADER_ROW + 1, expected, "HEADER_MISMATCH",
                        "表头字段应为 " + expected, actual);
                headerValid = false;
            }
        }
        if (!headerValid) return;
        int processed = 0;
        for (int rowIndex = DATA_START_ROW; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row excelRow = sheet.getRow(rowIndex);
            if (excelRow == null || isBlank(excelRow, spec.fields().size())) continue;
            processed++;
            if (processed > maxRows) {
                saveBatchError(batch, spec.sheetName(), rowIndex + 1, "row", "ROW_LIMIT_EXCEEDED",
                        "单次导入数据超过限制 " + maxRows, null);
                break;
            }
            parseRow(excelRow, rowIndex + 1, spec, dictionaries, batch, context);
        }
    }

    private void parseRow(Row excelRow, int rowNumber, SheetSpec spec, DictionaryCatalog dictionaries,
                          ImportBatch batch, ParseContext context) {
        Map<String, String> raw = new LinkedHashMap<>();
        Map<String, String> normalized = new LinkedHashMap<>();
        List<PendingError> errors = new ArrayList<>();
        for (int column = 0; column < spec.fields().size(); column++) {
            String field = spec.fields().get(column);
            Cell cell = excelRow.getCell(column);
            if (cell != null && cell.getCellType() == CellType.FORMULA) {
                errors.add(error(field, "FORMULA_NOT_ALLOWED", "业务数据不允许使用公式", null));
                raw.put(field, "");
                normalized.put(field, "");
                continue;
            }
            String value = cellText(cell);
            raw.put(field, value);
            normalized.put(field, normalize(value));
        }
        spec.required().forEach(field -> {
            if (blank(normalized.get(field))) errors.add(error(field, "REQUIRED", "必填字段不能为空", normalized.get(field)));
        });
        if (raw.values().stream().anyMatch(value -> value != null && (value.contains("DEMO-") || value.contains("【示例】")))) {
            errors.add(error("row", "DEMO_ROW", "示例行必须删除后再提交", null));
        }
        spec.dateFields().forEach(field -> validateDate(field, normalized.get(field), errors));
        spec.dictionaryFields().forEach((field, dictionary) -> {
            String value = normalized.get(field);
            if (!blank(value)) {
                String code = dictionaries.resolve(dictionary, value);
                if (code == null) {
                    errors.add(error(field, "DICTIONARY_INVALID", "值不在数据字典 " + dictionary + " 中", value));
                } else {
                    normalized.put(field, code);
                }
            }
        });
        if (batch.getProject() != null && !batch.getProject().getProCode().equals(normalized.get("proCode"))) {
            errors.add(error("proCode", "WORKSPACE_PROJECT_MISMATCH", "导入数据必须属于当前项目工作区", normalized.get("proCode")));
        }
        validateCommon(normalized, spec, errors, context);
        String businessKey = businessKey(spec.entityType(), normalized);
        if (!blank(businessKey) && !context.seenKeys.add(spec.entityType() + ":" + businessKey)) {
            errors.add(error("businessKey", "DUPLICATE_IN_FILE", "同一文件内业务唯一键重复", businessKey));
        }
        if (errors.isEmpty()) {
            registerReference(spec.entityType(), normalized, context);
        }

        ChangeType changeType = errors.isEmpty() ? calculateChange(spec.entityType(), normalized) : ChangeType.ERROR;
        ImportRow row = new ImportRow();
        row.setBatch(batch);
        row.setSheetName(spec.sheetName());
        row.setRowNumber(rowNumber);
        row.setEntityType(spec.entityType());
        row.setBusinessKey(businessKey);
        row.setRawJson(writeJson(raw));
        row.setNormalizedJson(writeJson(secureForStorage(normalized, spec.entityType())));
        row.setChangeType(changeType);
        row.setValidationStatus(errors.isEmpty() ? RowValidationStatus.VALID : RowValidationStatus.ERROR);
        rowRepository.save(row);
        errors.forEach(pending -> saveError(batch, row, spec.sheetName(), rowNumber, pending));
    }

    private void validateCommon(Map<String, String> values, SheetSpec spec, List<PendingError> errors, ParseContext context) {
        String entityType = spec.entityType();
        String proCode = values.get("proCode");
        if (!"PROJECT".equals(entityType) && !blank(proCode)
                && !context.projectKeys.contains(proCode) && !projectRepository.existsByProCode(proCode)) {
            errors.add(error("proCode", "PROJECT_NOT_FOUND", "引用的项目编码不存在", proCode));
        }
        if ("TEAM".equals(entityType)) {
            String companyKey = proCode + "|" + values.get("collCropCode");
            if (!context.companyKeys.contains(companyKey)
                    && !companyRepository.existsByProCodeAndCollCropCode(proCode, values.get("collCropCode"))) {
                errors.add(error("collCropCode", "COMPANY_NOT_FOUND", "引用的参建企业不存在", values.get("collCropCode")));
            }
        }
        if ("PERSON".equals(entityType)) {
            String teamKey = proCode + "|" + values.get("teamId");
            if (!context.teamKeys.contains(teamKey) && !teamRepository.existsByProCodeAndTeamId(proCode, values.get("teamId"))) {
                errors.add(error("teamId", "TEAM_NOT_FOUND", "引用的施工队不存在", values.get("teamId")));
            }
            if ("N".equals(values.get("idcardForever")) && blank(values.get("idcardEndDate"))) {
                errors.add(error("idcardEndDate", "CONDITION_REQUIRED", "证件非永久有效时必须填写截止日期", null));
            }
            if ("SHENFEN_ZHENGJIAN".equals(values.get("idcardType")) && !blank(values.get("idcardNumber"))
                    && !values.get("idcardNumber").matches("(?:\\d{15}|\\d{17}[0-9Xx])")) {
                errors.add(error("idcardNumber", "IDCARD_FORMAT", "身份证号码格式不正确", values.get("idcardNumber")));
            }
        }
        validateDateOrder(values, "entryTime", "exitTime", errors);
        for (String mobileField : MOBILE_FIELDS) {
            String value = values.get(mobileField);
            if (!blank(value) && !value.matches("[0-9+ -]{7,20}")) {
                errors.add(error(mobileField, "MOBILE_FORMAT", "手机号码格式不正确", value));
            }
        }
    }

    private void registerReference(String entityType, Map<String, String> values, ParseContext context) {
        switch (entityType) {
            case "PROJECT" -> context.projectKeys.add(values.get("proCode"));
            case "COMPANY" -> context.companyKeys.add(values.get("proCode") + "|" + values.get("collCropCode"));
            case "TEAM" -> context.teamKeys.add(values.get("proCode") + "|" + values.get("teamId"));
            default -> { }
        }
    }

    private ChangeType calculateChange(String type, Map<String, String> values) {
        return switch (type) {
            case "PROJECT" -> projectRepository.findByProCode(values.get("proCode"))
                    .map(item -> same(projectMap(item), values) ? ChangeType.UNCHANGED : ChangeType.UPDATED).orElse(ChangeType.NEW);
            case "COMPANY" -> companyRepository.findByProCodeAndCollCropCode(values.get("proCode"), values.get("collCropCode"))
                    .map(item -> same(companyMap(item, true), values) ? ChangeType.UNCHANGED : ChangeType.UPDATED).orElse(ChangeType.NEW);
            case "TEAM" -> teamRepository.findByProCodeAndTeamId(values.get("proCode"), values.get("teamId"))
                    .map(item -> same(teamMap(item, true), values) ? ChangeType.UNCHANGED : ChangeType.UPDATED).orElse(ChangeType.NEW);
            case "PERSON" -> personRepository.findByProCodeAndIdcardHash(values.get("proCode"), idHash(values))
                    .map(item -> same(personMap(item, true), values) ? ChangeType.UNCHANGED : ChangeType.UPDATED).orElse(ChangeType.NEW);
            default -> ChangeType.ERROR;
        };
    }

    private boolean same(Map<String, String> current, Map<String, String> incoming) {
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            if (!Objects.equals(emptyToNull(current.get(entry.getKey())), emptyToNull(entry.getValue()))) return false;
        }
        return true;
    }

    private void updateCounts(ImportBatch batch) {
        List<ImportRow> rows = rowRepository.findByBatchIdOrderByIdAsc(batch.getId());
        batch.setTotalCount(rows.size());
        batch.setValidCount((int) rows.stream().filter(row -> row.getValidationStatus() == RowValidationStatus.VALID).count());
        batch.setErrorCount((int) errorRepository.findByBatchIdOrderBySheetNameAscRowNumberAsc(batch.getId()).size());
        batch.setNewCount((int) rows.stream().filter(row -> row.getChangeType() == ChangeType.NEW).count());
        batch.setUpdatedCount((int) rows.stream().filter(row -> row.getChangeType() == ChangeType.UPDATED).count());
        batch.setUnchangedCount((int) rows.stream().filter(row -> row.getChangeType() == ChangeType.UNCHANGED).count());
    }

    private void publishProject(ImportBatch batch, ImportRow row, Map<String, String> values) {
        LaborProject entity = projectRepository.findByProCode(values.get("proCode")).orElseGet(LaborProject::new);
        Map<String, String> before = entity.getId() == null ? Map.of() : projectMap(entity);
        boolean existing = entity.getId() != null;
        entity.setProCode(values.get("proCode"));
        entity.setProjectName(values.get("collCompanyName"));
        entity.setInternalRemark(emptyToNull(values.get("internalRemark")));
        prepare(entity, batch, existing);
        projectRepository.save(entity);
        integrationProvisioner.provision(entity);
        versionAndOutbox("PROJECT", entity.getId(), row.getBusinessKey(), entity.getDataVersionNo(), before, values, batch);
    }

    private void publishCompany(ImportBatch batch, ImportRow row, Map<String, String> values) {
        LaborCompany entity = companyRepository.findByProCodeAndCollCropCode(values.get("proCode"), values.get("collCropCode")).orElseGet(LaborCompany::new);
        Map<String, String> before = entity.getId() == null ? Map.of() : companyMap(entity, true);
        boolean existing = entity.getId() != null;
        entity.setProCode(values.get("proCode"));
        entity.setCollCropCode(values.get("collCropCode"));
        entity.setCompanyName(values.get("collCompanyName"));
        entity.setCollCropType(values.get("collCropType"));
        entity.setChinaFlag(values.get("isChina"));
        entity.setEntryDate(date(values.get("entryTime")));
        entity.setExitDate(date(values.get("exitTime")));
        entity.setContactName(emptyToNull(values.get("linkName")));
        entity.setContactIdType(emptyToNull(values.get("idcardType")));
        entity.setContactIdEncrypted(cryptoService.encrypt(emptyToNull(values.get("idcardNumber"))));
        entity.setContactMobile(emptyToNull(values.get("linkMobile")));
        entity.setBlacklistFlag(emptyToNull(values.get("collCropStatus")));
        entity.setInternalRemark(emptyToNull(values.get("internalRemark")));
        prepare(entity, batch, existing);
        companyRepository.save(entity);
        versionAndOutbox("COMPANY", entity.getId(), row.getBusinessKey(), entity.getDataVersionNo(), before, values, batch);
    }

    private void publishTeam(ImportBatch batch, ImportRow row, Map<String, String> values) {
        LaborTeam entity = teamRepository.findByProCodeAndTeamId(values.get("proCode"), values.get("teamId")).orElseGet(LaborTeam::new);
        Map<String, String> before = entity.getId() == null ? Map.of() : teamMap(entity, true);
        boolean existing = entity.getId() != null;
        entity.setTeamId(values.get("teamId"));
        entity.setProCode(values.get("proCode"));
        entity.setCollCropCode(values.get("collCropCode"));
        entity.setTeamType(values.get("teamType"));
        entity.setTeamName(values.get("teamName"));
        entity.setEntryDate(date(values.get("entryTime")));
        entity.setExitDate(date(values.get("exitTime")));
        entity.setLeaderName(emptyToNull(values.get("teamLeaderName")));
        entity.setLeaderIdType(emptyToNull(values.get("teamLeaderIdcardType")));
        entity.setLeaderIdEncrypted(cryptoService.encrypt(emptyToNull(values.get("teamLeaderIdcardNumber"))));
        entity.setLeaderMobile(emptyToNull(values.get("teamLeaderMobile")));
        entity.setInternalRemark(emptyToNull(values.get("internalRemark")));
        prepare(entity, batch, existing);
        teamRepository.save(entity);
        versionAndOutbox("TEAM", entity.getId(), row.getBusinessKey(), entity.getDataVersionNo(), before, values, batch);
    }

    private void publishPerson(ImportBatch batch, ImportRow row, Map<String, String> values) {
        LaborPerson entity = personRepository.findByProCodeAndIdcardHash(values.get("proCode"), idHash(values)).orElseGet(LaborPerson::new);
        Map<String, String> before = entity.getId() == null ? Map.of() : personMap(entity, true);
        boolean existing = entity.getId() != null;
        entity.setName(values.get("name"));
        entity.setIdcardType(values.get("idcardType"));
        entity.setIdcardEncrypted(cryptoService.encrypt(values.get("idcardNumber")));
        entity.setIdcardHash(idHash(values));
        entity.setIdcardStartDate(date(values.get("idcardStartDate")));
        entity.setIdcardEndDate(date(values.get("idcardEndDate")));
        entity.setIdcardForever(values.get("idcardForever"));
        entity.setProCode(values.get("proCode"));
        entity.setTeamId(values.get("teamId"));
        entity.setUserType(values.get("userType"));
        entity.setWorkType(values.get("workType"));
        entity.setEntryDate(date(values.get("entryTime")));
        entity.setExitDate(date(values.get("exitTime")));
        // The current Excel template predates the V3 extension columns. Missing
        // extension fields must not erase values maintained in the ledger.
        if (values.containsKey("politicsStatus")) entity.setPoliticsStatus(emptyToNull(values.get("politicsStatus")));
        if (values.containsKey("eduLevel")) entity.setEduLevel(emptyToNull(values.get("eduLevel")));
        if (values.containsKey("maritalStatus")) entity.setMaritalStatus(emptyToNull(values.get("maritalStatus")));
        entity.setSex(emptyToNull(values.get("sex")));
        if (values.containsKey("idcardAddress")) entity.setIdcardAddress(emptyToNull(values.get("idcardAddress")));
        if (values.containsKey("homeAddress")) entity.setHomeAddress(emptyToNull(values.get("homeAddress")));
        entity.setBirthday(date(values.get("birthday")));
        if (values.containsKey("nation")) entity.setNation(emptyToNull(values.get("nation")));
        if (values.containsKey("countryCode")) entity.setCountryCode(emptyToNull(values.get("countryCode")));
        if (values.containsKey("provinceCode")) entity.setProvinceCode(emptyToNull(values.get("provinceCode")));
        entity.setMobile(emptyToNull(values.get("mobile")));
        entity.setTeamLeaderFlag(emptyToNull(values.get("teamLeaderFlag")));
        entity.setHikPersonId(emptyToNull(values.get("hikPersonId")));
        entity.setInternalRemark(emptyToNull(values.get("internalRemark")));
        prepare(entity, batch, existing);
        personRepository.save(entity);
        versionAndOutbox("PERSON", entity.getId(), row.getBusinessKey(), entity.getDataVersionNo(), before,
                personMap(entity, true), batch);
    }

    private void prepare(com.labor.sync.masterdata.MasterDataEntity entity, ImportBatch batch, boolean existing) {
        entity.setStatus(MasterDataStatus.PUBLISHED);
        entity.setDataVersionNo(existing ? entity.getDataVersionNo() + 1 : 1);
        entity.setSourceBatchId(batch.getId());
        entity.setPublishedAt(Instant.now());
    }

    private void versionAndOutbox(String type, Long id, String businessKey, int versionNo,
                                  Map<String, String> before, Map<String, String> after, ImportBatch batch) {
        Map<String, Object> changes = new LinkedHashMap<>();
        after.forEach((field, value) -> {
            if (!Objects.equals(emptyToNull(before.get(field)), emptyToNull(value))) {
                changes.put(field, Map.of("before", displayValue(before.get(field)), "after", displayValue(value)));
            }
        });
        Map<String, String> safeSnapshot = new LinkedHashMap<>(after);
        MasterDataVersion version = new MasterDataVersion();
        version.setEntityType(type);
        version.setEntityId(id);
        version.setBusinessKey(businessKey);
        version.setVersionNo(versionNo);
        version.setSnapshotJson(writeJson(safeSnapshot));
        version.setChangedFieldsJson(writeJson(changes));
        version.setSourceBatchId(batch.getId());
        version.setChangedBy(SecurityUtils.currentUsername());
        versionRepository.save(version);

        DomainOutbox outbox = new DomainOutbox();
        outbox.setAggregateType(type);
        outbox.setAggregateId(String.valueOf(id));
        outbox.setEventType("MASTER_DATA_PUBLISHED");
        outbox.setPayloadJson(writeJson(Map.of("entityType", type, "entityId", id,
                "versionNo", versionNo, "sourceBatchId", batch.getId())));
        outboxRepository.save(outbox);
    }

    private DictionaryCatalog readDictionaries(Workbook workbook) {
        Sheet sheet = workbook.getSheet("数据字典");
        if (sheet == null) throw new BusinessException("DICTIONARY_SHEET_MISSING", "缺少数据字典工作表");
        Map<String, Map<String, String>> aliases = new HashMap<>();
        for (int i = 4; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            String type = cellText(row.getCell(0));
            String label = cellText(row.getCell(1));
            String code = cellText(row.getCell(2));
            String display = cellText(row.getCell(3));
            if (blank(type) || blank(code)) continue;
            Map<String, String> dictionary = aliases.computeIfAbsent(type, ignored -> new LinkedHashMap<>());
            dictionary.put(code, code);
            if (!blank(label)) dictionary.put(label, code);
            if (!blank(display)) dictionary.put(display, code);
        }
        return new DictionaryCatalog(aliases);
    }

    private Map<String, String> projectMap(LaborProject entity) {
        return mapOf("proCode", entity.getProCode(), "collCompanyName", entity.getProjectName(), "internalRemark", entity.getInternalRemark());
    }

    private Map<String, String> companyMap(LaborCompany entity, boolean revealSensitive) {
        return mapOf("proCode", entity.getProCode(), "collCropCode", entity.getCollCropCode(), "collCompanyName", entity.getCompanyName(),
                "collCropType", entity.getCollCropType(), "isChina", entity.getChinaFlag(), "entryTime", text(entity.getEntryDate()),
                "exitTime", text(entity.getExitDate()), "linkName", entity.getContactName(), "idcardType", entity.getContactIdType(),
                "idcardNumber", revealSensitive ? cryptoService.decrypt(entity.getContactIdEncrypted()) : null,
                "linkMobile", entity.getContactMobile(), "collCropStatus", entity.getBlacklistFlag(), "internalRemark", entity.getInternalRemark());
    }

    private Map<String, String> teamMap(LaborTeam entity, boolean revealSensitive) {
        return mapOf("teamId", entity.getTeamId(), "proCode", entity.getProCode(), "collCropCode", entity.getCollCropCode(),
                "teamType", entity.getTeamType(), "teamName", entity.getTeamName(), "entryTime", text(entity.getEntryDate()),
                "exitTime", text(entity.getExitDate()), "teamLeaderName", entity.getLeaderName(), "teamLeaderIdcardType", entity.getLeaderIdType(),
                "teamLeaderIdcardNumber", revealSensitive ? cryptoService.decrypt(entity.getLeaderIdEncrypted()) : null,
                "teamLeaderMobile", entity.getLeaderMobile(), "internalRemark", entity.getInternalRemark());
    }

    private Map<String, String> personMap(LaborPerson entity, boolean revealSensitive) {
        return mapOf("name", entity.getName(), "idcardType", entity.getIdcardType(),
                "idcardNumber", revealSensitive ? cryptoService.decrypt(entity.getIdcardEncrypted()) : null,
                "idcardStartDate", text(entity.getIdcardStartDate()), "idcardEndDate", text(entity.getIdcardEndDate()),
                "idcardForever", entity.getIdcardForever(), "proCode", entity.getProCode(), "teamId", entity.getTeamId(),
                "userType", entity.getUserType(), "workType", entity.getWorkType(), "entryTime", text(entity.getEntryDate()),
                "exitTime", text(entity.getExitDate()), "politicsStatus", entity.getPoliticsStatus(),
                "eduLevel", entity.getEduLevel(), "maritalStatus", entity.getMaritalStatus(), "sex", entity.getSex(),
                "idcardAddress", entity.getIdcardAddress(), "homeAddress", entity.getHomeAddress(),
                "birthday", text(entity.getBirthday()), "nation", entity.getNation(), "countryCode", entity.getCountryCode(),
                "provinceCode", entity.getProvinceCode(),
                "positiveIdcardImage", present(entity.getPositiveIdcardImageEncrypted()),
                "negativeIdcardImage", present(entity.getNegativeIdcardImageEncrypted()),
                "headImage", present(entity.getHeadImageEncrypted()),
                "mobile", entity.getMobile(), "teamLeaderFlag", entity.getTeamLeaderFlag(), "hikPersonId", entity.getHikPersonId(),
                "internalRemark", entity.getInternalRemark());
    }

    private String present(String encrypted) {
        return encrypted == null || encrypted.isBlank() ? null : "已上传";
    }

    private Map<String, String> mapOf(String... entries) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) map.put(entries[i], emptyToNull(entries[i + 1]));
        return map;
    }

    private Map<String, String> secureForStorage(Map<String, String> source, String entityType) {
        Map<String, String> secured = new LinkedHashMap<>(source);
        for (String field : SENSITIVE_FIELDS) {
            String value = secured.get(field);
            if (!blank(value)) secured.put(field, "ENC:" + cryptoService.encrypt(value));
        }
        return secured;
    }

    public Map<String, String> displayNormalized(ImportRow row) {
        Map<String, String> stored = readMap(row.getNormalizedJson());
        stored.replaceAll((field, value) -> value != null && value.startsWith("ENC:")
                ? cryptoService.decrypt(value.substring(4)) : value);
        return stored;
    }

    private Map<String, String> reveal(Map<String, String> stored, String entityType) {
        stored.replaceAll((field, value) -> value != null && value.startsWith("ENC:")
                ? cryptoService.decrypt(value.substring(4)) : value);
        return stored;
    }

    private String displayValue(String value) {
        return value == null ? "" : value;
    }

    private String businessKey(String type, Map<String, String> values) {
        return switch (type) {
            case "PROJECT" -> values.get("proCode");
            case "COMPANY" -> values.get("proCode") + "|" + values.get("collCropCode");
            case "TEAM" -> values.get("proCode") + "|" + values.get("teamId");
            case "PERSON" -> blank(values.get("idcardNumber")) ? null : values.get("idcardType") + "|" + idHash(values);
            default -> null;
        };
    }

    private String idHash(Map<String, String> values) {
        return cryptoService.hmac(values.get("idcardType") + ":" + values.get("idcardNumber"));
    }

    private void validateDate(String field, String value, List<PendingError> errors) {
        if (blank(value)) return;
        try {
            LocalDate.parse(value, DATE_FORMAT);
        } catch (DateTimeParseException ex) {
            errors.add(error(field, "DATE_FORMAT", "日期必须为 yyyy-MM-dd", value));
        }
    }

    private void validateDateOrder(Map<String, String> values, String startField, String endField, List<PendingError> errors) {
        LocalDate start = date(values.get(startField));
        LocalDate end = date(values.get(endField));
        if (start != null && end != null && end.isBefore(start)) {
            errors.add(error(endField, "DATE_ORDER", "结束日期不能早于开始日期", values.get(endField)));
        }
    }

    private LocalDate date(String value) {
        if (blank(value)) return null;
        try {
            return LocalDate.parse(value, DATE_FORMAT);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        int separator = normalized.indexOf('｜');
        if (separator < 0) separator = normalized.indexOf('|');
        return (separator > 0 ? normalized.substring(0, separator) : normalized).trim();
    }

    private String cellText(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().format(DATE_FORMAT);
        }
        return new DataFormatter(Locale.CHINA, true).formatCellValue(cell).trim();
    }

    private boolean isBlank(Row row, int columns) {
        for (int i = 0; i < columns; i++) if (!blank(cellText(row.getCell(i)))) return false;
        return true;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String emptyToNull(String value) {
        return blank(value) ? null : value;
    }

    private String text(LocalDate value) {
        return value == null ? null : value.format(DATE_FORMAT);
    }

    private int entityOrder(String type) {
        return switch (type) {
            case "PROJECT" -> 0;
            case "COMPANY" -> 1;
            case "TEAM" -> 2;
            case "PERSON" -> 3;
            default -> 9;
        };
    }

    private void requireStatus(ImportBatch batch, ImportBatchStatus status) {
        if (batch.getStatus() != status) {
            throw new BusinessException("IMPORT_STATUS_INVALID", "当前状态不能执行此操作，需要状态 " + status);
        }
    }

    private PendingError error(String field, String code, String message, String raw) {
        return new PendingError(field, code, message, raw);
    }

    private void saveBatchError(ImportBatch batch, String sheet, int row, String field, String code, String message, String raw) {
        saveError(batch, null, sheet, row, new PendingError(field, code, message, raw));
    }

    private void saveError(ImportBatch batch, ImportRow row, String sheet, int rowNumber, PendingError pending) {
        ImportError error = new ImportError();
        error.setBatch(batch);
        error.setImportRow(row);
        error.setSheetName(sheet);
        error.setRowNumber(rowNumber);
        error.setFieldName(pending.field());
        error.setErrorCode(pending.code());
        error.setMessage(pending.message());
        error.setRawValue(pending.raw());
        errorRepository.save(error);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
        }
    }

    private Map<String, String> readMap(String json) {
        try {
            String source = json != null && json.startsWith("\"")
                    ? objectMapper.readValue(json, String.class)
                    : json;
            return objectMapper.readValue(source, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 解析失败", ex);
        }
    }

    private record SheetSpec(String sheetName, String entityType, List<String> fields,
                             Set<String> required, Set<String> dateFields,
                             Map<String, String> dictionaryFields) {}
    private record PendingError(String field, String code, String message, String raw) {}

    private record DictionaryCatalog(Map<String, Map<String, String>> aliases) {
        String resolve(String type, String value) {
            return aliases.getOrDefault(type, Map.of()).get(value);
        }
    }

    private static final class ParseContext {
        private final Set<String> projectKeys = new HashSet<>();
        private final Set<String> companyKeys = new HashSet<>();
        private final Set<String> teamKeys = new HashSet<>();
        private final Set<String> seenKeys = new HashSet<>();
    }
}
