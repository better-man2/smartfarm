package com.example.smartfarm.entity;

import lombok.Data;

import java.util.Date;

@Data
public class Farmland {
    private Integer id;
    private Integer userId;
    private String name;
    private Double area;
    private String cropType;
    private String location;
    /** 1 自动灌溉模式 / 0 手动模式 */
    private Integer autoIrrigation;
    /** 绑定的灌溉策略，为空时使用全局策略 */
    private Integer strategyId;
    private Date createdAt;

    // ---------- 关联展示字段 ----------
    private String username;
    private String strategyName;
    private Integer deviceCount;
    private Integer onlineDeviceCount;
    private Integer pendingAlarmCount;
    private Double latestSoilHumidity;
    private Double latestTemperature;
    private Double latestLightIntensity;
    private Date latestCollectTime;
}
