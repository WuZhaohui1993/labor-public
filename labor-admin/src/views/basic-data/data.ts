export interface DictionaryOption {
  value: string;
  label: string;
}

const option = (value: string, label: string): DictionaryOption => ({
  value,
  label
});

export const companyTypes = [
  option("ZONGCHENGBAO_CANJIAN", "工程总承包"),
  option("SHIGONGZONGCHENGBO_CANJIAN", "施工（总）承包"),
  option("SHIGONG_FENBAO_CANJIAN", "施工分包"),
  option("ZHUANYE_CANJIAN", "专业分包"),
  option("LAOWU_CANJIAN", "劳务分包"),
  option("HOUQIN_CANJIAN", "后勤服务"),
  option("QITA_CANJIAN", "其他")
];

export const idTypes = [
  option("SHENFEN_ZHENGJIAN", "身份证"),
  option("JUNGUAN_ZHENGJIAN", "军官证"),
  option("HUZHAO_ZHENGJIAN", "护照"),
  option("TAIWAN_ZHENGJIAN", "台湾居民身份证"),
  option("HONGKONG_ZHENGJIAN", "香港永久性身份证"),
  option("JINGGUAN_ZHENGJIAN", "警官证"),
  option("QITA_ZHENGJIAN", "其他")
];

export const teamTypes = [
  option("CANJIAN_TEAM", "参建单位类"),
  option("ZHISHU_TEAM", "项目直属类"),
  option("WAIPIN_TEAM", "项目外聘类"),
  option("TEAM_OTHER", "其他")
];

export const userTypes = [
  option("LAB_USER_MANAGE", "管理人员"),
  option("LAB_USER_BULIDER", "施工人员"),
  option("LAB_USER_OTHER", "其他")
];

export const workTypes = [
  option("WORK_TYPE_GLRY", "管理人员"),
  option("WORK_TYPE_GJG", "钢筋工"),
  option("WORK_TYPE_MG", "木工"),
  option("WORK_TYPE_WG", "瓦工"),
  option("WORK_TYPE_JZG", "架子工"),
  option("WORK_TYPE_DG", "电工"),
  option("WORK_TYPE_HG1", "焊工"),
  option("WORK_TYPE_QG", "钳工"),
  option("WORK_TYPE_MAOG", "铆工"),
  option("WORK_TYPE_QZG", "起重工"),
  option("WORK_TYPE_YBG", "仪表工"),
  option("WORK_TYPE_FFG", "防腐保温通风工"),
  option("WORK_TYPE_PG", "普工"),
  option("WORK_TYPE_BA", "保安"),
  option("WORK_TYPE_CLRY", "测量工"),
  option("WORK_TYPE_JSRY", "机上人员"),
  option("WORK_TYPE_JCRY", "检测工"),
  option("WORK_TYPE_HQRY", "后勤人员"),
  option("WORK_TYPE_GG", "管工"),
  option("WORK_TYPE_HNTG", "混凝工"),
  option("WORK_TYPE_WXDG", "维修工"),
  option("WORK_TYPE_YBTSG", "调试工"),
  option("WORK_TYPE_OTHER", "其他")
];

export const yesNoOptions = [option("Y", "是"), option("N", "否")];
export const sexOptions = [option("M", "男"), option("F", "女")];

export const educationLevels = [
  option("EDU_LEVEL_PRIMARY", "小学"),
  option("EDU_LEVEL_MIDDLE", "初中"),
  option("EDU_LEVEL_HIGH", "高中"),
  option("EDU_LEVEL_TECHNICAL", "中专"),
  option("EDU_LEVEL_JUNIOR", "大专"),
  option("EDU_LEVEL_BACHELOR", "本科"),
  option("EDU_LEVEL_MASTER", "硕士"),
  option("EDU_LEVEL_DOCTORATE", "博士"),
  option("EDU_LEVEL_OTHER", "其他")
];

export const maritalStatuses = [
  option("UNMARRIED", "未婚"),
  option("MARRIED", "已婚"),
  option("DIVORCED", "离异"),
  option("WIDOWED", "丧偶")
];
