package com.labor.sync.imports;

import com.labor.sync.masterdata.CompanyRepository;
import com.labor.sync.masterdata.DomainOutboxRepository;
import com.labor.sync.masterdata.MasterDataVersion;
import com.labor.sync.masterdata.MasterDataVersionRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class ImportPublishRollbackIntegrationTest {
    @Autowired ImportService importService;
    @Autowired ImportBatchRepository batchRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired CompanyRepository companyRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired PersonRepository personRepository;
    @Autowired DomainOutboxRepository outboxRepository;
    @MockitoSpyBean MasterDataVersionRepository versionRepository;

    @Test
    void publishRollsBackEveryLedgerWhenAnyStepFails() throws Exception {
        ImportBatch batch = importService.upload(new MockMultipartFile("file", "事务回滚.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", validWorkbook()));
        importService.submit(batch.getId());
        importService.approve(batch.getId(), "测试发布回滚");

        doAnswer(invocation -> invocation.getArgument(0, MasterDataVersion.class))
                .doAnswer(invocation -> invocation.getArgument(0, MasterDataVersion.class))
                .doThrow(new IllegalStateException("模拟版本记录写入失败"))
                .when(versionRepository).save(any(MasterDataVersion.class));

        assertThatThrownBy(() -> importService.publish(batch.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模拟版本记录写入失败");

        assertThat(projectRepository.count()).isZero();
        assertThat(companyRepository.count()).isZero();
        assertThat(teamRepository.count()).isZero();
        assertThat(personRepository.count()).isZero();
        assertThat(versionRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        assertThat(batchRepository.findById(batch.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportBatchStatus.APPROVED);
    }

    private byte[] validWorkbook() throws Exception {
        Path path = Path.of("../docs/劳务实名制线下数据采集模板_V1.0.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(path));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            values(workbook.getSheet("项目基本信息").getRow(5),
                    "P-ROLLBACK-001", "事务回滚测试项目", "自动化测试");
            values(workbook.getSheet("参建企业").getRow(5),
                    "P-ROLLBACK-001", "91110000999999999A", "事务回滚测试企业", "LAOWU_CANJIAN｜劳务分包", "Y｜是",
                    "2026-07-01", "", "张三", "SHENFEN_ZHENGJIAN｜身份证", "110101199001011234", "13800000000", "N｜否", "");
            values(workbook.getSheet("施工队").getRow(5),
                    "TEAM-ROLLBACK-001", "P-ROLLBACK-001", "91110000999999999A", "CANJIAN_TEAM｜参建单位类", "事务回滚施工队",
                    "2026-07-01", "", "李四", "SHENFEN_ZHENGJIAN｜身份证", "110101198801011234", "13900000000", "");
            Sheet persons = workbook.getSheet("人员信息");
            values(persons.getRow(5), "王五", "SHENFEN_ZHENGJIAN｜身份证", "110101199101011234",
                    "2015-01-01", "2035-01-01", "N｜否", "P-ROLLBACK-001", "TEAM-ROLLBACK-001",
                    "LAB_USER_BULIDER｜施工人员", "WORK_TYPE_GJG｜钢筋工", "2026-07-01", "", "M｜男",
                    "1991-01-01", "13700000000", "N｜否", "HIK-ROLLBACK-001", "");
            Row second = persons.createRow(6);
            values(second, "赵六", "SHENFEN_ZHENGJIAN｜身份证", "110101199202021234",
                    "", "", "Y｜是", "P-ROLLBACK-001", "TEAM-ROLLBACK-001", "LAB_USER_BULIDER｜施工人员",
                    "WORK_TYPE_MG｜木工", "2026-07-01", "", "F｜女", "1992-02-02", "13600000000",
                    "N｜否", "HIK-ROLLBACK-002", "");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void values(Row row, String... values) {
        for (int i = 0; i < values.length; i++) {
            if (row.getCell(i) == null) row.createCell(i);
            row.getCell(i).setCellValue(values[i]);
        }
    }
}
