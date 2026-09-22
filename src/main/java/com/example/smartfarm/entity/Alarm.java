package com.example.smartfarm.entity;

import lombok.Data;

import java.util.Date;

/**
 * 告警记录。level=HIGH 且 pushStatus=1 时才会向前端推送通知，
 * MEDIUM/LOW 仅在页面告警列表留痕。
 */
@Data
public class Alarm {
    private Integer id;
    private Integer farmlandId;
    private Integer deviceId;
    private String alarmType;
    private String alarmLevel;
    private String title;
    private String content;
    private String metric;
    private Double metricValue;
    private String status;
    private Integer pushStatus;
    private Date pushTime;
    private Integer handledBy;
    private Date handledTime;
    private String handleRemark;
    private Date createdAt;

    // ---------- 关联展示字段 ----------
    private String farmlandName;
    private String deviceName;
    private String deviceCode;
    private String handledByName;
}
