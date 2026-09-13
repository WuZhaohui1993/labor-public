package com.labor.sync.masterdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "labor_project")
public class LaborProject extends MasterDataEntity {
    @Column(name = "pro_code", nullable = false, unique = true, length = 100)
    private String proCode;
    @Column(name = "project_name", nullable = false, length = 200)
    private String projectName;
    @Column(name = "internal_remark", length = 1000)
    private String internalRemark;
}

