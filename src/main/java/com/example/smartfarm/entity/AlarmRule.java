package com.example.smartfarm.entity;

import lombok.Data;

import java.util.Date;

/**
 * 告警阈值规则，由管理员后台配置。
 * farmlandId 为空表示全局规则；pushEnabled=1 才会触发通知推送。
 */
@Data
public class AlarmRule {
    private Integer id;
    private String ruleName;
    private String metric;         // SOIL_HUMIDITY / TEMPERATURE / LIGHT_INTENSITY / DEVICE_OFFLINE_MINUTES
    private String compareOp;      // LT 小于 / GT 大于
    private Double threshold;
    private String alarmLevel;     // HIGH / MEDIUM / LOW
    private Integer farmlandId;
    private Integer pushEnabled;
    private Integer enabled;
    private String description;
    private Date createdAt;
    private Date updatedAt;

    // ---------- 关联展示字段 ----------
    private String farmlandName;
}
