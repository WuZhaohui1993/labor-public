package com.labor.sync.attendance;

import com.labor.sync.masterdata.CompanyRepository;
import com.labor.sync.masterdata.LaborCompany;
import com.labor.sync.masterdata.LaborPerson;
import com.labor.sync.masterdata.LaborTeam;
import com.labor.sync.masterdata.MasterDataStatus;
import com.labor.sync.masterdata.PersonRepository;
import com.labor.sync.masterdata.TeamRepository;
import com.labor.sync.push.PushTaskRepository;
import com.labor.sync.push.PushTaskStatus;
import com.labor.sync.push.PushTaskType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AttendanceBaselineService {
    private final PersonRepository personRepository;
    private final TeamRepository teamRepository;
    private final CompanyRepository companyRepository;
    private final PushTaskRepository taskRepository;

    @Transactional(readOnly = true)
    public List<BaselinePerson> eligiblePeople(String proCode, LocalDate date) {
        return eligibleOn(pushedPeople(proCode), date);
    }

    public List<BaselinePerson> eligibleOn(List<BaselinePerson> people, LocalDate date) {
        return people.stream()
                .filter(person -> activeOn(person.personEntryDate(), person.personExitDate(), date))
                .filter(person -> activeOn(person.teamEntryDate(), person.teamExitDate(), date))
                .filter(person -> activeOn(person.companyEntryDate(), person.companyExitDate(), date))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BaselinePerson> pushedPeople(String proCode) {
        Set<Long> pushedIds = taskRepository.findSuccessfulAggregateIds(
                        proCode, PushTaskType.PERSON, PushTaskStatus.SUCCESS).stream()
                .map(this::parseId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (pushedIds.isEmpty()) return List.of();

        Map<String, LaborCompany> companies = companyRepository
                .findByProCodeAndStatusNotOrderByCompanyNameAsc(proCode, MasterDataStatus.DISABLED).stream()
                .filter(company -> company.getStatus() == MasterDataStatus.PUBLISHED)
                .filter(LaborCompany::isPersonPushEnabled)
                .collect(Collectors.toMap(LaborCompany::getCollCropCode, company -> company,
                        BinaryOperator.maxBy(Comparator.comparing(LaborCompany::getId))));
        Map<String, LaborTeam> teams = teamRepository
                .findByProCodeAndStatusNotOrderByTeamNameAsc(proCode, MasterDataStatus.DISABLED).stream()
                .filter(team -> team.getStatus() == MasterDataStatus.PUBLISHED)
                .filter(team -> companies.containsKey(team.getCollCropCode()))
                .collect(Collectors.toMap(LaborTeam::getTeamId, team -> team,
                        BinaryOperator.maxBy(Comparator.comparing(LaborTeam::getId))));

        return personRepository.findAllById(pushedIds).stream()
                .filter(person -> proCode.equals(person.getProCode()))
                .filter(person -> person.getStatus() == MasterDataStatus.PUBLISHED)
                .filter(person -> teams.containsKey(person.getTeamId()))
                .map(person -> baselinePerson(person, teams.get(person.getTeamId()), companies))
                .sorted(Comparator.comparing(BaselinePerson::companyName)
                        .thenComparing(BaselinePerson::teamName)
                        .thenComparing(BaselinePerson::personName))
                .toList();
    }

    private BaselinePerson baselinePerson(LaborPerson person, LaborTeam team,
                                          Map<String, LaborCompany> companies) {
        LaborCompany company = companies.get(team.getCollCropCode());
        return new BaselinePerson(person.getId(), person.getName(), person.getHikPersonId(), person.getWorkType(),
                person.getEntryDate(), person.getExitDate(), team.getTeamId(), team.getTeamName(),
                team.getEntryDate(), team.getExitDate(), company.getCollCropCode(), company.getCompanyName(),
                company.getEntryDate(), company.getExitDate());
    }

    private boolean activeOn(LocalDate start, LocalDate end, LocalDate date) {
        return (start == null || !date.isBefore(start)) && (end == null || !date.isAfter(end));
    }

    private Long parseId(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public record BaselinePerson(Long personId, String personName, String hikPersonId, String workType,
                                 LocalDate personEntryDate, LocalDate personExitDate,
                                 String teamId, String teamName,
                                 LocalDate teamEntryDate, LocalDate teamExitDate,
                                 String companyCode, String companyName,
                                 LocalDate companyEntryDate, LocalDate companyExitDate) {
    }
}
