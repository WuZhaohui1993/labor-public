package com.labor.sync.imports;

public enum ImportScope {
    ALL(null, null, "综合"),
    PROJECT("PROJECT", "项目基本信息", "项目"),
    COMPANY("COMPANY", "参建企业", "参建企业"),
    TEAM("TEAM", "施工队", "施工队"),
    PERSON("PERSON", "人员信息", "人员");

    private final String entityType;
    private final String sheetName;
    private final String displayName;

    ImportScope(String entityType, String sheetName, String displayName) {
        this.entityType = entityType;
        this.sheetName = sheetName;
        this.displayName = displayName;
    }

    public boolean includes(String type) {
        return this == ALL || entityType.equals(type);
    }

    public String sheetName() {
        return sheetName;
    }

    public String displayName() {
        return displayName;
    }
}
