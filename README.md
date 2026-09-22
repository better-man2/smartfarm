# 智慧农业精准灌溉系统

基于 Spring Boot + MyBatis 的物联网灌溉管理系统，分为**农户操作端**与**管理员后台**两个角色端，
覆盖土壤湿度监测、自动灌溉、分级告警通知、历史统计看板、水肥配比与多设备批量管理。

---

## 一、快速启动

```bash
# 默认使用内嵌 H2 数据库，无需安装任何数据库，开箱即跑
mvn spring-boot:run
```

启动后访问：

| 入口 | 地址 |
| --- | --- |
| 登录页 | http://localhost:8081/index.html |
| 农户操作端 | http://localhost:8081/farmer.html |
| 管理员后台 | http://localhost:8081/admin.html |
| H2 控制台（开发用） | http://localhost:8081/h2-console |

登录页会按账号角色自动跳转到对应工作台。

### 演示账号

| 角色 | 账号 | 密码 | 说明 |
| --- | --- | --- | --- |
| 管理员 | `admin` | `123456` | 管理后台全部功能 |
| 农户 | `farmer1` | `123456` | 名下 2 块地（玉米田1号手动 / 小麦试验田自动） |
| 农户 | `farmer2` | `123456` | 名下 1 块地（蔬菜大棚，自动灌溉） |

### 切换到 MySQL

数据库账号密码通过**环境变量**注入，不写在配置文件里。

```powershell
# 1. 设置环境变量（Windows PowerShell，设置后需重开终端）
[Environment]::SetEnvironmentVariable('SMARTFARM_DB_USER',     'root',      'User')
[Environment]::SetEnvironmentVariable('SMARTFARM_DB_PASSWORD', '你的密码',   'User')
```

```bash
# 2. 启动时指定 profile
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

MySQL 环境同样会在启动时自动执行 `src/main/resources/db/schema.sql` 建表
（脚本使用 `CREATE TABLE IF NOT EXISTS`，可重复执行）。
若数据库账号没有建表权限，可手动导入该脚本后把 `spring.sql.init.mode` 改为 `never`。

连接信息默认是 `localhost:3306/smart_farm`，用户名为 `root`，
需要改动可直接编辑 `application-mysql.properties` 中的 `spring.datasource.url`。

---

## 二、需求实现对照

### P0 · 土壤湿度监测 / 自动灌溉 / 告警通知

| 需求 | 实现位置 |
| --- | --- |
| 土壤湿度、温度、光照实时监测 | `SensorDataService`、`SensorController`、`farmer.html` 实时监测看板 |
| 采集数据入库并驱动告警 | `POST /api/sensor/ingest`（设备网关入口）→ `AlarmService.evaluateSensorData()` |
| 手动模式：选地块直接下发灌溉指令 | `POST /api/irrigation/manual`、`IrrigationService.manualIrrigate()` |
| 自动模式：按预设策略自动执行 | `AutoIrrigationScheduler.scanAutoIrrigation()`（每分钟巡检） |
| 只有高等级告警才推送通知 | `AlarmService.raiseAlarm()` 强制校验 `level == HIGH`；前端 `App.alarmCenter` 轮询推送队列并弹桌面通知 |
| 普通数据异常仅页面记录 | 非 HIGH 等级告警 `pushStatus` 恒为 0，只在告警列表留痕 |

**自动灌溉的触发条件**（全部满足才执行，不是"湿度低就浇水"）：

1. 地块开启了自动模式
2. 最新数据在有效期内（默认 30 分钟，避免用陈旧数据浇水）
3. 土壤湿度低于策略下限
4. 当前时间处于策略允许的灌溉时段（支持跨零点时段）
5. 温度、光照未超过策略上限
6. 该地块没有正在执行的灌溉任务
7. 距上次灌溉已超过冷却期（默认 20 分钟）

**灌溉闭环**：巡检触发 → 写入灌溉任务（RUNNING）→ 后台任务按计划时长回收为 SUCCESS →
回写灌溉后的湿度数据，形成"监测 → 决策 → 灌溉 → 再监测"的闭环。

### P1 · 历史统计 / 数据看板

| 需求 | 实现位置 |
| --- | --- |
| 农户数据看板 | `GET /api/stats/dashboard`、农户端「实时监测看板」 |
| 管理员全局看板 | `GET /api/stats/admin/overview`、管理后台「全局看板」 |
| 历史统计（按天用水量、环境均值、决策记录） | `GET /api/stats/history`、`StatsService.historyStats()` |
| 统计报表导出 | `GET /api/report/export?type=summary|alarm|irrigation|sensor`（CSV，含 UTF-8 BOM，Excel 可直接打开） |

> 节水率为**估算值**：以"传统定时满灌"用水量（按需灌溉量的约 1.5 倍）为基准对比得出，
> 前端与接口均显式标注了该口径（`savingRateNote`）。

### P2 · 水肥配比 / 多设备批量管理

| 需求 | 实现位置 |
| --- | --- |
| 水肥配比方案维护 | `FertilizerService`、管理后台「水肥配比」 |
| 灌溉时关联配比方案 | 手动灌溉表单选择方案，`irrigation_records.fertilizer_recipe_id` |
| 设备新增 / 绑定 / 状态监控 | `DeviceService`、管理后台「设备管理」 |
| 多设备批量管理 | `POST /api/device/batch/bind`、`/batch/unbind`、`/batch/status` |

---

## 三、操作流程

### 农户操作端

1. **登录** → 自动进入 `farmer.html`
2. **查看实时监测看板**：选择地块 → 查看土壤湿度/温度/光照实时读数、24 小时趋势曲线、设备在线状态
3. **选择灌溉模式**
   - *手动模式*：选择地块 → 填写水量/时长/是否配肥 → 下发指令
   - *自动模式*：打开开关 → 系统按策略自动执行，页面实时显示"是否已满足触发条件"
4. **接收告警**：高等级故障会弹出桌面通知并在铃铛显示红点；普通数据异常仅在「告警消息」中记录
5. **故障排查**：在「我的设备」查看故障设备与排查建议

### 管理员后台

1. **登录** → 自动进入 `admin.html`
2. **管理设备**：新增设备、绑定/解绑地块、批量操作、查看全部地块汇总数据
3. **配置策略与阈值**：维护全局灌溉策略、告警阈值规则（含是否推送）、农户账号与权限
4. **处理告警与报表**：查询系统告警记录、处理设备故障、导出统计报表

---

## 四、技术架构

```
com.example.smartfarm
├── common/          统一响应 ApiResult、全局异常、登录与角色拦截器、常量与工具
├── config/          数据初始化（演示数据）、业务参数配置、分页与权限配置
├── entity/          9 张表对应实体
├── mapper/          MyBatis 注解式 Mapper
├── service/         业务服务（含告警引擎、自动灌溉调度器、统计与报表）
└── controller/      接口层（农户端 / 管理端）
```

- **数据库**：默认 H2（`MODE=MySQL` 兼容方言），可切换 MySQL 8
- **定时任务**：`@Scheduled` 实现自动灌溉巡检、设备离线巡检、灌溉任务回收、传感器模拟采集
- **前端**：原生 HTML + Bootstrap 5 + Chart.js，按角色拆分为两套页面，公共逻辑抽到 `js/common.js`

### 数据表

| 表名 | 用途 |
| --- | --- |
| `users` | 用户（管理员 / 农户），含启用状态 |
| `farmlands` | 地块，含自动灌溉开关与绑定策略 |
| `devices` | 物联网设备（传感器 / 阀门 / 水肥一体机） |
| `sensor_data` | 传感器采集数据 |
| `irrigation_strategies` | 灌溉策略（全局 / 地块专属） |
| `irrigation_decisions` | 灌溉决策记录 |
| `irrigation_records` | 灌溉执行记录（手动 / 自动） |
| `fertilizer_recipes` | 水肥配比方案 |
| `alarm_rules` | 告警阈值规则 |
| `alarms` | 告警记录 |

### 业务参数（`application.properties`）

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `smartfarm.sensor.valid-minutes` | 30 | 数据有效期，超过则不用于自动灌溉决策 |
| `smartfarm.auto-irrigation.cooldown-minutes` | 20 | 同一地块两次自动灌溉的最小间隔 |
| `smartfarm.device.offline-minutes` | 30 | 设备离线判定时长 |
| `smartfarm.alarm.dedup-minutes` | 30 | 告警去重窗口，避免重复刷屏 |
| `smartfarm.simulator.enabled` | dev 下 true | 是否开启传感器数据模拟采集 |

---

## 五、接口验证

项目根目录提供了端到端接口测试脚本，覆盖登录鉴权、数据隔离、两种灌溉模式、告警分级与推送、
设备批量管理、统计报表、CSV 导出等 15 组共 120 项断言，可重复执行：

```bash
# 应用启动后执行
python apitest.py
```

最近一次运行结果：

```
汇总：通过 120 项，失败 0 项
全部通过
```

### 设备数据上报（对接真实设备）

```bash
curl -X POST http://localhost:8081/api/sensor/ingest \
  -H "Content-Type: application/json" \
  -d '{"deviceCode":"SM-001","soilHumidity":26.5,"temperature":29.0,"lightIntensity":18000}'
```

上报后系统会立即执行告警评估；若湿度低于高等级阈值，会生成待推送的高等级告警。
生产环境请关闭数据模拟器（`smartfarm.simulator.enabled=false`）并改用真实设备上报。

---

## 六、注意事项

- H2 为内存数据库，**重启后数据重置为演示种子数据**；需要持久化请使用 MySQL。
- 演示数据中的设备 `SL-001` 被预置为故障状态、`SM-004`/`ST-003` 为未绑定状态，
  便于演示"告警 → 查看设备状态 → 故障排查"与"设备绑定"流程。
- 高等级告警推送依赖浏览器通知权限，首次会请求授权；未授权时仍有页面铃铛红点与 Toast 提醒。
- 当前密码为明文存储，仅适用于教学/演示环境；生产使用请改为 BCrypt 等加盐哈希。
