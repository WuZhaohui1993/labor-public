package com.labor.sync.imports;

import com.labor.sync.masterdata.CompanyRepository;
import com.labor.sync.masterdata.MasterDataStatus;
import com.labor.sync.masterdata.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFName;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ImportTemplateService {
    private static final String OPTIONS_SHEET = "_系统选项";
    private static final int FIRST_DATA_ROW = 5;
    private static final int LAST_DATA_ROW = 5004;
    private static final List<String> BUSINESS_SHEETS =
            List.of("项目基本信息", "参建企业", "施工队", "人员信息");
    private static final Map<String, String> DICTIONARY_NAMES = Map.of(
            "CANJIAN_TYPE", "'数据字典'!$D$5:$D$11",
            "ZHENGJIAN_TYPE", "'数据字典'!$D$12:$D$18",
            "TEAM_TYPE", "'数据字典'!$D$19:$D$22",
            "LAB_USER_TYPE", "'数据字典'!$D$23:$D$25",
            "LAB_WORK_TYPE", "'数据字典'!$D$26:$D$48",
            "YN_FLAG", "'数据字典'!$D$49:$D$50",
            "SEX", "'数据字典'!$D$51:$D$52");

    private final CompanyRepository companyRepository;
    private final TeamRepository teamRepository;

    public byte[] prepare(byte[] source, ImportScope scope, String proCode) throws IOException {
        if (scope != ImportScope.ALL) return scoped(source, scope, proCode);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(source));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            redirectName(workbook, "ProjectCodes", "'项目基本信息'!$A$6:$A$5005");
            redirectName(workbook, "CompanyCodes", "'参建企业'!$B$6:$B$5005");
            redirectName(workbook, "TeamIds", "'施工队'!$A$6:$A$5005");
            redirectDictionaryNames(workbook);
            rebuildValidations(workbook, ImportScope.ALL);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    public byte[] scoped(byte[] source, ImportScope scope, String proCode) throws IOException {
        if (scope == ImportScope.ALL) throw new IllegalArgumentException("综合模板不能按单表裁剪");
        List<String> companyCodes = companyRepository
                .findByProCodeAndStatusNotOrderByCompanyNameAsc(proCode, MasterDataStatus.DISABLED)
                .stream().map(item -> item.getCollCropCode()).filter(this::hasText)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)).stream().toList();
        List<String> teamIds = teamRepository
                .findByProCodeAndStatusNotOrderByTeamNameAsc(proCode, MasterDataStatus.DISABLED)
                .stream().map(item -> item.getTeamId()).filter(this::hasText)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)).stream().toList();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(source));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            removeExistingOptionsSheet(workbook);
            XSSFSheet options = workbook.createSheet(OPTIONS_SHEET);
            writeOptions(options, 0, "项目编码", List.of(proCode));
            writeOptions(options, 1, "参建企业编码", companyCodes);
            writeOptions(options, 2, "施工队编码", teamIds);
            redirectName(workbook, "ProjectCodes", "A", 1);
            redirectName(workbook, "CompanyCodes", "B", companyCodes.size());
            redirectName(workbook, "TeamIds", "C", teamIds.size());
            redirectDictionaryNames(workbook);

            for (int index = workbook.getNumberOfSheets() - 1; index >= 0; index--) {
                String sheetName = workbook.getSheetName(index);
                if (BUSINESS_SHEETS.contains(sheetName) && !scope.sheetName().equals(sheetName)) {
                    workbook.removeSheetAt(index);
                }
            }

            prepareExampleRow(workbook, scope, proCode, companyCodes, teamIds);
            rebuildValidations(workbook, scope);
            int targetIndex = workbook.getSheetIndex(scope.sheetName());
            workbook.setActiveSheet(targetIndex);
            workbook.setSelectedTab(targetIndex);
            workbook.setSheetVisibility(workbook.getSheetIndex(OPTIONS_SHEET), SheetVisibility.VERY_HIDDEN);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void removeExistingOptionsSheet(XSSFWorkbook workbook) {
        int index = workbook.getSheetIndex(OPTIONS_SHEET);
        if (index >= 0) workbook.removeSheetAt(index);
    }

    private void writeOptions(XSSFSheet sheet, int column, String title, List<String> values) {
        Row header = sheet.getRow(0);
        if (header == null) header = sheet.createRow(0);
        header.createCell(column).setCellValue(title);
        for (int index = 0; index < values.size(); index++) {
            Row row = sheet.getRow(index + 1);
            if (row == null) row = sheet.createRow(index + 1);
            row.createCell(column).setCellValue(values.get(index));
        }
    }

    private void redirectName(XSSFWorkbook workbook, String nameText, String column, int valueCount) {
        int lastRow = Math.max(valueCount + 1, 2);
        redirectName(workbook, nameText,
                "'" + OPTIONS_SHEET + "'!$" + column + "$2:$" + column + "$" + lastRow);
    }

    private void redirectName(XSSFWorkbook workbook, String nameText, String formula) {
        XSSFName name = workbook.getName(nameText);
        if (name == null) {
            name = workbook.createName();
            name.setNameName(nameText);
        }
        name.setRefersToFormula(formula);
    }

    private void redirectDictionaryNames(XSSFWorkbook workbook) {
        if (workbook.getSheet("数据字典") == null) {
            throw new IllegalStateException("导入模板缺少工作表: 数据字典");
        }
        DICTIONARY_NAMES.forEach((name, formula) -> redirectName(workbook, name, formula));
    }

    private void rebuildValidations(XSSFWorkbook workbook, ImportScope scope) {
        if (scope == ImportScope.ALL) {
            rebuildValidations(workbook, ImportScope.PROJECT);
            rebuildValidations(workbook, ImportScope.COMPANY);
            rebuildValidations(workbook, ImportScope.TEAM);
            rebuildValidations(workbook, ImportScope.PERSON);
            return;
        }
        XSSFSheet sheet = workbook.getSheet(scope.sheetName());
        if (sheet == null) throw new IllegalStateException("导入模板缺少工作表: " + scope.sheetName());
        if (sheet.getCTWorksheet().isSetDataValidations()) {
            sheet.getCTWorksheet().unsetDataValidations();
        }
        for (ValidationRule rule : validationRules(scope)) {
            addListValidation(sheet, rule.column(), rule.name());
        }
    }

    private List<ValidationRule> validationRules(ImportScope scope) {
        return switch (scope) {
            case PROJECT -> List.of(new ValidationRule(0, "ProjectCodes"));
            case COMPANY -> List.of(
                    new ValidationRule(0, "ProjectCodes"),
                    new ValidationRule(3, "CANJIAN_TYPE"),
                    new ValidationRule(4, "YN_FLAG"),
                    new ValidationRule(8, "ZHENGJIAN_TYPE"),
                    new ValidationRule(11, "YN_FLAG"));
            case TEAM -> List.of(
                    new ValidationRule(1, "ProjectCodes"),
                    new ValidationRule(2, "CompanyCodes"),
                    new ValidationRule(3, "TEAM_TYPE"),
                    new ValidationRule(8, "ZHENGJIAN_TYPE"));
            case PERSON -> List.of(
                    new ValidationRule(1, "ZHENGJIAN_TYPE"),
                    new ValidationRule(5, "YN_FLAG"),
                    new ValidationRule(6, "ProjectCodes"),
                    new ValidationRule(7, "TeamIds"),
                    new ValidationRule(8, "LAB_USER_TYPE"),
                    new ValidationRule(9, "LAB_WORK_TYPE"),
                    new ValidationRule(12, "SEX"),
                    new ValidationRule(15, "YN_FLAG"));
            case ALL -> throw new IllegalArgumentException("综合模板没有单独的验证规则");
        };
    }

    private void addListValidation(XSSFSheet sheet, int column, String name) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createFormulaListConstraint(name);
        CellRangeAddressList addressList = new CellRangeAddressList(
                FIRST_DATA_ROW, LAST_DATA_ROW, column, column);
        DataValidation validation = helper.createValidation(constraint, addressList);
        validation.setEmptyCellAllowed(true);
        validation.setSuppressDropDownArrow(false);
        validation.setShowPromptBox(true);
        validation.createPromptBox("填写提示", "请从下拉列表中选择");
        validation.setShowErrorBox(true);
        validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        validation.createErrorBox("选项无效", "请从下拉列表中选择");
        sheet.addValidationData(validation);
    }

    private void prepareExampleRow(XSSFWorkbook workbook, ImportScope scope, String proCode,
                                   List<String> companyCodes, List<String> teamIds) {
        XSSFSheet sheet = workbook.getSheet(scope.sheetName());
        if (sheet == null) throw new IllegalStateException("导入模板缺少工作表: " + scope.sheetName());
        Row row = sheet.getRow(5);
        if (row == null) row = sheet.createRow(5);
        int proCodeColumn = switch (scope) {
            case PROJECT, COMPANY -> 0;
            case TEAM -> 1;
            case PERSON -> 6;
            case ALL -> throw new IllegalArgumentException("综合模板无需生成单独模板");
        };
        if (row.getCell(proCodeColumn) == null) row.createCell(proCodeColumn);
        row.getCell(proCodeColumn).setCellValue(proCode);
        if (scope == ImportScope.TEAM && !companyCodes.isEmpty()) {
            if (row.getCell(2) == null) row.createCell(2);
            row.getCell(2).setCellValue(companyCodes.get(0));
        }
        if (scope == ImportScope.PERSON && !teamIds.isEmpty()) {
            if (row.getCell(7) == null) row.createCell(7);
            row.getCell(7).setCellValue(teamIds.get(0));
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ValidationRule(int column, String name) {}
}
