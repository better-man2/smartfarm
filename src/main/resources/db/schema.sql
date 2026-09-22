-- ============================================================
-- 智慧农业精准灌溉系统 - 建表脚本
-- 说明：脚本使用 CREATE TABLE IF NOT EXISTS，可重复执行；
--       未使用 COMMENT / ENGINE / KEY 等方言语法，
--       H2(MODE=MySQL) 与 MySQL 8 均可直接执行。
-- 注意：不使用外键约束（与原项目风格一致），完整性由应用层保证。
-- ============================================================

-- ---------- 1. 用户（管理员 / 农户） ----------
CREATE TABLE IF NOT EXISTS users (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL,
    password    VARCHAR(100) NOT NULL,
    role        VARCHAR(20)  NOT NULL DEFAULT 'farmer',  -- admin 管理员 / farmer 农户
    phone       VARCHAR(20),
    status      TINYINT      NOT NULL DEFAULT 1,         -- 1 启用 / 0 禁用
    created_at  DATETIME     NOT NULL,
    CONSTRAINT uk_users_username UNIQUE (username)
);

-- ---------- 2. 地块 ----------
CREATE TABLE IF NOT EXISTS farmlands (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    user_id           INT           NOT NULL,            -- 归属农户
    name              VARCHAR(100)  NOT NULL,
    area              DECIMAL(10,2),                     -- 面积(亩)
    crop_type         VARCHAR(50),                       -- 作物类型
    location          VARCHAR(200),
    auto_irrigation   TINYINT       NOT NULL DEFAULT 0,  -- 1 自动灌溉模式 / 0 手动模式
    strategy_id       INT,                               -- 绑定的灌溉策略(空则用全局策略)
    created_at        DATETIME      NOT NULL
);

-- ---------- 3. 物联网设备 ----------
CREATE TABLE IF NOT EXISTS devices (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    device_code       VARCHAR(50)  NOT NULL,             -- 设备编号，如 SM-001
    device_name       VARCHAR(100) NOT NULL,
    device_type       VARCHAR(30)  NOT NULL,             -- SOIL_MOISTURE/TEMPERATURE/LIGHT/WEATHER/IRRIGATION_VALVE/FERTILIZER_MIXER
    farmland_id       INT,                               -- 绑定的地块，空表示未绑定
    status            VARCHAR(20)  NOT NULL DEFAULT 'UNBOUND', -- ONLINE/OFFLINE/FAULT/MAINTENANCE/UNBOUND
    install_location  VARCHAR(200),
    last_online_time  DATETIME,
    firmware          VARCHAR(50),
    remark            VARCHAR(500),
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME,
    CONSTRAINT uk_devices_code UNIQUE (device_code)
);

-- ---------- 4. 传感器采集数据 ----------
CREATE TABLE IF NOT EXISTS sensor_data (
    id               INT AUTO_INCREMENT PRIMARY KEY,
    farmland_id      INT           NOT NULL,
    device_id        INT,                                -- 采集设备
    soil_humidity    DECIMAL(5,2),                       -- 土壤湿度 %
    temperature      DECIMAL(5,2),                       -- 温度 ℃
    light_intensity  DECIMAL(9,2),                       -- 光照强度 lux
    collect_time     DATETIME      NOT NULL
);

-- ---------- 5. 灌溉策略（全局策略 farmland_id 为空） ----------
CREATE TABLE IF NOT EXISTS irrigation_strategies (
    id                   INT AUTO_INCREMENT PRIMARY KEY,
    name                 VARCHAR(100) NOT NULL,
    farmland_id          INT,                            -- 空 = 全局策略
    is_global            TINYINT      NOT NULL DEFAULT 0,
    soil_humidity_min    DECIMAL(5,2) NOT NULL DEFAULT 40.00, -- 湿度低于该值触发灌溉
    target_humidity      DECIMAL(5,2) NOT NULL DEFAULT 65.00, -- 灌溉目标湿度
    temperature_max      DECIMAL(5,2) NOT NULL DEFAULT 38.00, -- 高于该温度不自动灌溉
    light_intensity_max  DECIMAL(9,2) NOT NULL DEFAULT 60000.00, -- 高于该光照不自动灌溉(减少蒸发损失)
    irrigation_amount    DECIMAL(10,2) NOT NULL DEFAULT 15.00,  -- 单次灌溉量 m³
    duration_minutes     INT          NOT NULL DEFAULT 10,      -- 单次灌溉时长(分钟)
    allowed_start_time   VARCHAR(5)   NOT NULL DEFAULT '06:00', -- 允许灌溉时段起 HH:mm
    allowed_end_time     VARCHAR(5)   NOT NULL DEFAULT '20:00', -- 允许灌溉时段止 HH:mm
    enabled              TINYINT      NOT NULL DEFAULT 1,
    created_by           INT,
    created_at           DATETIME     NOT NULL,
    updated_at           DATETIME
);

-- ---------- 6. 灌溉决策记录 ----------
CREATE TABLE IF NOT EXISTS irrigation_decisions (
    id                 INT AUTO_INCREMENT PRIMARY KEY,
    farmland_id        INT           NOT NULL,
    decision_time      DATETIME      NOT NULL,
    recommended_water  DECIMAL(10,2),                    -- 建议灌溉量 m³
    actual_water       DECIMAL(10,2),                    -- 实际灌溉量 m³
    decision_result    VARCHAR(50),                      -- 需要灌溉 / 无需灌溉
    reason             VARCHAR(500),
    confidence         DECIMAL(5,2),                     -- 决策置信度 %
    trigger_source     VARCHAR(20)                       -- MANUAL 人工 / AUTO 自动
);

-- ---------- 7. 灌溉执行记录（手动下发 + 自动执行） ----------
CREATE TABLE IF NOT EXISTS irrigation_records (
    id                    INT AUTO_INCREMENT PRIMARY KEY,
    farmland_id           INT           NOT NULL,
    strategy_id           INT,
    decision_id           INT,
    trigger_type          VARCHAR(20)   NOT NULL,        -- MANUAL / AUTO
    water_amount          DECIMAL(10,2) NOT NULL,
    duration_minutes      INT,
    fertilizer_recipe_id  INT,                           -- 关联水肥配比方案
    status                VARCHAR(20)   NOT NULL,        -- PENDING/RUNNING/SUCCESS/FAILED/CANCELLED
    start_time            DATETIME,
    end_time              DATETIME,
    operator_id           INT,
    remark                VARCHAR(500),
    created_at            DATETIME      NOT NULL
);

-- ---------- 8. 水肥配比方案 ----------
CREATE TABLE IF NOT EXISTS fertilizer_recipes (
    id             INT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    farmland_id    INT,                                  -- 空 = 通用方案
    crop_type      VARCHAR(50),
    n_ratio        DECIMAL(5,2) NOT NULL DEFAULT 1.00,   -- 氮比例
    p_ratio        DECIMAL(5,2) NOT NULL DEFAULT 1.00,   -- 磷比例
    k_ratio        DECIMAL(5,2) NOT NULL DEFAULT 1.00,   -- 钾比例
    ec_target      DECIMAL(6,2),                         -- 目标电导率 mS/cm
    ph_target      DECIMAL(4,2),                         -- 目标 pH
    concentration  DECIMAL(6,2),                         -- 母液浓度 %
    enabled        TINYINT      NOT NULL DEFAULT 1,
    created_at     DATETIME     NOT NULL,
    updated_at     DATETIME
);

-- ---------- 9. 告警规则（阈值配置，farmland_id 为空表示全局规则） ----------
CREATE TABLE IF NOT EXISTS alarm_rules (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    rule_name     VARCHAR(100) NOT NULL,
    metric        VARCHAR(50)  NOT NULL,                 -- SOIL_HUMIDITY/TEMPERATURE/LIGHT_INTENSITY/DEVICE_OFFLINE_MINUTES
    compare_op    VARCHAR(10)  NOT NULL,                 -- LT 小于 / GT 大于
    threshold     DECIMAL(10,2) NOT NULL,
    alarm_level   VARCHAR(10)  NOT NULL,                 -- HIGH/MEDIUM/LOW
    farmland_id   INT,
    push_enabled  TINYINT      NOT NULL DEFAULT 0,       -- 1 才推送通知，0 仅页面记录
    enabled       TINYINT      NOT NULL DEFAULT 1,
    description   VARCHAR(300),
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME
);

-- ---------- 10. 告警记录 ----------
CREATE TABLE IF NOT EXISTS alarms (
    id             INT AUTO_INCREMENT PRIMARY KEY,
    farmland_id    INT,
    device_id      INT,
    alarm_type     VARCHAR(40)  NOT NULL,                -- DEVICE_OFFLINE/SENSOR_ABNORMAL/SOIL_DRY/TEMP_HIGH/LIGHT_ABNORMAL/IRRIGATION_FAIL
    alarm_level    VARCHAR(10)  NOT NULL,                -- HIGH/MEDIUM/LOW
    title          VARCHAR(200) NOT NULL,
    content        VARCHAR(500),
    metric         VARCHAR(50),
    metric_value   DECIMAL(10,2),
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING 待处理 / PROCESSING 处理中 / RESOLVED 已解决
    push_status    TINYINT      NOT NULL DEFAULT 0,      -- 1 已进入推送队列
    push_time      DATETIME,
    handled_by     INT,
    handled_time   DATETIME,
    handle_remark  VARCHAR(500),
    created_at     DATETIME     NOT NULL
);
