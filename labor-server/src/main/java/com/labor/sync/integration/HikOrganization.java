package com.labor.sync.integration;

public record HikOrganization(String orgIndexCode, String orgName, String parentOrgIndexCode,
                              String orgPath, int sortOrder) {
}
