# 劳务实名制系统

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE) [![Vue 3](https://img.shields.io/badge/Vue-3.x-42b883.svg)](https://vuejs.org/) [![Java 17](https://img.shields.io/badge/Java-17%2B-ed8b00.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)

面向项目工作区的劳务主数据、考勤采集、人员匹配、数据推送和审计记录管理系统。

> 本项目只保留通用源码和 Mock 适配器，不包含人员、考勤、身份证、照片、生产数据库、平台凭据、设备地址或项目交付资料。真实海康和劳务平台接口需要在目标环境单独验收。

## 功能

- **项目工作区**：项目、企业、班组和人员台账按工作区隔离。
- **数据导入**：Excel 导入、校验、复核、发布和错误反馈。
- **考勤管理**：采集、匹配、统计、补全、复核和按日查询。
- **平台推送**：依赖顺序、幂等任务、失败补偿、重试和调用日志。
- **权限与审计**：角色、菜单、项目成员、操作日志和敏感字段加密。
- **Mock 联调**：本地可使用 Mock 网关验证采集、匹配和推送流程。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 管理端 | Vue 3、TypeScript、Vite、Element Plus、Pinia |
| 后端 | Java 17、Spring Boot、Flyway、JPA、Maven |
| 基础设施 | MySQL、Redis、可选 MQTT/HTTP 集成 |

## 快速开始

### 环境要求

Git、JDK 17、Maven、Node.js、pnpm、MySQL 8+ 和 Redis 6+。

### 配置与启动

从 `labor-server/.env.example`（如需自建）准备本地环境变量，填写数据库、Token、加密密钥和 Mock 开关。真实平台凭据只能通过本地环境提供。

```bash
cd labor-server
./mvnw spring-boot:run

cd ../labor-admin
pnpm install
pnpm dev
```

服务端默认使用本地 Mock 适配器。海康 Artemis SDK 未随仓库分发，启用真实适配器前需自行取得授权 SDK。

## 测试

```bash
cd labor-server
JAVA_HOME=/path/to/jdk-17 ./mvnw -DskipTests package
```

完整测试需要专用 JDBC 测试数据库和外部适配器配置；公开副本不包含生产数据或恢复快照。

## 项目结构

```text
├── labor-admin/      # Vue 管理端
├── labor-server/     # Spring Boot 后端与迁移脚本
├── LICENSE
└── THIRD_PARTY_NOTICES.md
```

## 安全边界

人员身份证、照片、联系方式、考勤记录、平台 Token 和设备凭据必须加密存储并通过环境变量注入。生产环境还需配置 HTTPS、权限审计、数据保留和备份恢复。

## 参与贡献

请保持工作区权限、导入校验和推送幂等行为的一致性；不要提交真实人员数据、设备资料、生产配置、凭据或构建产物。提交 PR 时说明实际执行的检查。

## 许可证

本项目及其自有代码采用 MIT 许可证。RuoYi、vue-pure-admin、海康 SDK 和其他依赖的许可证边界见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
