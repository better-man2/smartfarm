package com.example.smartfarm.entity;

import lombok.Data;

import java.util.Date;

/**
 * 灌溉执行记录：手动下发的指令与自动策略触发的灌溉统一记录在此表。
 */
@Data
public class IrrigationRecord {
    private Integer id;
    private Integer farmlandId;
    private Integer strategyId;
    private Integer decisionId;
    private String triggerType;     // MANUAL 手动 / AUTO 自动
    private Double waterAmount;     // 灌溉量 m³
    private Integer durationMinutes;
    private Integer fertilizerRecipeId;
    private String status;          // PENDING/RUNNING/SUCCESS/FAILED/CANCELLED
    private Date startTime;
    private Date endTime;
    private Integer operatorId;
    private String remark;
    private Date createdAt;

    // ---------- 关联展示字段 ----------
    private String farmlandName;
    private String operatorName;
    private String fertilizerName;
    private String strategyName;
}
