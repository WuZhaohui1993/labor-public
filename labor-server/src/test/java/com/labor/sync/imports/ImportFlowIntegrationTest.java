package com.labor.sync.imports;

import com.labor.sync.masterdata.CompanyRepository;
import com.labor.sync.masterdata.PersonRepository;
import com.labor.sync.masterdata.ProjectRepository;
import com.labor.sync.masterdata.TeamRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ImportFlowIntegrationTest {
    @Autowired ImportService importService;
    @Autowired ProjectRepository projectRepository;
    @Autowired CompanyRepository companyRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired PersonRepository personRepository;
    @Autowired ImportErrorRepository errorRepository;

    @Test
    void validWorkbookCanBeReviewedAndPublishedAtomically() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "测试项目_劳务实名制主数据_20260726.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", validWorkbook());

        ImportBatch batch = importService.upload(file);
        assertThat(batch.getTotalCount()).isEqualTo(5);
        assertThat(batch.getErrorCount()).isZero();
        assertThat(batch.getNewCount()).isEqualTo(5);

        importService.submit(batch.getId());
        importService.approve(batch.getId(), "测试通过");
        ImportBatch published = importService.publish(batch.getId());

        assertThat(published.getStatus()).isEqualTo(ImportBatchStatus.PUBLISHED);
        assertThat(projectRepository.count()).isEqualTo(1);
        assertThat(companyRepository.count()).isEqualTo(1);
        assertThat(teamRepository.count()).isEqualTo(1);
        assertThat(personRepository.count()).isEqualTo(2);
    }

    @Test
    void conditionalRequiredErrorBlocksReviewSubmission() throws Exception {
        byte[] source = validWorkbook();
        byte[] invalid;
        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(source));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.getSheet("人员信息").getRow(5).getCell(4).setCellValue("");
            workbook.write(output);
            invalid = output.toByteArray();
        }
        ImportBatch batch = importService.upload(new MockMultipartFile("file", "条件必填错误.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", invalid));

        assertThat(batch.getErrorCount()).isGreaterThan(0);
        assertThatThrownBy(() -> importService.submit(batch.getId()))
                .isInstanceOf(com.labor.sync.common.BusinessException.class)
                .hasMessageContaining("校验错误");
    }

    @Test
    void headerMismatchIsLocatedAtExactSheetRowAndField() throws Exception {
        ImportBatch batch = upload("错误表头.xlsx", workbook(2, source ->
                source.getSheet("项目基本信息").getRow(4).getCell(0).setCellValue("projectCode")));

        assertThat(errorRepository.findByBatchIdOrderBySheetNameAscRowNumberAsc(batch.getId()))
                .anySatisfy(error -> {
                    assertThat(error.getSheetName()).isEqualTo("项目基本信息");
                    assertThat(error.getRowNumber()).isEqualTo(5);
                    assertThat(error.getFieldName()).isEqualTo("proCode");
                    assertThat(error.getErrorCode()).isEqualTo("HEADER_MISMATCH");
                });
    }

    @Test
    void dictionaryDateDuplicateAndMissingReferenceErrorsAreClassified() throws Exception {
        ImportBatch batch = upload("组合校验错误.xlsx", workbook(2, source -> {
            source.getSheet("参建企业").getRow(5).getCell(3).setCellValue("UNKNOWN_TYPE");
            source.getSheet("施工队").getRow(5).getCell(5).setCellValue("2026/07/01");
            Row secondPerson = source.getSheet("人员信息").getRow(6);
            secondPerson.getCell(2).setCellValue("110101199001010000");
            secondPerson.getCell(7).setCellValue("TEAM-NOT-FOUND");
        }));

        Set<String> codes = errorRepository.findByBatchIdOrderBySheetNameAscRowNumberAsc(batch.getId()).stream()
                .map(ImportError::getErrorCode)
                .collect(Collectors.toSet());
        assertThat(codes).contains("DICTIONARY_INVALID", "DATE_FORMAT", "DUPLICATE_IN_FILE", "TEAM_NOT_FOUND");
    }

    @Test
    void acceptsChineseDictionaryLabelsAndNormalizesThemToCodes() throws Exception {
        ImportBatch batch = upload("中文下拉值.xlsx", workbook(2, source -> {
            Row company = source.getSheet("参建企业").getRow(5);
            company.getCell(3).setCellValue("劳务分包");
            company.getCell(4).setCellValue("是");
            company.getCell(8).setCellValue("身份证");
            company.getCell(11).setCellValue("否");

            Row team = source.getSheet("施工队").getRow(5);
            team.getCell(3).setCellValue("参建单位类");
            team.getCell(8).setCellValue("身份证");

            Sheet persons = source.getSheet("人员信息");
            for (int rowIndex = 5; rowIndex <= 6; rowIndex++) {
                Row person = persons.getRow(rowIndex);
                person.getCell(1).setCellValue("身份证");
                person.getCell(5).setCellValue("否");
                person.getCell(8).setCellValue("施工人员");
                person.getCell(9).setCellValue("钢筋工");
                person.getCell(12).setCellValue("男");
                person.getCell(15).setCellValue("否");
            }
        }));

        assertThat(batch.getTotalCount()).isEqualTo(5);
        assertThat(batch.getErrorCount()).isZero();
        assertThat(batch.getValidCount()).isEqualTo(5);
    }

    @Test
    void publishedRowsAreClassifiedAsUnchangedUpdatedAndError() throws Exception {
        ImportBatch original = upload("首次发布.xlsx", validWorkbook());
        importService.submit(original.getId());
        importService.approve(original.getId(), "测试通过");
        importService.publish(original.getId());

        ImportBatch unchanged = upload("无变化.xlsx", validWorkbook());
        assertThat(unchanged.getNewCount()).isZero();
        assertThat(unchanged.getUpdatedCount()).isZero();
        assertThat(unchanged.getUnchangedCount()).isEqualTo(5);

        ImportBatch updated = upload("单项修改.xlsx", workbook(2, source ->
                source.getSheet("项目基本信息").getRow(5).getCell(1).setCellValue("测试项目（更新）")));
        assertThat(updated.getNewCount()).isZero();
        assertThat(updated.getUpdatedCount()).isEqualTo(1);
        assertThat(updated.getUnchangedCount()).isEqualTo(4);

        ImportBatch error = upload("错误分类.xlsx", workbook(2, source ->
                source.getSheet("人员信息").getRow(5).getCell(9).setCellValue("UNKNOWN_WORK_TYPE")));
        assertThat(error.getErrorCount()).isGreaterThan(0);
        assertThat(errorRepository.findByBatchIdOrderBySheetNameAscRowNumberAsc(error.getId()))
                .extracting(ImportError::getErrorCode)
                .contains("DICTIONARY_INVALID");
    }

    @Test
    void acceptsFiveThousandPersonBatch() throws Exception {
        ImportBatch batch = upload("5000人批次.xlsx", workbook(5000, ignored -> { }));

        assertThat(batch.getTotalCount()).isEqualTo(5003);
        assertThat(batch.getValidCount()).isEqualTo(5003);
        assertThat(batch.getErrorCount()).isZero();
        assertThat(batch.getNewCount()).isEqualTo(5003);
    }

    @Test
    void personScopedImportOnlyParsesPersonSheetAndUsesPublishedParents() throws Exception {
        ImportBatch original = upload("基础数据.xlsx", validWorkbook());
        importService.submit(original.getId());
        importService.approve(original.getId(), "测试通过");
        importService.publish(original.getId());

        ImportBatch persons = importService.upload(new MockMultipartFile("file", "人员维护.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", validWorkbook()), ImportScope.PERSON);

        assertThat(persons.getScope()).isEqualTo(ImportScope.PERSON);
        assertThat(persons.getTotalCount()).isEqualTo(2);
        assertThat(persons.getErrorCount()).isZero();
        assertThat(persons.getUnchangedCount()).isEqualTo(2);
    }

    private byte[] validWorkbook() throws Exception {
        return workbook(2, ignored -> { });
    }

    private byte[] workbook(int personCount, Consumer<XSSFWorkbook> mutation) throws Exception {
        Path path = Path.of("../docs/劳务实名制线下数据采集模板_V1.0.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(path));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            values(workbook.getSheet("项目基本信息").getRow(5),
                    "P-2026-001", "测试项目", "自动化测试");
            values(workbook.getSheet("参建企业").getRow(5),
                    "P-2026-001", "91110000123456789A", "测试劳务公司", "LAOWU_CANJIAN｜劳务分包", "Y｜是",
                    "2026-07-01", "", "张三", "SHENFEN_ZHENGJIAN｜身份证", "110101199001011234", "13800000000", "N｜否", "");
            values(workbook.getSheet("施工队").getRow(5),
                    "TEAM-001", "P-2026-001", "91110000123456789A", "CANJIAN_TEAM｜参建单位类", "施工一队",
                    "2026-07-01", "", "李四", "SHENFEN_ZHENGJIAN｜身份证", "110101198801011234", "13900000000", "");
            Sheet persons = workbook.getSheet("人员信息");
            for (int index = 0; index < personCount; index++) {
                Row person = index == 0 ? persons.getRow(5) : persons.createRow(5 + index);
                String idcard = "11010119900101" + String.format("%04d", index);
                values(person, "测试人员" + index, "SHENFEN_ZHENGJIAN｜身份证", idcard,
                        "2015-01-01", "2035-01-01", "N｜否", "P-2026-001", "TEAM-001",
                        "LAB_USER_BULIDER｜施工人员", index % 2 == 0 ? "WORK_TYPE_GJG｜钢筋工" : "WORK_TYPE_MG｜木工",
                        "2026-07-01", "", index % 2 == 0 ? "M｜男" : "F｜女", "1990-01-01",
                        "137" + String.format("%08d", index), "N｜否", "HIK-" + String.format("%05d", index), "");
            }
            mutation.accept(workbook);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private ImportBatch upload(String filename, byte[] contents) {
        return importService.upload(new MockMultipartFile("file", filename,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", contents));
    }

    private void values(Row row, String... values) {
        for (int i = 0; i < values.length; i++) {
            if (row.getCell(i) == null) row.createCell(i);
            row.getCell(i).setCellValue(values[i]);
        }
    }
}
