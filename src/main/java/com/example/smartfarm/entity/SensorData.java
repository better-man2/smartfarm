package com.example.smartfarm.entity;

import lombok.Data;

import java.util.Date;

@Data
public class SensorData {
    private Integer id;
    private Integer farmlandId;
    private Integer deviceId;
    private Double soilHumidity;    // 土壤湿度 %
    private Double temperature;     // 温度 ℃
    private Double lightIntensity;  // 光照强度 lux
    private Date collectTime;

    // ---------- 关联展示字段 ----------
    private String farmlandName;
    private String deviceCode;
}
