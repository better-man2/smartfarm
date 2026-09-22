package com.example.smartfarm.entity;

import lombok.Data;

import java.util.Date;

/**
 * 物联网设备（传感器 / 阀门 / 水肥一体机）。
 */
@Data
public class Device {
    private Integer id;
    private String deviceCode;
    private String deviceName;
    private String deviceType;
    private Integer farmlandId;
    private String status;
    private String installLocation;
    private Date lastOnlineTime;
    private String firmware;
    private String remark;
    private Date createdAt;
    private Date updatedAt;

    // ---------- 关联展示字段 ----------
    private String farmlandName;
    private String ownerName;
    /** 与当前时间的间隔分钟数，用于判断心跳是否超时 */
    private Long offlineMinutes;
}
