package com.example.smartfarm.common;

/**
 * 系统常量：角色、设备类型与状态、告警等级与处理状态、灌溉触发方式等。
 * 使用字符串常量而非枚举，与数据库中 VARCHAR 存储保持一致。
 */
public final class Constants {

    private Constants() {
    }

    // ---------- 角色 ----------
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_FARMER = "farmer";

    // ---------- 用户启用状态 ----------
    public static final int USER_ENABLED = 1;
    public static final int USER_DISABLED = 0;

    // ---------- 设备类型 ----------
    public static final String DEVICE_SOIL_MOISTURE = "SOIL_MOISTURE";       // 土壤湿度传感器
    public static final String DEVICE_TEMPERATURE = "TEMPERATURE";           // 温度传感器
    public static final String DEVICE_LIGHT = "LIGHT";                       // 光照传感器
    public static final String DEVICE_WEATHER = "WEATHER";                   // 气象站
    public static final String DEVICE_IRRIGATION_VALVE = "IRRIGATION_VALVE"; // 灌溉阀门
    public static final String DEVICE_FERTILIZER_MIXER = "FERTILIZER_MIXER"; // 水肥一体机

    // ---------- 设备状态 ----------
    public static final String DEVICE_ONLINE = "ONLINE";
    public static final String DEVICE_OFFLINE = "OFFLINE";
    public static final String DEVICE_FAULT = "FAULT";
    public static final String DEVICE_MAINTENANCE = "MAINTENANCE";
    public static final String DEVICE_UNBOUND = "UNBOUND";

    // ---------- 告警等级：仅 HIGH 会推送通知 ----------
    public static final String ALARM_HIGH = "HIGH";
    public static final String ALARM_MEDIUM = "MEDIUM";
    public static final String ALARM_LOW = "LOW";

    // ---------- 告警处理状态 ----------
    public static final String ALARM_PENDING = "PENDING";
    public static final String ALARM_PROCESSING = "PROCESSING";
    public static final String ALARM_RESOLVED = "RESOLVED";

    // ---------- 告警类型 ----------
    public static final String ALARM_TYPE_DEVICE_OFFLINE = "DEVICE_OFFLINE";
    public static final String ALARM_TYPE_SENSOR_ABNORMAL = "SENSOR_ABNORMAL";
    public static final String ALARM_TYPE_SOIL_DRY = "SOIL_DRY";
    public static final String ALARM_TYPE_TEMP_HIGH = "TEMP_HIGH";
    public static final String ALARM_TYPE_LIGHT_ABNORMAL = "LIGHT_ABNORMAL";
    public static final String ALARM_TYPE_IRRIGATION_FAIL = "IRRIGATION_FAIL";

    // ---------- 告警指标 ----------
    public static final String METRIC_SOIL_HUMIDITY = "SOIL_HUMIDITY";
    public static final String METRIC_TEMPERATURE = "TEMPERATURE";
    public static final String METRIC_LIGHT_INTENSITY = "LIGHT_INTENSITY";
    public static final String METRIC_DEVICE_OFFLINE_MINUTES = "DEVICE_OFFLINE_MINUTES";

    // ---------- 比较符 ----------
    public static final String OP_LT = "LT";
    public static final String OP_GT = "GT";

    // ---------- 灌溉触发方式 ----------
    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_AUTO = "AUTO";

    // ---------- 灌溉任务状态 ----------
    public static final String IRRIGATION_PENDING = "PENDING";
    public static final String IRRIGATION_RUNNING = "RUNNING";
    public static final String IRRIGATION_SUCCESS = "SUCCESS";
    public static final String IRRIGATION_FAILED = "FAILED";
    public static final String IRRIGATION_CANCELLED = "CANCELLED";

    // ---------- 会话属性名 ----------
    public static final String SESSION_USER_ID = "userId";
    public static final String SESSION_USERNAME = "username";
    public static final String SESSION_ROLE = "role";
}
