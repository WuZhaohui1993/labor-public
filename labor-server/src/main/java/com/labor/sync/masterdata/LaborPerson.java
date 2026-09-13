package com.labor.sync.masterdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "labor_person")
public class LaborPerson extends MasterDataEntity {
    @Column(nullable = false, length = 100)
    private String name;
    @Column(name = "idcard_type", nullable = false, length = 64)
    private String idcardType;
    @Column(name = "idcard_encrypted", nullable = false, columnDefinition = "text")
    private String idcardEncrypted;
    @Column(name = "idcard_hash", nullable = false, unique = true, length = 64)
    private String idcardHash;
    @Column(name = "idcard_start_date")
    private LocalDate idcardStartDate;
    @Column(name = "idcard_end_date")
    private LocalDate idcardEndDate;
    @Column(name = "idcard_forever", nullable = false, length = 1)
    private String idcardForever;
    @Column(name = "pro_code", nullable = false, length = 100)
    private String proCode;
    @Column(name = "team_id", nullable = false, length = 100)
    private String teamId;
    @Column(name = "user_type", nullable = false, length = 64)
    private String userType;
    @Column(name = "work_type", nullable = false, length = 64)
    private String workType;
    @Column(name = "entry_date")
    private LocalDate entryDate;
    @Column(name = "exit_date")
    private LocalDate exitDate;
    @Column(name = "politics_status", length = 64)
    private String politicsStatus;
    @Column(name = "edu_level", length = 64)
    private String eduLevel;
    @Column(name = "marital_status", length = 64)
    private String maritalStatus;
    @Column(length = 1)
    private String sex;
    @Column(name = "idcard_address", length = 500)
    private String idcardAddress;
    @Column(name = "home_address", length = 500)
    private String homeAddress;
    private LocalDate birthday;
    @Column(length = 64)
    private String nation;
    @Column(name = "country_code", length = 64)
    private String countryCode;
    @Column(name = "province_code", length = 64)
    private String provinceCode;
    @Column(name = "positive_idcard_image_encrypted", columnDefinition = "longtext")
    private String positiveIdcardImageEncrypted;
    @Column(name = "negative_idcard_image_encrypted", columnDefinition = "longtext")
    private String negativeIdcardImageEncrypted;
    @Column(name = "head_image_encrypted", columnDefinition = "longtext")
    private String headImageEncrypted;
    @Column(length = 32)
    private String mobile;
    @Column(name = "team_leader_flag", length = 1)
    private String teamLeaderFlag;
    @Column(name = "hik_person_id", length = 100)
    private String hikPersonId;
    @Column(name = "internal_remark", length = 1000)
    private String internalRemark;
}
