package com.labor.sync.hik;

import com.labor.sync.common.BaseEntity;
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
@Table(name = "hik_organization_mapping")
public class HikOrganizationMapping extends BaseEntity {
    @Column(name = "pro_code", nullable = false, length = 100)
    private String proCode;
    @Column(name = "org_index_code", nullable = false, length = 100)
    private String orgIndexCode;
    @Column(name = "org_name", length = 200)
    private String orgName;
    @Column(name = "org_path", length = 1000)
    private String orgPath;
    @Column(name = "mapping_source", nullable = false, length = 20)
    private String mappingSource = "MANUAL";
    @Column(name = "include_children", nullable = false)
    private boolean includeChildren;
    @Column(nullable = false)
    private boolean enabled = true;
}
