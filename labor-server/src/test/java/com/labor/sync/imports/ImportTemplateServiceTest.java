package com.labor.sync.imports;

import com.labor.sync.masterdata.CompanyRepository;
import com.labor.sync.masterdata.LaborCompany;
import com.labor.sync.masterdata.LaborTeam;
import com.labor.sync.masterdata.MasterDataStatus;
import com.labor.sync.masterdata.TeamRepository;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImportTemplateServiceTest {
    private static final String PRO_CODE = "24187e";
    private static final List<String> BUSINESS_SHEETS =
            List.of("项目基本信息", "参建企业", "施工队", "人员信息");
    private static final Map<ImportScope, Map<Integer, String>> EXPECTED_VALIDATIONS = Map.of(
            ImportScope.PROJECT, Map.of(0, "ProjectCodes"),
            ImportScope.COMPANY, Map.of(
                    0, "ProjectCodes", 3, "CANJIAN_TYPE", 4, "YN_FLAG",
                    8, "ZHENGJIAN_TYPE", 11, "YN_FLAG"),
            ImportScope.TEAM, Map.of(
                    1, "ProjectCodes", 2, "CompanyCodes", 3, "TEAM_TYPE", 8, "ZHENGJIAN_TYPE"),
            ImportScope.PERSON, Map.of(
                    1, "ZHENGJIAN_TYPE", 5, "YN_FLAG", 6, "ProjectCodes", 7, "TeamIds",
                    8, "LAB_USER_TYPE", 9, "LAB_WORK_TYPE", 12, "SEX", 15, "YN_FLAG"));

    @Test
    void scopedTemplatesUseWorkspaceOptionsWithoutBrokenReferences() throws Exception {
        CompanyRepository companyRepository = mock(CompanyRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        LaborCompany company = new LaborCompany();
        company.setCollCropCode("COMPANY-001");
        company.setCompanyName("测试企业");
        LaborTeam team = new LaborTeam();
        team.setTeamId("TEAM-001");
        team.setTeamName("测试施工队");
        when(companyRepository.findByProCodeAndStatusNotOrderByCompanyNameAsc(PRO_CODE, MasterDataStatus.DISABLED))
                .thenReturn(List.of(company));
        when(teamRepository.findByProCodeAndStatusNotOrderByTeamNameAsc(PRO_CODE, MasterDataStatus.DISABLED))
                .thenReturn(List.of(team));
        ImportTemplateService service = new ImportTemplateService(companyRepository, teamRepository);
        byte[] source = Files.readAllBytes(Path.of("../docs/劳务实名制线下数据采集模板_V1.0.xlsx"));

        for (ImportScope scope : List.of(ImportScope.PROJECT, ImportScope.COMPANY, ImportScope.TEAM, ImportScope.PERSON)) {
            byte[] generated = service.scoped(source, scope, PRO_CODE);
            assertTrue(generated.length > 10_000);
            try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(generated))) {
                assertNotNull(workbook.getSheet(scope.sheetName()));
                BUSINESS_SHEETS.stream().filter(name -> !name.equals(scope.sheetName()))
                        .forEach(name -> assertNull(workbook.getSheet(name)));
                assertEquals(SheetVisibility.VERY_HIDDEN,
                        workbook.getSheetVisibility(workbook.getSheetIndex("_系统选项")));
                assertEquals("'_系统选项'!$A$2:$A$2", workbook.getName("ProjectCodes").getRefersToFormula());
                assertEquals("'_系统选项'!$B$2:$B$2", workbook.getName("CompanyCodes").getRefersToFormula());
                assertEquals("'_系统选项'!$C$2:$C$2", workbook.getName("TeamIds").getRefersToFormula());
                assertDictionaryNames(workbook);
                assertNoBrokenReferences(workbook);
                assertValidations(workbook, scope);
                int proCodeColumn = switch (scope) {
                    case PROJECT, COMPANY -> 0;
                    case TEAM -> 1;
                    case PERSON -> 6;
                    case ALL -> throw new IllegalStateException();
                };
                assertEquals(PRO_CODE, workbook.getSheet(scope.sheetName()).getRow(5)
                        .getCell(proCodeColumn).getStringCellValue());
                if (scope == ImportScope.TEAM) {
                    assertEquals("COMPANY-001", workbook.getSheet(scope.sheetName()).getRow(5)
                            .getCell(2).getStringCellValue());
                }
                if (scope == ImportScope.PERSON) {
                    assertEquals("TEAM-001", workbook.getSheet(scope.sheetName()).getRow(5)
                            .getCell(7).getStringCellValue());
                }
            }
        }
    }

    @Test
    void comprehensiveTemplateRebuildsEveryDictionaryDropdown() throws Exception {
        ImportTemplateService service = new ImportTemplateService(
                mock(CompanyRepository.class), mock(TeamRepository.class));
        byte[] source = Files.readAllBytes(Path.of("../docs/劳务实名制线下数据采集模板_V1.0.xlsx"));

        byte[] generated = service.prepare(source, ImportScope.ALL, PRO_CODE);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(generated))) {
            assertEquals("'项目基本信息'!$A$6:$A$5005",
                    workbook.getName("ProjectCodes").getRefersToFormula());
            assertEquals("'参建企业'!$B$6:$B$5005",
                    workbook.getName("CompanyCodes").getRefersToFormula());
            assertEquals("'施工队'!$A$6:$A$5005",
                    workbook.getName("TeamIds").getRefersToFormula());
            assertDictionaryNames(workbook);
            assertNoBrokenReferences(workbook);
            EXPECTED_VALIDATIONS.keySet().forEach(scope -> assertValidations(workbook, scope));
        }
    }

    private static void assertDictionaryNames(XSSFWorkbook workbook) {
        assertEquals("'数据字典'!$D$5:$D$11", workbook.getName("CANJIAN_TYPE").getRefersToFormula());
        assertEquals("'数据字典'!$D$12:$D$18", workbook.getName("ZHENGJIAN_TYPE").getRefersToFormula());
        assertEquals("'数据字典'!$D$19:$D$22", workbook.getName("TEAM_TYPE").getRefersToFormula());
        assertEquals("'数据字典'!$D$23:$D$25", workbook.getName("LAB_USER_TYPE").getRefersToFormula());
        assertEquals("'数据字典'!$D$26:$D$48", workbook.getName("LAB_WORK_TYPE").getRefersToFormula());
        assertEquals("'数据字典'!$D$49:$D$50", workbook.getName("YN_FLAG").getRefersToFormula());
        assertEquals("'数据字典'!$D$51:$D$52", workbook.getName("SEX").getRefersToFormula());
    }

    private static void assertNoBrokenReferences(XSSFWorkbook workbook) {
        workbook.getAllNames().forEach(name -> {
            assertFalse(name.getRefersToFormula().contains("#REF"));
            assertFalse(name.getRefersToFormula().contains("["));
        });
        for (String sheetName : BUSINESS_SHEETS) {
            if (workbook.getSheet(sheetName) == null) continue;
            for (DataValidation validation : workbook.getSheet(sheetName).getDataValidations()) {
                String formula = validation.getValidationConstraint().getFormula1();
                if (formula != null) {
                    assertFalse(formula.contains("#REF"));
                    assertFalse(formula.contains("["));
                }
            }
        }
    }

    private static void assertValidations(XSSFWorkbook workbook, ImportScope scope) {
        List<? extends DataValidation> validations = workbook.getSheet(scope.sheetName()).getDataValidations();
        Map<Integer, String> expected = EXPECTED_VALIDATIONS.get(scope);
        assertEquals(expected.size(), validations.size());
        expected.forEach((column, formula) -> {
            DataValidation validation = validations.stream()
                    .filter(item -> item.getRegions().countRanges() == 1)
                    .filter(item -> sameColumn(item.getRegions().getCellRangeAddress(0), column))
                    .findFirst().orElseThrow();
            assertEquals(formula, validation.getValidationConstraint().getFormula1());
            assertTrue(validation.getEmptyCellAllowed());
            assertFalse(validation.getSuppressDropDownArrow());
            assertTrue(validation.getShowPromptBox());
            assertTrue(validation.getShowErrorBox());
            assertEquals(DataValidation.ErrorStyle.STOP, validation.getErrorStyle());
            assertEquals("请从下拉列表中选择", validation.getErrorBoxText());
        });
    }

    private static boolean sameColumn(CellRangeAddress range, int column) {
        return range.getFirstRow() == 5 && range.getLastRow() == 5004
                && range.getFirstColumn() == column && range.getLastColumn() == column;
    }
}
