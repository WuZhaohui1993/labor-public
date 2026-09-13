package com.labor.sync.masterdata;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public final class MasterDataWriteRequest {
    private MasterDataWriteRequest() {}

    public record Project(
            @NotBlank @Size(max = 100) String proCode,
            @NotBlank @Size(max = 200) String projectName,
            @Size(max = 1000) String internalRemark) {}

    public record Company(
            @NotBlank @Size(max = 100) String proCode,
            @NotBlank @Size(max = 64) String collCropCode,
            @NotBlank @Size(max = 200) String companyName,
            @NotBlank @Size(max = 64) String collCropType,
            @NotBlank @Pattern(regexp = "[YN]") String chinaFlag,
            LocalDate entryDate,
            LocalDate exitDate,
            @Size(max = 100) String contactName,
            @Size(max = 64) String contactIdType,
            @Size(max = 100) String contactIdNumber,
            @Pattern(regexp = "^$|[0-9+ -]{7,20}$") String contactMobile,
            @Pattern(regexp = "^$|[YN]$") String blacklistFlag,
            @Size(max = 1000) String internalRemark) {}

    public record Team(
            @NotBlank @Size(max = 100) String teamId,
            @NotBlank @Size(max = 100) String proCode,
            @NotBlank @Size(max = 64) String collCropCode,
            @NotBlank @Size(max = 64) String teamType,
            @NotBlank @Size(max = 200) String teamName,
            LocalDate entryDate,
            LocalDate exitDate,
            @Size(max = 100) String leaderName,
            @Size(max = 64) String leaderIdType,
            @Size(max = 100) String leaderIdNumber,
            @Pattern(regexp = "^$|[0-9+ -]{7,20}$") String leaderMobile,
            @Size(max = 1000) String internalRemark) {}

    public record Person(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 64) String idcardType,
            @Size(max = 100) String idcardNumber,
            LocalDate idcardStartDate,
            LocalDate idcardEndDate,
            @NotBlank @Pattern(regexp = "[YN]") String idcardForever,
            @NotBlank @Size(max = 100) String proCode,
            @NotBlank @Size(max = 100) String teamId,
            @NotBlank @Size(max = 64) String userType,
            @NotBlank @Size(max = 64) String workType,
            LocalDate entryDate,
            LocalDate exitDate,
            @Size(max = 64) String politicsStatus,
            @Size(max = 64) String eduLevel,
            @Size(max = 64) String maritalStatus,
            @Pattern(regexp = "^$|[MF]$") String sex,
            @Size(max = 500) String idcardAddress,
            @Size(max = 500) String homeAddress,
            LocalDate birthday,
            @Size(max = 64) String nation,
            @Size(max = 64) String countryCode,
            @Size(max = 64) String provinceCode,
            @Size(max = 4500000) String positiveIdcardImage,
            @Size(max = 4500000) String negativeIdcardImage,
            @Size(max = 4500000) String headImage,
            Boolean clearPositiveIdcardImage,
            Boolean clearNegativeIdcardImage,
            Boolean clearHeadImage,
            @Pattern(regexp = "^$|[0-9+ -]{7,20}$") String mobile,
            @Pattern(regexp = "^$|[YN]$") String teamLeaderFlag,
            @Size(max = 100) String hikPersonId,
            @Size(max = 1000) String internalRemark) {
        public Person(String name, String idcardType, String idcardNumber, LocalDate idcardStartDate,
                      LocalDate idcardEndDate, String idcardForever, String proCode, String teamId,
                      String userType, String workType, LocalDate entryDate, LocalDate exitDate,
                      String sex, LocalDate birthday, String mobile, String teamLeaderFlag,
                      String hikPersonId, String internalRemark) {
            this(name, idcardType, idcardNumber, idcardStartDate, idcardEndDate, idcardForever,
                    proCode, teamId, userType, workType, entryDate, exitDate,
                    null, null, null, sex, null, null, birthday, null, null, null,
                    null, null, null, false, false, false, mobile, teamLeaderFlag, hikPersonId, internalRemark);
        }
    }
}
