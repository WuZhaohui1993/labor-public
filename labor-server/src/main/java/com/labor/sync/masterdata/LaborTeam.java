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
@Table(name = "labor_team")
public class LaborTeam extends MasterDataEntity {
    @Column(name = "team_id", nullable = false, length = 100)
    private String teamId;
    @Column(name = "pro_code", nullable = false, length = 100)
    private String proCode;
    @Column(name = "coll_crop_code", nullable = false, length = 64)
    private String collCropCode;
    @Column(name = "team_type", nullable = false, length = 64)
    private String teamType;
    @Column(name = "team_name", nullable = false, length = 200)
    private String teamName;
    @Column(name = "entry_date")
    private LocalDate entryDate;
    @Column(name = "exit_date")
    private LocalDate exitDate;
    @Column(name = "leader_name", length = 100)
    private String leaderName;
    @Column(name = "leader_id_type", length = 64)
    private String leaderIdType;
    @Column(name = "leader_id_encrypted", columnDefinition = "text")
    private String leaderIdEncrypted;
    @Column(name = "leader_mobile", length = 32)
    private String leaderMobile;
    @Column(name = "internal_remark", length = 1000)
    private String internalRemark;
}
