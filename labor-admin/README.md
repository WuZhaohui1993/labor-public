# labor-admin

劳务实名制数据同步管理中心前端，基于 MIT 许可的 [vue-pure-admin 7.0.0](https://github.com/pure-admin/vue-pure-admin/releases/tag/v7.0.0) 初始化。

业务路由仅加载工作台、导入复核、基础台账、接口配置、用户角色和操作审计。生产构建路径为 `/labor-admin/`，接口前缀为 `/api/labor/admin`。

```bash
pnpm install --frozen-lockfile
pnpm typecheck
pnpm build
```

