package com.example.smartfarm.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Date;

/**
 * 水肥配比方案（P2）：灌溉时可选择携带该配方，实现水肥一体化。
 */
@Data
public class FertilizerRecipe {
    private Integer id;
    private String name;
    private Integer farmlandId;
    private String cropType;

    /**
     * N、P、K 比例。
     * Lombok 生成的 getter 为 getNRatio()，Jackson 默认会推导出 "NRatio" 这样的属性名，
     * 与前端约定的 nRatio 不一致，因此显式指定 JSON 字段名。
     */
    @JsonProperty("nRatio")
    private Double nRatio;          // 氮比例
    @JsonProperty("pRatio")
    private Double pRatio;          // 磷比例
    @JsonProperty("kRatio")
    private Double kRatio;          // 钾比例

    private Double ecTarget;        // 目标电导率 mS/cm
    private Double phTarget;        // 目标 pH
    private Double concentration;   // 母液浓度 %
    private Integer enabled;
    private Date createdAt;
    private Date updatedAt;

    // ---------- 关联展示字段 ----------
    private String farmlandName;
}
