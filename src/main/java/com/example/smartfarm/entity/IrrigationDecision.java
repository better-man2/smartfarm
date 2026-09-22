package com.example.smartfarm.entity;

import lombok.Data;

import java.util.Date;

@Data
public class IrrigationDecision {
    private Integer id;
    private Integer farmlandId;
    private Date decisionTime;
    private Double recommendedWater; // 建议灌溉量 m³
    private Double actualWater;      // 实际灌溉量 m³
    private String decisionResult;   // 需要灌溉 / 无需灌溉
    private String reason;           // 决策原因
    private Double confidence;       // 决策置信度 %
    private String triggerSource;    // MANUAL 人工触发 / AUTO 自动策略

    // ---------- 关联展示字段 ----------
    private String farmlandName;
}
