package com.example.smartfarm.entity;

import lombok.Data;

import java.util.Date;

/**
 * 灌溉策略。isGlobal=1 为管理员配置的全局策略，
 * 否则为针对某地块的专属策略。
 */
@Data
public class IrrigationStrategy {
    private Integer id;
    private String name;
    private Integer farmlandId;
    private Integer isGlobal;
    private Double soilHumidityMin;     // 湿度低于该值触发灌溉
    private Double targetHumidity;      // 灌溉目标湿度
    private Double temperatureMax;      // 高于该温度不自动灌溉
    private Double lightIntensityMax;   // 高于该光照不自动灌溉
    private Double irrigationAmount;    // 单次灌溉量 m³
    private Integer durationMinutes;    // 单次灌溉时长
    private String allowedStartTime;    // 允许灌溉时段起 HH:mm
    private String allowedEndTime;      // 允许灌溉时段止 HH:mm
    private Integer enabled;
    private Integer createdBy;
    private Date createdAt;
    private Date updatedAt;

    // ---------- 关联展示字段 ----------
    private String farmlandName;
    private String creatorName;
}
