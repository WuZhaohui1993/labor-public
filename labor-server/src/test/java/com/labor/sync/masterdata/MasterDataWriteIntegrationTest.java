package com.labor.sync.masterdata;

import com.labor.sync.audit.AuditLogRepository;
import com.labor.sync.common.BusinessException;
import com.labor.sync.common.CryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class MasterDataWriteIntegrationTest {
    private static final String TEST_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlK0AAAAASUVORK5CYII=";
    @Autowired MasterDataWriteService writeService;
    @Autowired ProjectRepository projectRepository;
    @Autowired CompanyRepository companyRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired PersonRepository personRepository;
    @Autowired MasterDataVersionRepository versionRepository;
    @Autowired DomainOutboxRepository outboxRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired CryptoService cryptoService;

    @Test
    void manualCreateAndUpdateProduceVersionsEventsAndAuditLogs() {
        LaborProject project = writeService.createProject(project("手工维护项目"));
        LaborCompany company = writeService.createCompany(company("手工维护企业", "13800000000", "110101199001011234"));
        LaborTeam team = writeService.createTeam(team("手工维护施工队", "13900000000", "110101198801011234"));
        LaborPerson person = writeService.createPerson(person("张三", "13700000000", "110101199101011234"));

        assertThat(project.getDataVersionNo()).isEqualTo(1);
        assertThat(company.getDataVersionNo()).isEqualTo(1);
        assertThat(team.getDataVersionNo()).isEqualTo(1);
        assertThat(person.getDataVersionNo()).isEqualTo(1);
        assertThat(versionRepository.count()).isEqualTo(4);
        assertThat(outboxRepository.count()).isEqualTo(4);
        assertThat(auditLogRepository.count()).isEqualTo(4);

        writeService.updateProject(project.getId(), project("手工维护项目（修改）"));
        writeService.updateCompany(company.getId(), company("手工维护企业（修改）", "", ""));
        writeService.updateTeam(team.getId(), team("手工维护施工队（修改）", "", ""));
        writeService.updatePerson(person.getId(), person("张三（修改）", "", ""));

        LaborCompany updatedCompany = companyRepository.findById(company.getId()).orElseThrow();
        LaborTeam updatedTeam = teamRepository.findById(team.getId()).orElseThrow();
        LaborPerson updatedPerson = personRepository.findById(person.getId()).orElseThrow();
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getDataVersionNo()).isEqualTo(2);
        assertThat(updatedCompany.getContactMobile()).isEqualTo("13800000000");
        assertThat(cryptoService.decrypt(updatedCompany.getContactIdEncrypted())).isEqualTo("110101199001011234");
        assertThat(updatedTeam.getLeaderMobile()).isEqualTo("13900000000");
        assertThat(cryptoService.decrypt(updatedTeam.getLeaderIdEncrypted())).isEqualTo("110101198801011234");
        assertThat(updatedPerson.getMobile()).isEqualTo("13700000000");
        assertThat(cryptoService.decrypt(updatedPerson.getIdcardEncrypted())).isEqualTo("110101199101011234");
        assertThat(updatedPerson.getPoliticsStatus()).isEqualTo("POLITICAL_MEMBER");
        assertThat(updatedPerson.getEduLevel()).isEqualTo("EDU_LEVEL_BACHELOR");
        assertThat(updatedPerson.getMaritalStatus()).isEqualTo("MARRIED");
        assertThat(updatedPerson.getIdcardAddress()).isEqualTo("北京市公安局");
        assertThat(updatedPerson.getHomeAddress()).isEqualTo("北京市朝阳区测试地址");
        assertThat(updatedPerson.getNation()).isEqualTo("HAN");
        assertThat(updatedPerson.getCountryCode()).isEqualTo("CHN");
        assertThat(updatedPerson.getProvinceCode()).isEqualTo("110000");
        assertThat(cryptoService.decrypt(updatedPerson.getPositiveIdcardImageEncrypted())).isEqualTo(TEST_PNG);
        assertThat(cryptoService.decrypt(updatedPerson.getNegativeIdcardImageEncrypted())).isEqualTo(TEST_PNG);
        assertThat(cryptoService.decrypt(updatedPerson.getHeadImageEncrypted())).isEqualTo(TEST_PNG);
        assertThat(versionRepository.findByEntityTypeAndEntityIdOrderByVersionNoDesc("PERSON", person.getId()).get(0)
                .getSnapshotJson()).contains("已上传").doesNotContain(TEST_PNG);
        assertThat(versionRepository.count()).isEqualTo(8);
        assertThat(outboxRepository.count()).isEqualTo(8);
        assertThat(auditLogRepository.count()).isEqualTo(8);

        assertThatThrownBy(() -> writeService.deleteProject(project.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能删除");
        writeService.deletePerson(person.getId());
        writeService.deleteTeam(team.getId());
        writeService.deleteCompany(company.getId());
        writeService.deleteProject(project.getId());

        assertThat(personRepository.findById(person.getId()).orElseThrow().getStatus()).isEqualTo(MasterDataStatus.DISABLED);
        assertThat(teamRepository.findById(team.getId()).orElseThrow().getStatus()).isEqualTo(MasterDataStatus.DISABLED);
        assertThat(companyRepository.findById(company.getId()).orElseThrow().getStatus()).isEqualTo(MasterDataStatus.DISABLED);
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getStatus()).isEqualTo(MasterDataStatus.DISABLED);
        assertThat(versionRepository.count()).isEqualTo(12);
        assertThat(outboxRepository.count()).isEqualTo(12);
        assertThat(auditLogRepository.count()).isEqualTo(12);
    }

    @Test
    void manualWriteRejectsMissingParentAndDuplicateBusinessKey() {
        assertThatThrownBy(() -> writeService.createCompany(company("无上级企业", "", "")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("项目编码不存在");

        writeService.createProject(project("项目一"));
        assertThatThrownBy(() -> writeService.createProject(project("重复项目")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("项目编码已存在");
    }

    private MasterDataWriteRequest.Project project(String name) {
        return new MasterDataWriteRequest.Project("P-MANUAL-001", name, "手工维护测试");
    }

    private MasterDataWriteRequest.Company company(String name, String mobile, String idcard) {
        return new MasterDataWriteRequest.Company("P-MANUAL-001", "91110000111111111A", name,
                "LAOWU_CANJIAN", "Y", LocalDate.of(2026, 7, 1), null, "企业联系人",
                "SHENFEN_ZHENGJIAN", idcard, mobile, "N", "手工维护测试");
    }

    private MasterDataWriteRequest.Team team(String name, String mobile, String idcard) {
        return new MasterDataWriteRequest.Team("TEAM-MANUAL-001", "P-MANUAL-001", "91110000111111111A",
                "CANJIAN_TEAM", name, LocalDate.of(2026, 7, 1), null, "施工队长",
                "SHENFEN_ZHENGJIAN", idcard, mobile, "手工维护测试");
    }

    private MasterDataWriteRequest.Person person(String name, String mobile, String idcard) {
        return new MasterDataWriteRequest.Person(name, "SHENFEN_ZHENGJIAN", idcard,
                LocalDate.of(2015, 1, 1), LocalDate.of(2035, 1, 1), "N", "P-MANUAL-001",
                "TEAM-MANUAL-001", "LAB_USER_BULIDER", "WORK_TYPE_GJG", LocalDate.of(2026, 7, 1),
                null, "POLITICAL_MEMBER", "EDU_LEVEL_BACHELOR", "MARRIED", "M",
                "北京市公安局", "北京市朝阳区测试地址", LocalDate.of(1991, 1, 1),
                "HAN", "CHN", "110000", TEST_PNG, TEST_PNG, TEST_PNG,
                false, false, false, mobile, "N", "HIK-MANUAL-001", "手工维护测试");
    }
}
