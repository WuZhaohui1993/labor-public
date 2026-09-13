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
@Table(name = "labor_company")
public class LaborCompany extends MasterDataEntity {
    @Column(name = "pro_code", nullable = false, length = 100)
    private String proCode;
    @Column(name = "coll_crop_code", nullable = false, length = 64)
    private String collCropCode;
    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;
    @Column(name = "person_push_enabled", nullable = false)
    private boolean personPushEnabled = true;
    @Column(name = "coll_crop_type", nullable = false, length = 64)
    private String collCropType;
    @Column(name = "china_flag", nullable = false, length = 1)
    private String chinaFlag;
    @Column(name = "entry_date")
    private LocalDate entryDate;
    @Column(name = "exit_date")
    private LocalDate exitDate;
    @Column(name = "contact_name", length = 100)
    private String contactName;
    @Column(name = "contact_id_type", length = 64)
    private String contactIdType;
    @Column(name = "contact_id_encrypted", columnDefinition = "text")
    private String contactIdEncrypted;
    @Column(name = "contact_mobile", length = 32)
    private String contactMobile;
    @Column(name = "blacklist_flag", length = 1)
    private String blacklistFlag;
    @Column(name = "internal_remark", length = 1000)
    private String internalRemark;
}
