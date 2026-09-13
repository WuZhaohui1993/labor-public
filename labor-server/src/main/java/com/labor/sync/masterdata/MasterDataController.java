package com.labor.sync.masterdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labor.sync.common.ApiResponse;
import com.labor.sync.common.BusinessException;
import com.labor.sync.common.CryptoService;
import com.labor.sync.common.PageResponse;
import com.labor.sync.imports.ImportRowRepository;
import com.labor.sync.imports.ImportService;
import com.labor.sync.push.PushTaskService;
import com.labor.sync.security.WorkspaceContext;
import com.labor.sync.security.WorkspaceAccessService;
import com.labor.sync.security.SecurityUtils;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/labor/admin")
@PreAuthorize("hasAuthority('master:view')")
public class MasterDataController {
    private final ProjectRepository projectRepository;
    private final CompanyRepository companyRepository;
    private final TeamRepository teamRepository;
    private final PersonRepository personRepository;
    private final MasterDataVersionRepository versionRepository;
    private final ImportRowRepository importRowRepository;
    private final ImportService importService;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;
    private final MasterDataWriteService writeService;
    private final WorkspaceAccessService workspaceAccessService;
    private final PushTaskService pushTaskService;

    @GetMapping("/projects")
    public ApiResponse<PageResponse<ProjectView>> projects(@RequestParam(defaultValue = "") String search,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        List<String> allowed = workspaceAccessService.available(SecurityUtils.currentUsername()).stream()
                .map(WorkspaceAccessService.WorkspaceView::proCode).toList();
        Specification<LaborProject> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.notEqual(root.get("status"), MasterDataStatus.DISABLED));
            predicates.add(root.get("proCode").in(allowed));
            if (!search.isBlank()) predicates.add(cb.or(cb.like(root.get("proCode"), like(search)),
                    cb.like(root.get("projectName"), like(search))));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return ApiResponse.ok(PageResponse.from(projectRepository.findAll(spec, page(page, size)), ProjectView::from));
    }

    @GetMapping("/projects/{id}")
    public ApiResponse<ProjectView> project(@PathVariable Long id) {
        LaborProject project = projectRepository.findById(id).orElseThrow(() -> notFound("项目"));
        workspaceAccessService.resolve(SecurityUtils.currentUsername(), project.getProCode());
        return ApiResponse.ok(ProjectView.from(project));
    }

    @PostMapping("/projects")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ApiResponse<ProjectView> createProject(@Valid @RequestBody MasterDataWriteRequest.Project request) {
        return ApiResponse.ok(ProjectView.from(writeService.createProject(request)));
    }

    @PutMapping("/projects/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ApiResponse<ProjectView> updateProject(@PathVariable Long id,
                                                   @Valid @RequestBody MasterDataWriteRequest.Project request) {
        return ApiResponse.ok(ProjectView.from(writeService.updateProject(id, request)));
    }

    @DeleteMapping("/projects/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ApiResponse<Void> deleteProject(@PathVariable Long id) {
        writeService.deleteProject(id);
        return ApiResponse.ok();
    }

    @GetMapping("/companies")
    public ApiResponse<PageResponse<CompanyView>> companies(@RequestParam(defaultValue = "") String search,
                                                             @RequestParam(required = false) String proCode,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        Specification<LaborCompany> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.notEqual(root.get("status"), MasterDataStatus.DISABLED));
            if (!search.isBlank()) predicates.add(cb.or(cb.like(root.get("collCropCode"), like(search)), cb.like(root.get("companyName"), like(search))));
            predicates.add(cb.equal(root.get("proCode"), WorkspaceContext.proCode()));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return ApiResponse.ok(PageResponse.from(companyRepository.findAll(spec, page(page, size)), this::companyView));
    }

    @GetMapping("/companies/{id}")
    public ApiResponse<CompanyView> company(@PathVariable Long id) {
        LaborCompany item = companyRepository.findById(id).orElseThrow(() -> notFound("参建企业"));
        requireWorkspace(item.getProCode());
        return ApiResponse.ok(companyView(item));
    }

    @PostMapping("/companies")
    @PreAuthorize("hasAuthority('master:edit')")
    public ApiResponse<CompanyView> createCompany(@Valid @RequestBody MasterDataWriteRequest.Company request) {
        requireWorkspace(request.proCode());
        return ApiResponse.ok(companyView(writeService.createCompany(request)));
    }

    @PutMapping("/companies/{id}")
    @PreAuthorize("hasAuthority('master:edit')")
    public ApiResponse<CompanyView> updateCompany(@PathVariable Long id,
                                                   @Valid @RequestBody MasterDataWriteRequest.Company request) {
        requireWorkspace(request.proCode());
        return ApiResponse.ok(companyView(writeService.updateCompany(id, request)));
    }

    @DeleteMapping("/companies/{id}")
    @PreAuthorize("hasAuthority('master:edit')")
    public ApiResponse<Void> deleteCompany(@PathVariable Long id) {
        requireWorkspace(companyRepository.findById(id).orElseThrow(() -> notFound("参建企业")).getProCode());
        writeService.deleteCompany(id);
        return ApiResponse.ok();
    }

    @PutMapping("/companies/{id}/person-push-setting")
    @PreAuthorize("hasAuthority('master:edit')")
    @Transactional
    public ApiResponse<CompanyView> updateCompanyPersonPushSetting(
            @PathVariable Long id, @Valid @RequestBody CompanyPersonPushSettingWrite request) {
        LaborCompany item = companyRepository.findById(id).orElseThrow(() -> notFound("参建企业"));
        requireWorkspace(item.getProCode());
        LaborCompany updated = writeService.updateCompanyPersonPushSetting(id, request.enabled());
        pushTaskService.applyCompanyPersonPushSetting(updated);
        return ApiResponse.ok(companyView(updated));
    }

    @GetMapping("/teams")
    public ApiResponse<PageResponse<TeamView>> teams(@RequestParam(defaultValue = "") String search,
                                                      @RequestParam(required = false) String proCode,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        Specification<LaborTeam> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.notEqual(root.get("status"), MasterDataStatus.DISABLED));
            if (!search.isBlank()) predicates.add(cb.or(cb.like(root.get("teamId"), like(search)), cb.like(root.get("teamName"), like(search))));
            predicates.add(cb.equal(root.get("proCode"), WorkspaceContext.proCode()));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<LaborTeam> result = teamRepository.findAll(spec, page(page, size));
        Map<String, String> companyNames = companyRepository
                .findByProCodeAndStatusNotOrderByCompanyNameAsc(WorkspaceContext.proCode(), MasterDataStatus.DISABLED)
                .stream().collect(Collectors.toMap(LaborCompany::getCollCropCode, LaborCompany::getCompanyName,
                        (left, right) -> left));
        return ApiResponse.ok(new PageResponse<>(result.getContent().stream()
                .map(item -> teamView(item, companyNames.get(item.getCollCropCode()))).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @GetMapping("/teams/{id}")
    public ApiResponse<TeamView> team(@PathVariable Long id) {
        LaborTeam item = teamRepository.findById(id).orElseThrow(() -> notFound("施工队"));
        requireWorkspace(item.getProCode());
        String companyName = companyRepository.findByProCodeAndCollCropCode(item.getProCode(), item.getCollCropCode())
                .map(LaborCompany::getCompanyName).orElse(null);
        return ApiResponse.ok(teamView(item, companyName));
    }

    @PostMapping("/teams")
    @PreAuthorize("hasAuthority('master:edit')")
    public ApiResponse<TeamView> createTeam(@Valid @RequestBody MasterDataWriteRequest.Team request) {
        requireWorkspace(request.proCode());
        return ApiResponse.ok(teamView(writeService.createTeam(request)));
    }

    @PutMapping("/teams/{id}")
    @PreAuthorize("hasAuthority('master:edit')")
    public ApiResponse<TeamView> updateTeam(@PathVariable Long id,
                                             @Valid @RequestBody MasterDataWriteRequest.Team request) {
        requireWorkspace(request.proCode());
        return ApiResponse.ok(teamView(writeService.updateTeam(id, request)));
    }

    @DeleteMapping("/teams/{id}")
    @PreAuthorize("hasAuthority('master:edit')")
    public ApiResponse<Void> deleteTeam(@PathVariable Long id) {
        requireWorkspace(teamRepository.findById(id).orElseThrow(() -> notFound("施工队")).getProCode());
        writeService.deleteTeam(id);
        return ApiResponse.ok();
    }

    @GetMapping("/persons")
    @Transactional(readOnly = true)
    public ApiResponse<PageResponse<PersonView>> persons(@RequestParam(defaultValue = "") String search,
                                                          @RequestParam(required = false) String proCode,
                                                          @RequestParam(defaultValue = "") String collCropCode,
                                                          @RequestParam(defaultValue = "") String teamId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        List<LaborTeam> activeTeams = teamRepository
                .findByProCodeAndStatusNotOrderByTeamNameAsc(WorkspaceContext.proCode(), MasterDataStatus.DISABLED);
        List<String> companyTeamIds = collCropCode.isBlank() ? List.of() : activeTeams.stream()
                .filter(team -> collCropCode.equals(team.getCollCropCode()))
                .map(LaborTeam::getTeamId)
                .toList();
        Specification<LaborPerson> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.notEqual(root.get("status"), MasterDataStatus.DISABLED));
            if (!search.isBlank()) predicates.add(cb.or(cb.like(root.get("name"), like(search)), cb.like(root.get("hikPersonId"), like(search))));
            predicates.add(cb.equal(root.get("proCode"), WorkspaceContext.proCode()));
            if (!collCropCode.isBlank()) {
                predicates.add(companyTeamIds.isEmpty() ? cb.disjunction() : root.get("teamId").in(companyTeamIds));
            }
            if (!teamId.isBlank()) predicates.add(cb.equal(root.get("teamId"), teamId));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<LaborPerson> result = personRepository.findAll(spec, page(page, size));
        Map<String, LaborTeam> teams = activeTeams.stream()
                .collect(Collectors.toMap(LaborTeam::getTeamId, Function.identity(), (left, right) -> left));
        Map<String, String> companyNames = companyRepository
                .findByProCodeAndStatusNotOrderByCompanyNameAsc(WorkspaceContext.proCode(), MasterDataStatus.DISABLED)
                .stream().collect(Collectors.toMap(LaborCompany::getCollCropCode, LaborCompany::getCompanyName,
                        (left, right) -> left));
        return ApiResponse.ok(new PageResponse<>(result.getContent().stream().map(person -> {
            LaborTeam team = teams.get(person.getTeamId());
            return personView(person, team == null ? null : team.getTeamName(),
                    team == null ? null : team.getCollCropCode(),
                    team == null ? null : companyNames.get(team.getCollCropCode()));
        }).toList(), result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @GetMapping("/persons/filter-tree")
    @Transactional(readOnly = true)
    public ApiResponse<PersonFilterTreeNode> personFilterTree() {
        String proCode = WorkspaceContext.proCode();
        LaborProject project = projectRepository.findByProCode(proCode).orElseThrow(() -> notFound("项目"));
        List<LaborTeam> teams = teamRepository
                .findByProCodeAndStatusNotOrderByTeamNameAsc(proCode, MasterDataStatus.DISABLED);
        Map<String, List<LaborTeam>> teamsByCompany = teams.stream()
                .collect(Collectors.groupingBy(LaborTeam::getCollCropCode));
        List<PersonFilterTreeNode> companies = companyRepository
                .findByProCodeAndStatusNotOrderByCompanyNameAsc(proCode, MasterDataStatus.DISABLED)
                .stream().map(company -> new PersonFilterTreeNode(
                        "company:" + company.getCollCropCode(), "COMPANY", company.getCompanyName(), proCode,
                        company.getCollCropCode(), null,
                        teamsByCompany.getOrDefault(company.getCollCropCode(), List.of()).stream()
                                .map(team -> new PersonFilterTreeNode(
                                        "team:" + team.getTeamId(), "TEAM", team.getTeamName(), proCode,
                                        company.getCollCropCode(), team.getTeamId(), List.of()))
                                .toList()))
                .toList();
        return ApiResponse.ok(new PersonFilterTreeNode(
                "project:" + proCode, "PROJECT", project.getProjectName(), proCode, null, null, companies));
    }

    @GetMapping("/persons/{id}")
    @Transactional(readOnly = true)
    public ApiResponse<PersonView> person(@PathVariable Long id) {
        LaborPerson item = personRepository.findById(id).orElseThrow(() -> notFound("人员"));
        requireWorkspace(item.getProCode());
        return ApiResponse.ok(personView(item));
    }

    @PostMapping("/persons")
    @PreAuthorize("hasAuthority('master:edit')")
    public ApiResponse<PersonView> createPerson(@Valid @RequestBody MasterDataWriteRequest.Person request) {
        requireWorkspace(request.proCode());
        return ApiResponse.ok(personView(writeService.createPerson(request)));
    }

    @PutMapping("/persons/{id}")
    @PreAuthorize("hasAuthority('master:edit')")
    public ApiResponse<PersonView> updatePerson(@PathVariable Long id,
                                                 @Valid @RequestBody MasterDataWriteRequest.Person request) {
        requireWorkspace(request.proCode());
        return ApiResponse.ok(personView(writeService.updatePerson(id, request)));
    }

    @DeleteMapping("/persons/{id}")
    @PreAuthorize("hasAuthority('master:edit')")
    public ApiResponse<Void> deletePerson(@PathVariable Long id) {
        requireWorkspace(personRepository.findById(id).orElseThrow(() -> notFound("人员")).getProCode());
        writeService.deletePerson(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{entityType}/{id}/versions")
    public ApiResponse<List<VersionView>> versions(@PathVariable String entityType, @PathVariable Long id) {
        String type = switch (entityType) {
            case "projects" -> "PROJECT";
            case "companies" -> "COMPANY";
            case "teams" -> "TEAM";
            case "persons" -> "PERSON";
            default -> throw new BusinessException("ENTITY_TYPE_INVALID", "不支持的数据类型");
        };
        switch (entityType) {
            case "projects" -> workspaceAccessService.resolve(SecurityUtils.currentUsername(),
                    projectRepository.findById(id).orElseThrow(() -> notFound("项目")).getProCode());
            case "companies" -> requireWorkspace(companyRepository.findById(id).orElseThrow(() -> notFound("参建企业")).getProCode());
            case "teams" -> requireWorkspace(teamRepository.findById(id).orElseThrow(() -> notFound("施工队")).getProCode());
            case "persons" -> requireWorkspace(personRepository.findById(id).orElseThrow(() -> notFound("人员")).getProCode());
            default -> { }
        }
        return ApiResponse.ok(versionRepository.findByEntityTypeAndEntityIdOrderByVersionNoDesc(type, id)
                .stream().map(this::versionView).toList());
    }

    private PageRequest page(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    private String like(String value) {
        return "%" + value.trim() + "%";
    }

    private void requireWorkspace(String proCode) {
        if (!WorkspaceContext.proCode().equals(proCode)) {
            throw new BusinessException("WORKSPACE_DATA_MISMATCH", "数据不属于当前项目工作区", HttpStatus.FORBIDDEN);
        }
    }

    private BusinessException notFound(String name) {
        return new BusinessException("NOT_FOUND", name + "不存在", HttpStatus.NOT_FOUND);
    }

    private PersonView personView(LaborPerson person) {
        LaborTeam team = teamRepository.findByProCodeAndTeamId(person.getProCode(), person.getTeamId()).orElse(null);
        String companyName = team == null ? null : companyRepository
                .findByProCodeAndCollCropCode(person.getProCode(), team.getCollCropCode())
                .map(LaborCompany::getCompanyName).orElse(null);
        return personView(person, team == null ? null : team.getTeamName(),
                team == null ? null : team.getCollCropCode(), companyName);
    }

    private PersonView personView(LaborPerson person, String teamName, String collCropCode, String companyName) {
        return new PersonView(person.getId(), person.getName(), person.getIdcardType(),
                decrypt(person.getIdcardEncrypted()),
                person.getIdcardStartDate(), person.getIdcardEndDate(), person.getIdcardForever(),
                person.getProCode(), person.getTeamId(), teamName, collCropCode, companyName,
                person.getUserType(), person.getWorkType(),
                person.getEntryDate(), person.getExitDate(), person.getPoliticsStatus(), person.getEduLevel(),
                person.getMaritalStatus(), person.getSex(), person.getIdcardAddress(), person.getHomeAddress(),
                person.getBirthday(), person.getNation(), person.getCountryCode(), person.getProvinceCode(),
                present(person.getPositiveIdcardImageEncrypted()), present(person.getNegativeIdcardImageEncrypted()),
                present(person.getHeadImageEncrypted()),
                person.getMobile(), person.getTeamLeaderFlag(), person.getHikPersonId(),
                person.getInternalRemark(), person.getStatus(), person.getDataVersionNo(), person.getPublishedAt(), person.getUpdatedAt());
    }

    private CompanyView companyView(LaborCompany item) {
        return new CompanyView(item.getId(), item.getProCode(), item.getCollCropCode(), item.getCompanyName(),
                item.isPersonPushEnabled(), item.getCollCropType(), item.getChinaFlag(), item.getEntryDate(), item.getExitDate(),
                item.getContactName(), item.getContactIdType(), decrypt(item.getContactIdEncrypted()),
                item.getContactMobile(), item.getBlacklistFlag(), item.getInternalRemark(), item.getStatus(),
                item.getDataVersionNo(), item.getPublishedAt(), item.getUpdatedAt());
    }

    private TeamView teamView(LaborTeam item) {
        String companyName = companyRepository.findByProCodeAndCollCropCode(item.getProCode(), item.getCollCropCode())
                .map(LaborCompany::getCompanyName).orElse(null);
        return teamView(item, companyName);
    }

    private TeamView teamView(LaborTeam item, String companyName) {
        return new TeamView(item.getId(), item.getTeamId(), item.getProCode(), item.getCollCropCode(), item.getTeamType(),
                item.getTeamName(), companyName, item.getEntryDate(), item.getExitDate(), item.getLeaderName(), item.getLeaderIdType(),
                decrypt(item.getLeaderIdEncrypted()), item.getLeaderMobile(), item.getInternalRemark(),
                item.getStatus(), item.getDataVersionNo(), item.getPublishedAt(), item.getUpdatedAt());
    }

    private String decrypt(String encrypted) {
        return encrypted == null || encrypted.isBlank() ? null : cryptoService.decrypt(encrypted);
    }

    private boolean present(String encrypted) {
        return encrypted != null && !encrypted.isBlank();
    }

    private VersionView versionView(MasterDataVersion version) {
        String snapshot = version.getSnapshotJson();
        String changes = version.getChangedFieldsJson();
        if (version.getSourceBatchId() != null) {
            var row = importRowRepository.findFirstByBatchIdAndEntityTypeAndBusinessKey(
                    version.getSourceBatchId(), version.getEntityType(), version.getBusinessKey()).orElse(null);
            if (row != null) {
                Map<String, String> values = importService.displayNormalized(row);
                snapshot = json(values, snapshot);
                changes = revealChangedValues(changes, values);
            }
        }
        return new VersionView(version.getId(), version.getVersionNo(), snapshot, changes,
                version.getSourceBatchId(), version.getChangedBy(), version.getCreatedAt());
    }

    private String revealChangedValues(String json, Map<String, String> values) {
        if (json == null || json.isBlank() || !json.contains("*")) return json;
        try {
            Map<String, Map<String, Object>> changes = objectMapper.readValue(json, new TypeReference<>() {});
            changes.forEach((field, difference) -> difference.replaceAll((side, value) ->
                    value instanceof String text && text.contains("*") ? values.getOrDefault(field, text) : value));
            return objectMapper.writeValueAsString(changes);
        } catch (Exception ignored) {
            return json;
        }
    }

    private String json(Object value, String fallback) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public record ProjectView(Long id, String proCode, String projectName, String internalRemark,
                              MasterDataStatus status, int versionNo, Instant publishedAt, Instant updatedAt) {
        static ProjectView from(LaborProject item) {
            return new ProjectView(item.getId(), item.getProCode(), item.getProjectName(), item.getInternalRemark(),
                    item.getStatus(), item.getDataVersionNo(), item.getPublishedAt(), item.getUpdatedAt());
        }
    }

    public record CompanyView(Long id, String proCode, String collCropCode, String companyName,
                              boolean personPushEnabled, String collCropType,
                              String chinaFlag, LocalDate entryDate, LocalDate exitDate, String contactName,
                              String contactIdType, String contactIdNumber, String contactMobile,
                              String blacklistFlag, String internalRemark, MasterDataStatus status, int versionNo,
                              Instant publishedAt, Instant updatedAt) {
    }

    public record TeamView(Long id, String teamId, String proCode, String collCropCode, String teamType,
                           String teamName, String companyName, LocalDate entryDate, LocalDate exitDate, String leaderName,
                           String leaderIdType, String leaderIdNumber, String leaderMobile,
                           String internalRemark, MasterDataStatus status, int versionNo,
                           Instant publishedAt, Instant updatedAt) {
    }

    public record PersonView(Long id, String name, String idcardType, String idcardNumber,
                             LocalDate idcardStartDate, LocalDate idcardEndDate, String idcardForever,
                             String proCode, String teamId, String teamName, String collCropCode, String companyName,
                             String userType, String workType,
                             LocalDate entryDate, LocalDate exitDate, String politicsStatus, String eduLevel,
                             String maritalStatus, String sex, String idcardAddress, String homeAddress,
                             LocalDate birthday, String nation, String countryCode, String provinceCode,
                             boolean positiveIdcardImagePresent, boolean negativeIdcardImagePresent,
                             boolean headImagePresent,
                             String mobile, String teamLeaderFlag, String hikPersonId,
                             String internalRemark, MasterDataStatus status, int versionNo,
                             Instant publishedAt, Instant updatedAt) {}

    public record PersonFilterTreeNode(String key, String type, String label, String proCode,
                                       String collCropCode, String teamId,
                                       List<PersonFilterTreeNode> children) {}

    public record CompanyPersonPushSettingWrite(@NotNull Boolean enabled) {}

    public record VersionView(Long id, int versionNo, String snapshotJson, String changedFieldsJson,
                              Long sourceBatchId, String changedBy, Instant createdAt) {}
}
