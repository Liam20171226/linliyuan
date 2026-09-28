# 04 · 第一阶段 · API 接口清单（一阶段 = D 全部）

| 项 | 说明 |
|---|---|
| 文档编号 | 04 |
| 文档类型 | API 接口清单（**一阶段完整**；覆盖 D.1～D.4） |
| 文档状态 | **须对照实现修订；待正式上线** · 冲突以 01 + 本附录为准 |
| 对应需求 | [01-需求规格说明书-V1.2.md](./01-需求规格说明书-V1.2.md)（①；一阶段=D） |
| 对应选型 | [02-技术选型说明书.md](./02-技术选型说明书.md)（②） |
| 对应模型 | [03-第一阶段-数据模型设计说明书.md](./03-第一阶段-数据模型设计说明书.md)（③） |
| 文档链 | ①01 → ②02 → ③03 → ④04 → 编码（冲突以 01 为准） |
| 文档结构 | **第一部分 D.1**；**第二部分 D.2～D.4**（均已写至可编码粒度；01-Q18） |
| 更新日期 | 2026-09-28 |

**阶段说明：** 产品仅 **一阶段 / 二阶段**。D.1～D.4 是一阶段内模块实施顺序，**不是**「批次」。本文 API 覆盖整个一阶段；编码时仍按 D.1→D.4 顺序实现。

---

## 实现对照附录（2026-09-28）— 上线必读

正文 §11.2 / §11.4 / §11.5 等与代码有差异时，**以本附录与 Controller 为准**。联调放行见 [05](./05-上线联调清单.md)。

### A. 公共收益（§11.2）— 已收口（2026-09-28）

`POST /staff/public-revenue/items` → 直接 `PUBLISHED`。  
另有 `PUT/DELETE /staff/...`、`GET /staff|resident|committee|platform/...`。  
**无** confirm/reject。小程序只读；管理端财务 Tab 可登记。

### B. 报修 / 投诉（§11.4～11.5）— 已增强

路径前缀仍多为 `/resident|staff|platform/repairs|complaints`，实现落在 `ServiceTicketController`（统一 `service_ticket`）。相对原文增补：

| 方法 | 说明 |
|---|---|
| `POST .../claim` | 一线岗接单 |
| `GET /staff/repairs/mine|done|pool` | 我的 / 已办 / 池 |
| 评价 / 答复改删 | 多维评价与答复附件维护 |
| `GET /committee/tickets` | 业委会只读工单 |

### C. 巡检（文档正文未列，已实现）

| 方法 | 说明 |
|---|---|
| `GET/POST/PUT/DELETE /staff/inspect/spots` | 点位 |
| `.../spots/{id}/qr` · `.../spots/qr-batch` | 二维码 |
| `GET/POST/PUT /staff/inspect/plans` · `publish` / `cancel` | 计划 |
| `GET /staff/inspect/jobs` · `claim` / `scan` | 任务接单与扫码 |
| `GET /staff/inspect/photos` | 巡检照片 |

### D. 其它已实现控制器（查阅源码）

`AuthController` · `PlatformController` · `StaffController` · `ResidentController` · `BillingController` · `PrepaidController` · `FinanceController` · `NoticeController` · `VoteController` · `OccupantController` · `TodoController` · `AttachmentController` · `CommunityApplicationController` · `AboutUsController` · `HealthController`

经营收支与财务汇总仍在 `FinanceController`（`/staff|resident|committee|platform/finance/...`）。

---

## 0. 约定

### 0.1 基址与协议

- 协议：HTTPS + JSON（`Content-Type: application/json`）
- 基址示例：`/api/v1`
- 鉴权：`Authorization: Bearer <JWT>`（除「登录/微信 code 换会话」外均需）
- JWT 载荷必含：`userId`；登录后切换身份时再含 `identityType`、`communityId`；住户身份另含 `roomId`（可空表示未选房）

### 0.2 统一响应

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

| code | 含义 |
|---|---|
| 0 | 成功 |
| 400xx | 参数/业务校验失败（如手机号冲突、一房已有业主） |
| 401xx | 未登录 / 令牌无效 |
| 403xx | 无权限（身份/小区不符） |
| 404xx | 资源不存在 |
| 500xx | 服务器错误 |

列表分页统一查询参数：`page`（从 1）、`pageSize`（默认 20，最大 100）。  
列表 `data`：`{ "list": [], "total": 0, "page": 1, "pageSize": 20 }`。

### 0.3 角色缩写（权限列）

| 缩写 | 含义 |
|---|---|
| 游客 | 已登录、无有效住户绑定时可出现 |
| 住户 | OWNER / OWNER_MEMBER / TENANT / TENANT_MEMBER |
| 业委会 | COMMITTEE |
| 物业 | CUSTOMER_SERVICE / PROPERTY_MANAGER |
| 平台 | PLATFORM（仅 Web） |

### 0.4 身份证脱敏

业委会看住户列表：`idCardNo` 返回 **前 4 + 后 4，中间 `*`**；物业/平台返回明文。

### 0.5 附件

- 单文件 ≤ 10MB；同一业务单最多 3 个  
- 允许：`jpg` / `png` / `pdf`  
- 一阶段存储：本地 `uploads/`（预留上云接口）

---

## 1. 认证与会话

### 1.1 平台 Web 登录

| 项 | 内容 |
|---|---|
| 方法路径 | `POST /auth/platform/login` |
| 权限 | 匿名 |
| 请求 | `{ "username": "admin", "password": "..." }` |
| 响应 | `{ "token": "...", "user": { "id", "username", "isPlatformAdmin": true } }` |
| 说明 | 仅 `is_platform_admin=1`；成功后 JWT 可带 `identityType=PLATFORM` |

### 1.2 平台改密

| 项 | 内容 |
|---|---|
| 方法路径 | `POST /auth/platform/change-password` |
| 权限 | 平台 |
| 请求 | `{ "oldPassword", "newPassword" }` |

### 1.3 物业 Web 登录

| 项 | 内容 |
|---|---|
| 方法路径 | `POST /auth/staff/login` |
| 权限 | 匿名 |
| 请求 | `{ "mobile", "password" }` |
| 响应 | `{ "token", "mustChangePassword": true/false, "user": { "id", "mobile", "realName" }, "communities": [{ "communityId", "communityName", "staffRole" }] }` |
| 说明 | 须已有有效 `staff_community`。若 `mustChangePassword=true`（平台下发的临时密码），须先调 §1.3a 改密，再调「切换身份」；未改密时访问 `/staff/**` 或 `context/switch` 返回 `40302` |

### 1.3a 物业 Web：首次修改临时密码

| 项 | 内容 |
|---|---|
| 方法路径 | `POST /auth/staff/change-password` |
| 权限 | 已登录（物业登录后的 token，尚未切换身份亦可） |
| 请求 | `{ "oldPassword", "newPassword" }`（新密码 ≥6 且不可与临时密码相同） |
| 响应 | `{ "mustChangePassword": false }` |
| 说明 | 清除 `sys_user.must_change_password`；之后方可进入物业后台 |

### 1.3b App：手机号密码登录

| 项 | 内容 |
|---|---|
| 方法路径 | `POST /auth/app/login` |
| 权限 | 匿名 |
| 请求 | `{ "mobile", "password" }` |
| 响应 | `{ "token", "mustChangePassword", "needChooseIdentity", "user", "identities", "current?" }` |
| 说明 | 与 Web 共用 `password_hash`；住户 / 业委会 / 物业（含一线岗）可登；JWT 标 `appPasswordSession`；平台账号禁止；无小区身份禁止。`mustChangePassword=true` 时须先改密 |

### 1.3c App：修改临时密码

| 项 | 内容 |
|---|---|
| 方法路径 | `POST /auth/app/change-password` |
| 权限 | 已登录（App 密码会话） |
| 请求 | `{ "oldPassword", "newPassword" }` |
| 响应 | `{ "mustChangePassword": false }` |

### 1.3d 物业 Web：为小区用户设 App 临时密码

| 项 | 内容 |
|---|---|
| 方法路径 | `POST /staff/users/{userId}/reset-app-password` |
| 权限 | 物业（当前小区） |
| 请求 | `{ "newPassword?" }`（可空 → 系统默认临时密码） |
| 响应 | `{ "userId", "mobile", "mustChangePassword": true, "usedDefaultPassword", "tempPassword?" }` |
| 说明 | 用户须为本小区住户 / 业委会 / 物业任职；写入 `password_hash` 并置须改密 |

### 1.4 小程序：code 换 openid

| 项 | 内容 |
|---|---|
| 方法路径 | `POST /auth/miniapp/code2session` |
| 权限 | 匿名 |
| 请求 | `{ "code": "wx.login 的 code" }` |
| 响应 | `{ "sessionToken", "openidBound", "needPhoneAuth": true }`（尚未完成手机号匹配时）或已绑定用户的完整登录态 |
| 说明 | 解析 openid；若已有 `user_wechat` 且用户已有 mobile，可直接返回正式 `token` + `identities`；否则返回短期 `sessionToken`，**必须再调 1.5 手机号授权匹配** |

### 1.5 小程序：微信手机号授权并匹配身份（首次必走）

| 项 | 内容 |
|---|---|
| 方法路径 | `POST /auth/miniapp/phone-match` |
| 权限 | 匿名（携带 1.4 的 `sessionToken`）或已登录补绑 |
| 请求 | `{ "sessionToken?", "phoneCode": "getPhoneNumber 返回的 code" }`（服务端向微信换真实手机号） |
| 响应 | `{ "token", "user": { "id", "mobile", "realName?" }, "identities": [], "needChooseIdentity": true/false }` |
| 匹配规则（对齐 01-Q16） | 用手机号匹配住户 / 业委会 / 物业任职；多种 → `needChooseIdentity=true` 且 `identities` 列出可选；仅一种 → 直接可用；无匹配 → 仅 `GUEST`；建/绑 `sys_user`+`user_wechat`；手机号冲突（属其他 openid）→ `40002` |
| 说明 | **禁止**在未完成手机号授权匹配前进入身份选择页 |

### 1.6 物业小程序密码绑定（已废弃）

| 项 | 内容 |
|---|---|
| 方法路径 | `POST /auth/miniapp/staff/bind` |
| 状态 | **方案 2 已废弃**：小程序物业与业委会均免密，仅需微信 + 手机号匹配（§1.5）。接口可保留兼容旧客户端，新产品流程勿再调用 |
| 权限 | 已完成 1.5（正式 JWT） |
| 请求 | `{ "mobile", "password" }` |

### 1.7 住户：手机号与 openid 对齐（认证/导入后补绑，可选）

| 项 | 内容 |
|---|---|
| 方法路径 | `POST /auth/miniapp/resident/bind-mobile` |
| 权限 | 小程序已登录 |
| 请求 | `{ "phoneCode" }` 或已授权场景下的手机号同步 |
| 说明 | 若该手机已属其他用户 → `400` +「请联系物业管理人员」。一阶段无短信验证码 |

### 1.8 查询我的可用身份

| 项 | 内容 |
|---|---|
| 方法路径 | `GET /auth/identities` |
| 权限 | 已登录 |
| 响应 | `{ "identities": [ ... ], "current": { ... }, "user" / "profile": { "id", "realName", "mobile" } }` |
| 规则 | 无 ACTIVE 住户绑定时可出现 `GUEST`；**有 ACTIVE 住户则无 GUEST**；`current` 为 JWT 上下文；`user` 与 `profile` 同义，供首页问候人名 |

### 1.8a 本人修改姓名

| 项 | 内容 |
|---|---|
| 方法路径 | `PUT /auth/profile` |
| 权限 | 已登录（小程序本人；平台改他人姓名仍用人员清单 `PUT /platform/users/{id}`） |
| 请求 | `{ "realName" }`（必填，1～32 字） |
| 响应 | `{ "id", "realName", "mobile" }` |

### 1.9 切换身份 / 小区 / 房屋（重签 JWT）

| 项 | 内容 |
|---|---|
| 方法路径 | `POST /auth/context/switch` |
| 权限 | 已登录 |
| 请求 | `{ "identityType", "communityId?", "roomId?" }` |
| 响应 | `{ "token" }` |
| 校验 | 目标必须出现在 1.8 结果中；业委会/物业不可切到无任职小区 |
| 住户多房 | 同一小区多套房在 `identities[].rooms[]`；小程序「切换身份」按房展开；「我的房屋」点选切换主体房（须传 `roomId`）；未传 `roomId` 时服务端取该小区名下第一套 |

---

## 2. 平台：小区与物业账号

> 平台 Web 首页三 Tab：**小区**（列表 + 建小区申请）· **物业人员** · **全体住户**（只读，`GET /platform/occupants`）。

### 2.0 建小区申请（小程序，已登录）

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET /regions` | 已登录 | 全国省/市/区（县）三级树：31 个省级行政区（含自治区、直辖市），不含港澳台 |
| `GET /community-templates` | 已登录 | 房屋初始化模板列表 |
| `POST /community-applications` | 已登录 | 提交申请：省市区、communityName、applicantName、applicantRole=`RESIDENT\|STAFF`、applicantMobile（必填）、applyReason?（≤20）；房屋初始化二选一：`templateCode?`（预设）或自定义 `buildings`+`unitsPerBuilding`+`floorsPerUnit`+`roomsPerFloor`（落库为 `C_b_u_f_r`） |
| `GET /community-applications/mine` | 已登录 | 我的申请列表 |
| `GET /platform/community-applications` | 平台 | 筛 status；分页 |
| `POST /platform/community-applications/{id}/approve` | 平台 | 建小区；有模板则生成空间；角色 STAFF→挂 PROPERTY_MANAGER |
| `POST /platform/community-applications/{id}/reject` | 平台 | `{ "rejectReason" }` |

### 2.1 开户（建小区，平台直开兜底）

| 项 | 内容 |
|---|---|
| 方法路径 | `POST /platform/communities` |
| 权限 | 平台 |
| 请求 | `{ "name", "address?", "intro?", "contactPhone?", "hasFormalCommittee": 0/1, "provinceCode?", "provinceName?", "cityCode?", "cityName?", "districtCode?", "districtName?", "addressDetail?" }` |
| 响应 | `{ "id" }` |
| 说明 | 地址优先用省市区选择器字段：校验 `GET /regions` 后拼入 `community.address`（直辖市市名与省名相同时不重复）。`addressDetail` 为街道门牌等具体地址；与省市区同时传时 `addressDetail` 必填。也可只传拼接好的 `address` |

### 2.2 小区列表 / 详情 / 更新 / 删除

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET /platform/communities` | 平台 | 分页。查询：`name`（小区名模糊）；`provinceName` / `cityName` / `districtName`（可只到省或省市，按 `address` 前缀匹配，直辖市兼容去重/不去重两种拼接） |
| `GET /platform/communities/{id}` | 平台 | |
| `PUT /platform/communities/{id}` | 平台 | 改介绍等。**改地址（01-Q27）**：body 传 `provinceCode/Name`、`cityCode/Name`、`districtCode/Name` + `addressDetail`（具体地址，必填），服务端校验省市区后写入拼接 `address`；直辖市去重市名。亦可只传已拼接的 `address`。`name` 可省略 |
| `DELETE /platform/communities/{id}` | 平台 | **物理删除**小区行，并级联删除本小区空间（楼栋/单元/楼层/房屋/户型/车位）、任职/住户/业委会、本小区账单/财务/工单等业务数据。查询参数 `force`（默认 `false`）：有 ACTIVE 住户且 `force=false` → 400 + 明确文案；`force=true` 或无住户则执行。写 `audit_log` action=`ADMIN_DELETE_COMMUNITY` |

### 2.3 创建物业账号并设密

| 项 | 内容 |
|---|---|
| 方法路径 | `POST /platform/staff-users` |
| 权限 | 平台 |
| 请求 | `{ "mobile", "password", "realName?" }` |
| 说明 | 写 `sys_user`（手机号+临时密码哈希，`must_change_password=1`）；**不自动任职**，任职走 2.4 / community-roles；对方首次登录物业 Web 须改密 |

### 2.4 物业任职（先经理后管理员）

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `POST /platform/communities/{communityId}/staff` | 平台 | body：`{ "userId", "staffRole": "PROPERTY_MANAGER\|CUSTOMER_SERVICE\|…" }`；`PROPERTY_ADMIN` 入参归一为客服；经理岗全小区唯一 |
| `PUT /platform/communities/{communityId}/staff/{userId}` | 平台 | 改角色/停用（INACTIVE） |
| `GET /platform/communities/{communityId}/staff` | 平台 | 任职列表 |

### 2.5 跨小区住户查询

| 项 | 内容 |
|---|---|
| 方法路径 | `GET /platform/occupants` |
| 权限 | 平台 |
| 查询 | `communityId?`, `mobile?`, `roomNo?`（可跨小区按房号模糊）, `page`, `pageSize` |
| 响应 | 分页；含 `communityName`、`address`（栋+单元+层+房号）、姓名/手机/角色；**身份证明文** |
| 平台首页 | Tab「全体住户」只读一览；改绑/停用须进入对应小区「住户」 |

### 2.6 平台后台直改（审计）

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `POST /platform/fixes/occupants` | 平台 | 增改绑/停用住户；body 含 roomId、user/mobile、role、action；**编辑改手机号时同物业：解绑微信**；写 `audit_log` action=`ADMIN_FIX_OCCUPANT` |
| `POST /platform/fixes/vehicles` | 平台 | 车辆增删改绑 |
| `POST /platform/fixes/parking-links` | 平台 | 车位改挂/解绑（触发顺位） |

### 2.7 人员清单（有手机号账号）

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET /platform/users` | 平台 | **物业人员清单（一人一行）**。仅曾有/现有 `staff_community` 的账号。查询：`mobile?`、`realName?`、`communityId?`（该小区有物业任职）、`status?`、`page`、`pageSize`。响应含 `affiliations[]`（仅 `STAFF`）、`communitySummary`（仅物业岗位） |
| `PUT /platform/users/{id}` | 平台 | body：`{ "realName?", "mobile?" }`；改姓名/改手机号（11 位数字；冲突 `40002`）；**若手机号实际变更 → 删除该用户全部 `user_wechat`，对方须用新号重新授权登录小程序**；不可改平台管理员；审计 `ADMIN_UPDATE_USER`（含 `wechatUnbound`） |
| `POST /platform/users/{id}/enable` | 平台 | **启用**已停用账号：`status=1`（可登录）；**不**自动恢复停用时解除的任职/住户/业委会；审计 `ADMIN_ENABLE_USER` |
| `PUT /platform/users/{id}/staff-community` | 平台 | body：`{ "communityId", "staffRole?" }`（默认经理）；**改归属社区（物业任职）**：停用该人在其他小区的 ACTIVE 物业任职后，按 §2.4 规则任职到目标小区；仅 `status=1`；审计 `ADMIN_SET_USER_COMMUNITY` |
| `PUT /platform/users/{id}/community-roles` | 平台 | body：`{ "items": [ { "communityId", "staffRole" } ] }`。**仅批量同步多社区物业岗位**（如 A 物业经理、B 客服）；同小区物业最多一项；`items` 为空则清除此人全部 ACTIVE 物业（**不改住户、不改业委会**）。若传 `committeeTitle` → `400`（业委会须在小区住户栏任命且须 OWNER）。审计 `ADMIN_SET_USER_ROLES` |
| `DELETE /platform/users/{id}` | 平台 | 默认**停用**：`status=0` + 级联任职/住户/业委会 INACTIVE；审计 `ADMIN_DISABLE_USER`。查询参数 `hard=true`：**须已停用**后物理删除用户行及 `staff_community`/`room_occupant`/`committee_member`/`user_wechat`；审计 `ADMIN_PURGE_USER`；账单等流水可残留孤儿 userId |
| `POST /platform/users/reset-password` | 平台 | `{ "mobile", "newPassword" }`；置 `must_change_password=1`；不可重置平台管理员；审计 `ADMIN_RESET_PASSWORD`。**业主改任物业时须设临时密码**，对方登录物业 Web 后按 §1.3a 改密 |

任职改角色/停用：复用 §2.4 `PUT .../staff/{userId}`。住户解绑：复用 §2.6 `POST /platform/fixes/occupants` action=`DEACTIVATE`。

---

## 3. 物业 Web：小区配置（当前小区）

> 以下接口均需 JWT：`identityType=STAFF` + `communityId`。

### 3.1 小区介绍

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET /staff/community` | 物业 | 当前小区详情 |
| `PUT /staff/community` | 物业 | 改 name/intro/contactPhone；封面 attachmentId。**改地址**同平台：省市区选择器字段 + `addressDetail`，拼入 `address`（01-Q27） |

### 3.2 楼栋 / 单元 / 楼层 / 房屋

| 资源 | 方法 | 说明 |
|---|---|---|
| 楼栋 | `GET/POST /staff/buildings`；`PUT/DELETE /staff/buildings/{id}` | 同小区名唯一；软删 |
| 单元 | `GET/POST /staff/buildings/{buildingId}/units`；`PUT/DELETE .../units/{id}` | |
| 楼层 | `GET/POST /staff/units/{unitId}/floors`；`PUT/DELETE .../floors/{id}` | |
| 房屋 | `GET/POST /staff/floors/{floorId}/rooms`；`PUT /staff/rooms/{id}`；`GET /staff/rooms/{id}/occupants`；`DELETE /staff/rooms/{id}?releaseOccupants=false\|true` | 可改 `roomNo`、`areaSqm`、`houseTypeId`、`status`；同楼层房号唯一；有 ACTIVE 住户时须 `releaseOccupants=true`（解绑为游客后再软删） |
| 房屋树 | `GET /staff/space-tree` | 返回楼栋→单元→楼层→房屋树，供选择器 |

### 3.3 房屋类型（含物业费单价）

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET /staff/house-types` | 物业 | |
| `POST /staff/house-types` | 物业 | `{ "name", "propertyFeeUnitPrice?", "sortNo?" }` |
| `PUT /staff/house-types/{id}` | 物业 | 含单价；出账前单价须有值 |
| `PUT /staff/house-types/{id}/status` | 物业 | 启用/停用 |

### 3.4 车位台账

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET /staff/parking-spaces` | 物业 | 筛：未挂房 / 已挂房 / spaceNo |
| `GET /staff/vehicles` | 物业 | 本小区车辆分页；筛 `plateNo?`、`roomId?`、`bound=yes\|no`；含 `address`、`parkingSpaceNo`、`createdAt`。住户页 Tab「车辆」（业委会无此 Web 入口） |
| `GET /staff/rooms/{roomId}/parking-options` | 物业 | 本房已挂车位选项；`availableOnly`（默认 true）、`keepSpaceId?`（编辑时保留当前位） |
| `POST /staff/parking-spaces` | 物业 | `{ "spaceNo", "remark?" }`（无 ownership） |
| `PUT /staff/parking-spaces/{id}` | 物业 | 改编号/备注 |
| `POST /staff/parking-spaces/{id}/link` | 物业 | `{ "roomId" }` 挂靠（UI：楼栋→单元→楼层→房屋）；触发未绑车辆自动补绑 |
| `POST /staff/parking-spaces/{id}/unlink` | 物业 | 解绑；车辆顺位 |
| `POST /staff/parking-spaces/{id}/relink` | 物业 | `{ "roomId" }` 改挂到其他房；先顺位再挂 |

### 3.5 业委会职务

> 须为本小区 ACTIVE **业主（OWNER）**；入口为小区后台「住户」栏。平台进小区后台走同一套 `/staff/*`。

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET /staff/committee-members` | 物业/平台进小区 | |
| `POST /staff/committee-members` | 同上 | `{ "userId"（推荐）或已存在业主的 mobile, "title": "ACTIVIST\|DIRECTOR\|MEMBER" }`；**须已是本小区 OWNER**；主任唯一；一人一小区一条；不可凭手机号新建非业主用户再任命 |
| `PUT /staff/users/{userId}/committee` | 同上 | `{ "title": "ACTIVIST\|DIRECTOR\|MEMBER" \| null }`；住户栏任命/解除；`title` 空则 INACTIVE |
| `PUT /staff/committee-members/{id}` | 同上 | 改 title / INACTIVE；改 ACTIVE title 时仍须为业主 |
| `GET /staff/users/by-mobile` | 物业 | `mobile`（须 `1` 开头共 11 位，与 BIND 一致）；返回 `{ exists, userId?, mobile?, realName?, hasIdCard?, idCardMasked? }`。空间/住户名单绑定表单：已存在则出匹配卡、姓名只读（不改名）；不存在则姓名必填建档 |

---

## 4. 导入 / 认证 / 变更 / 直改

### 4.1 住户 Excel

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET /staff/import/template` | 物业 | 下载模板（列见 03 §6） |
| `POST /staff/import/batches` | 物业 | multipart 上传 Excel；建 `import_batch` |
| `GET /staff/import/batches` | 物业 | 批次列表 |
| `GET /staff/import/batches/{id}` | 物业 | 含 failDetailJson |

导入规则：业主行须身份证；已有 ACTIVE 业主则该行失败不覆盖。

### 4.2 认证申请（小程序住户侧）

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `POST /resident/auth-applications` | 游客 或 住户（再申请其他房）；**禁止**业委会/物业身份 | body：`{ "communityId", "roomId", "applicantName", "applicantIdCardNo?", "applyRole", "applyMessage?", "attachmentIds?": [] }`；**手机号由服务端取当前登录用户**（忽略客户端 `applicantMobile`）；**姓名：账号已有 `realName` 时小程序只读带出，无则必填（改名走「我的」）**；业主角色须身份证+产权附件；**不要**面积/房屋类型；已是本房**同角色**则拒；本房已有待审申请则拒；已是本房**其他角色**可申请改角色（通过时 upsert） |
| `GET /resident/communities` | 已登录 | 认证选小区：`name?`、`provinceName?`、`cityName?`、`districtName?`、`page`、`pageSize`；按地址前缀+名称模糊 |
| `GET /resident/communities/{id}/space-tree` | 已登录 | 认证选房：楼栋→单元→楼层→房屋（仅 id/名称/房号）。小程序交互：**房号搜索 + 四级联动滚轮一次确认**（S1+S4） |
| 手机号 | — | 账号无 mobile → `400` 提示重新微信授权登录 |
| `GET /resident/auth-applications/mine` | 游客/住户 | 我的申请列表（含 `roomNo`、`roomPath`、`attachments[]`）；小程序「认证记录」 |
| `GET /resident/auth-applications/{id}` | 申请人本人 | 详情含 `roomPath`、附件摘要 |

### 4.3 认证审核（物业）

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET /staff/auth-applications` | 物业 | 筛 status；含 `roomNo`、`roomPath`（栋+单元+层+房号）、`createdAt`、`attachments[]`（id/fileName/contentType/url/image）；列表可快捷通过/拒绝 |
| `GET /staff/auth-applications/{id}` | 物业 | 详情同上；审核页预览产权图后通过/拒绝 |
| `POST /staff/auth-applications/{id}/approve` | 物业 | 事务：绑/改 occupant（同房同人已有 ACTIVE 则改角色）、归档附件**改挂** ROOM_ARCHIVE、订阅消息 AUTH_RESULT、待办 AUTH_RESULT；非业主校验人数≤10；业主校验唯一与身份证 |
| `POST /staff/auth-applications/{id}/reject` | 物业 | `{ "rejectReason" }`；订阅+待办 |

提交成功时：给本小区全体有效物业写 `PENDING_REVIEW` 待办 + 尝试订阅 `PENDING_REVIEW_STAFF`。

### 4.4 房屋变更申请（业主）

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `POST /resident/room-change-applications` | 当前房 OWNER | `{ "changeType", "payload", "applyMessage?", "attachmentIds?" }`；类型仅：`ADD_MEMBER` / `REMOVE_MEMBER` / `ADD_VEHICLE` / `REMOVE_VEHICLE` / `LINK_PARKING` / `UNLINK_PARKING` |
| `GET /resident/room-change-applications/mine` | 业主 | 列表视图含 `changeTypeLabel`、`roomPath`、`payloadSummary`、`status`、`rejectReason` |
| `GET /staff/room-change-applications` | 物业 | 同上视图字段，便于审核 |
| `POST /staff/room-change-applications/{id}/approve` | 物业 | 按类型落库；LINK 触发补绑；UNLINK 走顺位；订阅+待办 |
| `POST /staff/room-change-applications/{id}/reject` | 物业 | `{ "rejectReason" }` |

### 4.5 物业后台直改（审计）

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `POST /staff/fixes/occupants` | 物业 | action=`BIND`/`DEACTIVATE`。`BIND`：`mobile` 须 `1` 开头共 11 位（与 by-mobile 一致）；新增可只传 `mobile`（查找或建档）；**编辑须传 `userId`+`mobile`**（改当前人手机号，全局唯一，冲突 `40002`；**手机号变更则解绑该用户全部微信，须用新号重新授权**），可改 `realName`/`role`/`idCardNo`；**匹配已有账号绑定时前端不传 `realName`（姓名只读，避免覆盖）**；同房同人已有 ACTIVE 则改角色（不插第二行） |
| `POST /staff/fixes/vehicles` | 物业 | `ADD/UPDATE/DELETE`；字段 `roomId`、`plateNo`、`parkingSpaceId?`、`vehicleId?`；**不可改房屋**（换房删再建）；未传车位时 ADD 触发空位自动补绑；写审计 |
| `POST /staff/fixes/parking-links` | 物业 | |

平台直改见 §2.6。

---

## 5. 住户列表 / 我的房屋 / 车辆只读查询

### 5.1 住户列表

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET /staff/occupants` | 物业 | 身份证明文；筛 `buildingId?` / `unitId?` / `roomId?` / `roomNo?` / `mobile?` / `role?`；行含 `buildingName` / `unitName` / `floorName` / `committeeTitle?`。空间页选房屋与住户名单共用维护面板 |
| `GET /committee/occupants` | 业委会 | 仅本小区；筛参同物业（含 `unitId?`）；身份证前4后4中间* |
| `GET /platform/occupants` | 平台 | 见 2.5 |

### 5.2 我的房屋

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET /resident/rooms` | 住户 | 当前小区下我的 ACTIVE 绑定 |
| `GET /resident/rooms/{roomId}` | 该房住户 | 地址拼接、面积、类型、成员、车辆、已关联车位；**归档材料仅 OWNER** |
| `GET /resident/rooms/{roomId}/archives` | OWNER | 归档附件列表 |

### 5.3 可选：未挂房车位（变更申请 LINK 用）

| 项 | 内容 |
|---|---|
| 方法路径 | `GET /resident/parking-spaces/unlinked` |
| 权限 | 当前房 OWNER |
| 说明 | 仅本小区 `room_id IS NULL` 的车位 |

---

## 6. 附件

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `POST /attachments/upload` | 已登录 | multipart：`file` + `bizType`（上传时可先 TEMP 或业务类型）+ 可选 `bizId`；校验大小/扩展名/同业务≤3 |
| `GET /attachments/{id}` | 有权看该业务的人 | 返回 url 或下载流 |
| `DELETE /attachments/{id}` | 上传者或物业/平台 | 仅未归档/未锁定业务允许 |

认证通过改挂：服务端将 `biz_type`→`ROOM_ARCHIVE`，`biz_id`→`roomId`。

---

## 7. 订阅消息与待办

### 7.1 记录用户授权（可选，便于审计）

| 项 | 内容 |
|---|---|
| 方法路径 | `POST /notify/subscribe/ack` |
| 权限 | 小程序已登录 |
| 请求 | `{ "templateIds": [], "scene": "AUTH_RESULT\|ROOM_CHANGE_RESULT\|PENDING_REVIEW_STAFF" }` |

### 7.2 发送日志（管理查询，可选）

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET /staff/subscribe-notify-logs` | 物业 | 本小区 |
| `GET /platform/subscribe-notify-logs` | 平台 | |

下发由服务端在审核/提交事件中触发，写 `subscribe_notify_log`（SUCCESS/FAIL/SKIPPED）。

### 7.3 站内待办

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET /todos` | 已登录 | 当前用户；筛 `status=OPEN` |
| `POST /todos/{id}/read` | 接收人 | 写 `read_at` |
| `POST /todos/{id}/done` | 接收人 | 或业务完成时服务端自动 DONE |

小程序「我的」待办：结果类（`AUTH_RESULT` / `ROOM_CHANGE_RESULT` 等）点按 → `done` 并清除；行动类（账单/报修/投票等）点按跳转对应页并 `read`。

D.1 待办 `todo_type`：`AUTH_RESULT` / `ROOM_CHANGE_RESULT` / `PENDING_REVIEW`；D.2～D.4 扩展见第二部分。

---

## 8. 错误码（D.1 常用 + 账单关键）

| code | 场景 |
|---|---|
| 40001 | 参数校验失败 |
| 40002 | 手机号已被其他账号占用 |
| 40003 | 该房已有有效业主，禁止覆盖 |
| 40004 | 身份证号已被其他用户占用 |
| 40005 | 非业主有效住户已达 10 人 |
| 40006 | 小区已有有效物业经理 |
| 40007 | 无物业经理时不可创建管理员任职 |
| 40008 | 同人同小区已有物业任职/业委会职务 |
| 40009 | 小区已有有效主任 |
| 40010 | 车牌同小区重复 |
| 40011 | 车位编号同小区重复 |
| 40012 | 附件超大小或超数量 |
| 40013 | 当前身份不允许提交认证（业委会/物业） |
| 40014 | 已有住户绑定，不可再使用游客身份 |
| 40015 | 面积非法（&lt; 0） |
| 40016 | 账单：面积为空或类型/单价缺失，整单不可提交 |
| 40017 | 账单已确认收款，不可覆盖 |
| 40101 | 未登录 |
| 40301 | 身份或小区权限不足 |
| 40302 | 须先修改临时密码后再使用物业后台 |
| 40401 | 资源不存在 |

---

## 9. 接口与表对照（D.1）

| 能力域 | 主要表 |
|---|---|
| 登录/微信 | sys_user, user_wechat | 手机号授权匹配见 01-Q16 |
| 小区/空间/类型 | community, building, unit, floor, room, community_house_type |
| 车位/车辆 | parking_space, room_vehicle |
| 住户绑定 | room_occupant |
| 物业/业委会 | staff_community, committee_member |
| 认证/变更 | auth_application, room_change_application |
| 导入/审计/附件 | import_batch, audit_log, attachment |
| 订阅/待办 | subscribe_notify_log, todo_item |
| D.2 账单 | fee_item, fee_item_price_rule, bill_meter_upload, bill, bill_line, payment_record |
| D.3 | finance_entry, public_revenue_item, notice, repair_order, repair_reply, complaint |
| D.4 | vote, vote_option, vote_ballot |

---

# 第二部分 · 一阶段 D.2～D.4 API

> 与 D.1 共用 §0 约定。表结构：D.2 见 03 §10；D.3 见 03 §11；D.4 见 03 §12。

---

## 10. D.2 收费、账单与缴费

### 10.1 费项与单价

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET/POST /staff/fee-items` | 物业 | **九大类**（01-Q34）：`PROPERTY_FEE`/`PARKING_MGMT`/`PARKING_MONTHLY`/`SHARED`/`GARBAGE`/`WATER`/`ELECTRIC`/`GAS`/`OTHER`。body：`name`（显示名）、`feeCategory`、`billingMode`=`FIXED`\|`FORMULA`\|`IMPORT`（写入 remark）、`monthlyAmount?`（FIXED 必填）、物业费公式字段可放 remark。除 `OTHER` 外各大类本小区仅一条；`FORMULA` 仅允许三费。创建已存在的单例大类 → 400 |
| `PUT /staff/fee-items/{id}` | 物业 | 含启用停用（1=出账计入，0=不计入）；物业费可改 `remark` 系数 |
| `DELETE /staff/fee-items/{id}` | 物业 | 删除费项及单价规则 |
| `GET/POST /staff/fee-items/{id}/price-rules` | 物业 | 车位管理费 / 月保均为 `DEFAULT` 一价 |
| `PUT /staff/fee-item-price-rules/{id}` | 物业 | |

### 10.2 公摊/水电/煤气上传

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `POST /staff/bill-meter-uploads` | 物业 | 单条或批量；公摊仅 amount；水电煤气须起止读数+单价 |
| `GET /staff/bill-meter-uploads/template` | 物业 | 下载 Excel：含「填写说明」+「表计上传」；预填本小区全部房屋×启用中的导入费项；列 A～K 见说明页；查询参数 `billMonth` |
| `POST /staff/bill-meter-uploads/import` | 物业 | Excel 批量；按「表计上传」工作表导入；以 A 列房屋ID 匹配；H～K 全空则跳过 |
| `GET /staff/bill-meter-uploads` | 物业 | 按 `billMonth`+房筛选 |

### 10.3 生成 / 发放 / 单房重推 / 查询账单

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET /staff/bills/charge-preview` | 物业 | `billMonth=YYYY-MM`；**不写库**干跑算费。返回 `summary`（纳入出账/可出账/将失败/物业费资料/导入门禁）与 `rooms[]`（地址、面积、类型单价、车位车辆、预计费项与合计、READY/WILL_FAIL） |
| `POST /staff/bills/generate` | 物业 | `{ "billMonth", "roomIds?" }`；**不传 `roomIds` = 一键**：本小区全部有有效住户的房屋。若存在**启用中的表格导入费项**，须本月已上传对应类别数据，否则拒绝。仅当启用了物业费公式项时才校验面积/类型/单价（缺失 → 该房失败）；未确认收款可覆盖重出；结果=`DRAFT` |
| `POST /staff/bills/publish` | 物业 | `{ "billMonth", "roomIds?" }`；→ `PUBLISHED`；写 `due_date = 发放日期 + 10 天`（01-Q22）；待办+订阅催缴 |
| `POST /staff/bills/{id}/publish` | 物业 | 单账单发放 |
| `POST /staff/rooms/{roomId}/bills/republish` | 物业 | **单房重推**（01-Q19）：未缴可覆盖；重算后重新发放并**重写 `due_date=新发放日+10天`**；已缴 → `40017` |
| `GET /staff/bills` · `GET /staff/bills/{id}` | 物业 | 含草稿 |
| `GET /resident/bills` · `GET /resident/bills/{id}` | 住户 | 列表默认仅**待缴**（`PUBLISHED`/`OVERDUE`，也可用 `status=UNPAID`）；`status=PAID` 看已缴；列表带 `lines`/`roomLabel` |
| `GET /platform/bills` | 平台 | 跨小区 |

### 10.4 收款与退费（一阶段两条路径均必做）

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET/PUT /staff/payment-config` | 物业经理 | 线下指引、是否启用预缴 `prepaidEnabled`、预缴说明 `prepaidGuideText`；商户号敏感项放环境变量 |
| `POST /resident/bills/{id}/wechat-pay` | 住户 | **路径 A（必做）**：商户号下单；金额必须=`total_amount`（**整单**，01-Q20）；仅 `PUBLISHED` 未缴 |
| `POST /pay/wechat/notify` | 微信回调 | 验签；支付成功 → 账单 `PAID`、写 `payment_record`（`pay_channel=WECHAT_MCH`）、自动确认；幂等 |
| `GET /resident/bills/{id}/pay-guide` | 住户 | **路径 B 指引**：返回收款说明/二维码（非下单） |
| `POST /staff/bills/{id}/confirm-paid` | 物业（小程序/Web） | **路径 B（必做）**：私下转账/现金等；body 可选 `{ "payChannel":"TRANSFER\|QR\|CASH\|OTHER", "remark?" }`；→ `PAID` + `payment_record`（记确认人）；已缴不可重复 |
| `POST /staff/bills/{id}/refund-duplicate` | 物业 | 重复支付退费 |
| `GET /resident/payment-records` | 住户 | |
| `GET /staff/payment-records` | 物业 | 本小区；用于收入汇总 |
| `GET /platform/payment-records` | 平台 | 跨小区 |

**规则：**  
- 路径 A 与 B **均可**把账单变为已缴并进入收入汇总。  
- 同一账单已 `PAID` 后锁定，不可再确认、不可重推覆盖。  
- 住户可先看线下指引再转账，**系统不会因个人转账自动到账**，必须等物业点确认。

---

## 11. D.3 财务、公告、报修、投诉（完整接口）

### 11.1 经营收支

| 方法路径 | 权限 | 请求/说明 |
|---|---|---|
| `POST /staff/finance/entries` | 物业（**仅 Web**） | `{ "entryType":"INCOME\|EXPENSE", "amount", "occurDate", "category?", "title", "remark?" }` → status=`PENDING` |
| `PUT /staff/finance/entries/{id}` | 物业 | 仅 `PENDING` 可改 |
| `POST /staff/finance/entries/{id}/approve` | 物业经理 | 通过 → `APPROVED` |
| `POST /staff/finance/entries/{id}/reject` | 物业经理 | `{ "reason?" }` → `REJECTED` |
| `GET /staff/finance/entries` | 物业 | 本小区；筛日期/状态 |
| `GET /resident/finance/summary` | 业主 | `{ "from", "to" }` → `{ income, expense, balance, paymentIncome?, prepaidIncome?, entryIncome?, disclaimer? }`；口径 **01-Q21 / Q28 / Q33**：收入 = 区间内 `payment_record`（按 `paid_at`）+ **ACTIVE 预缴协议按账期结转的 `cashAmount`（`prepaidIncome`）** + 已审经营收入；支出 = 已审经营支出；余额 = 收入 − 支出。**不含**未缴账单、**默认不含**已公示公共收益；预缴优惠**不计入**。`disclaimer` 可为固定口径文案（亦可仅前端写死，与 01-B5.4 一致） |
| `GET /committee/finance/summary` | 业委会 | 同 Q21 / Q28，本小区 |
| `GET /staff/finance/summary` | 物业 | 同 Q21 / Q28，本小区 |
| `GET /platform/finance/summary` | 平台 | 可传 `communityId`；同 Q21 / Q28 |

**汇总约束：** 已通过账单缴费进入公开的金额，登记 `finance_entry` 时业务上禁止再记同额物业费收入（防双计，01-Q28）。公共收益走 §11.2，不并入本 summary 三数。

**一阶段增补（Q29～Q33）：**

| 方法路径 | 权限 | 说明 |
|---|---|---|
| `GET/POST /staff/room-fee-exemptions` · `DELETE .../{id}` | 物业 | 房屋费项豁免（房+类别+生效账期）；出账跳过 |
| `GET /staff/fee-exemption-ledger` | 物业 | 减免台账（出账时写入，不进 Q21） |
| `POST /staff/bills/{id}/void` | 物业 | 未缴作废 → `VOID` |
| `POST /staff/bills/{id}/credit-reverse` | 物业 | 已缴冲红：负向 `payment_record` + `VOID` |
| `POST /staff/bills/{id}/supplement` | 物业 | 已缴少收补收（正数 payment） |
| `POST /staff/bills/batch-confirm-paid` | 物业 | `{ billIds, payChannel?, remark? }` 批量确认收款 |
| `POST /resident/bills/batch-wechat-pay` | 住户 | `{ billIds }` 多张已发放未缴账单合并支付（可跨房屋；MOCK 逐单确认） |
| `GET .../finance/summary?viewMode=` | 各端 | `BY_PAID_AT`\|`BY_BILL_MONTH`；预缴 ACTIVE 明细 `cashAmount` **始终按 `bill_month`** 计入 `prepaidIncome`（签约日不整笔灌入）；返回构成/`disclaimer`；`paymentDetails[]` 含 `kind`/`kindLabel`（收款|冲红|补收）、`roomLabel`、`feeTypeLabel`/`feeCategories`（账单费项）、`billMonth`、`amount`、`payChannel`、`paidAt`、`remark` |
| `PUT /staff/payment-config` | 经理 | 增 `prepaidEnabled` / `prepaidGuideText`（与线下指引并列单独配置） |
| `POST /staff/prepaid/plans/preview` | 物业 | `{ roomId, billMonths[], feeCategories[], cashAmount? }` → 报价 `listAmount` / 分月分项明细；不写库 |
| `POST /staff/prepaid/plans` | 物业 | 创建协议；`confirm` 默认 true → ACTIVE。生效时：已缴含覆盖费则 400 拒绝；未缴单自动减费/作废空单，响应含 `billAdjust` |
| `GET /staff/prepaid/plans` · `GET .../plans/{id}` | 物业 | 列表 / 详情（含 items） |
| `POST /staff/prepaid/plans/{id}/confirm` | 物业 | → ACTIVE；此后出账跳过覆盖费项 |
| `POST /staff/prepaid/plans/{id}/void` | 物业 | → VOID（可选 reason） |
| `DELETE /staff/prepaid/plans/{id}` | 物业 | 仅 VOID/DRAFT 可删；ACTIVE 须先作废；不影响已计入公开的历史实收 |
| `GET /resident/prepaid/plans` | 住户 | 本房预缴协议列表（只读） |

### 11.2 公共收益

| 方法路径 | 权限 | 请求/说明 |
|---|---|---|
| `POST /staff/public-revenue/items` | 物业 | `{ "title", "amount", "occurMonth?", "remark?" }` → **直接 `PUBLISHED` 进财务公开**（无业委会确认） |
| `PUT /staff/public-revenue/items/{id}` | 物业 | 可改 |
| `GET /resident/public-revenue/items` | 业主/业委会（同业主查阅） | **已公示** |
| `GET /staff/public-revenue/items` | 物业 | 本小区全部 |
| `GET /platform/public-revenue/items` | 平台 | 跨小区 |

> 已废弃：`/committee/public-revenue/items/*/confirm|reject`（群众监督，无确认闸门）

### 11.3 公告

| 方法路径 | 权限 | 请求/说明 |
|---|---|---|
| `POST /staff/notices` | 物业 | 物业公告；`visibility` 含 `PUBLIC_ALL\|RESIDENTS\|OWNERS` |
| `POST /committee/notices` | 业委会 | 业委通知；受众常用 `RESIDENTS`（住户）/`OWNERS`（业主） |
| `PUT /staff\|committee/notices/{id}` | 创建人本人 | 仅自己创建的 |
| `DELETE /staff\|committee/notices/{id}` | 创建人本人 | 软删 |
| `DELETE /platform/notices/{id}` | 平台 | 可删任意 |
| `GET /notices` | 按可见范围 | 可选 `creatorIdentity=STAFF\|COMMITTEE` 拆入口 |
| `GET /notices/home` | 登录 | Banner+最新 **仅物业公告** |
| `GET /notices/{id}` | 有权可见者 | |
| `GET /staff/notices` · `GET /committee/notices/mine` | 物业/业委会 | 管理我创建的 |
| `GET /committee/notices` | 业委会 | 本小区**全部**业委通知（含他人，只读列表） |
| `GET /platform/notices` | 平台 | 跨小区 |

### 11.4 报修

| 方法路径 | 权限 | 请求/说明 |
|---|---|---|
| `POST /resident/repairs` | 住户 | `{ "roomId", "category", "description", "attachmentIds?" }` → `PENDING_ASSIGN` |
| `GET /resident/repairs` · `GET /resident/repairs/{id}` | 住户本人 | |
| `POST /staff/repairs/{id}/assign` | 物业 | `{ "assigneeUserId" }` → `ASSIGNED`；记 `assigned_at` |
| `POST /staff/repairs/{id}/replies` | 物业 | `{ "content", "attachmentIds?" }`；首条答复写 `first_reply_at`；状态→`IN_PROGRESS` |
| `POST /staff/repairs/{id}/complete` | 物业 | → `DONE_WAIT_RATE` |
| `POST /resident/repairs/{id}/rate` | 住户本人 | `{ "rating":1～5, "comment?" }` → `COMPLETED`，写分数；仅 `DONE_WAIT_RATE` |
| `POST /staff/repairs/{id}/close` | 物业 | → `CLOSED` |
| `GET /staff/repairs` | 物业 | 本小区；派单后 24h 无首复 → 升级经理待办；**完工满 7 天仍待评价 → 任务自动 COMPLETED 且 rating=null（01-Q24）** |
| `GET /platform/repairs` | 平台 | 跨小区 |

### 11.5 投诉

| 方法路径 | 权限 | 请求/说明 |
|---|---|---|
| `POST /resident/complaints` | 住户 | `{ "roomId?", "contactName", "contactMobile", "category", "content", "attachmentIds?" }`；**无身份证字段** |
| `GET /resident/complaints` · `GET /resident/complaints/{id}` | 住户本人 | |
| `POST /staff/complaints/{id}/handle` | 物业 | `{ "replyContent" }`；状态 `PROCESSING`→`REPLIED` |
| `POST /staff/complaints/{id}/close` | 物业 | → `CLOSED` |
| `GET /staff/complaints` | 物业 | 本小区；手机明文 |
| `GET /committee/complaints/overview` | 业委会 | 兼容旧接口；仅投诉类 |
| `GET /committee/tickets` | 业委会 | 本小区工单只读全量（报修+投诉）；可选 `kind` |
| `GET /committee/tickets/{id}` | 业委会 | 详情只读（不脱敏，同物业详情结构） |
| `GET /platform/complaints` | 平台 | 跨小区明细 |

---

## 12. D.4 业主投票（完整接口）

| 方法路径 | 权限 | 请求/说明 |
|---|---|---|
| `POST /committee/votes` | 业委会 | `{ "title", "background", "startAt", "endAt", "options":["同意","反对",...] }`；无草稿，创建即生效时间窗内进行 |
| `POST /staff/votes` | **物业经理** | 同上 |
| `POST /platform/votes` | 平台 | 须带 `communityId` + 同上 |
| `DELETE /votes/{id}` | 截止前=发起人；截止后=**仅平台** | 硬删除（可写 audit_log） |
| `GET /resident/votes` | 业主 | 本小区；一人多房**合并列表** |
| `GET /resident/votes/{id}` | 业主 | 含本人各房投票状态；**非本小区业主 403/整页不可见** |
| `POST /resident/votes/{id}/ballots` | 业主 | `{ "ballots":[ { "roomId", "optionId" } ] }`；仅本人 ACTIVE 业主房；一房一票；截止前可改 |
| `GET /votes/{id}/stats` | 业主/业委会/物业/平台 | 各选项票数（按房屋套数）；**无法定门槛判定** |
| `GET /staff/votes` | 物业 | 本小区（含物业发起） |
| `GET /committee/votes` | 业委会 | 本小区**仅业委会发起**的投票（管理用） |
| `GET /platform/votes` | 平台 | 跨小区 |
| `GET /share/votes/{id}` | 本小区业主（分享落地） | 非本小区业主不可见 |

订阅/待办：投票开始；**临近截止 = `end_at` 前 3 小时**（01-Q23，每人至多一次）；结果 → 业主。

---

## 13. 下一动作

1. **G 节已空**：可进入编码准备  
2. 按 **D.1→D.2→D.3→D.4** 建表并实现（禁止一次生成全量代码）  
3. **二阶段（E）** 另开文档  

登录：手机号授权匹配（§1.4～1.5）；小程序物业/业委会免密；身份弹窗切换（§1.8～1.9）。出账：一键 + 单房重推（§10.3）。收款：商户支付 + 线下确认（§10.4 / Q17）。
