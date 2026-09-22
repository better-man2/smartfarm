package com.example.smartfarm.mapper;

import com.example.smartfarm.entity.IrrigationDecision;
import org.apache.ibatis.annotations.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Mapper
public interface IrrigationDecisionMapper {

    @Insert("INSERT INTO irrigation_decisions(farmland_id, decision_time, recommended_water, " +
            "decision_result, reason, confidence, trigger_source) " +
            "VALUES(#{farmlandId}, #{decisionTime}, #{recommendedWater}, #{decisionResult}, " +
            "#{reason}, #{confidence}, #{triggerSource})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(IrrigationDecision decision);

    @Select("SELECT * FROM irrigation_decisions " +
            "WHERE farmland_id = #{farmlandId} " +
            "ORDER BY decision_time DESC " +
            "LIMIT #{limit}")
    List<IrrigationDecision> getHistory(@Param("farmlandId") Integer farmlandId, @Param("limit") Integer limit);

    /** 农户视角：自己全部地块的决策记录 */
    @Select("SELECT d.*, f.name AS farmland_name FROM irrigation_decisions d " +
            "LEFT JOIN farmlands f ON d.farmland_id = f.id " +
            "WHERE f.user_id = #{userId} " +
            "ORDER BY d.decision_time DESC " +
            "LIMIT #{limit}")
    List<IrrigationDecision> getHistoryByUser(@Param("userId") Integer userId, @Param("limit") Integer limit);

    /** 管理端视角：全部决策记录 */
    @Select("SELECT d.*, f.name AS farmland_name FROM irrigation_decisions d " +
            "LEFT JOIN farmlands f ON d.farmland_id = f.id " +
            "ORDER BY d.decision_time DESC " +
            "LIMIT #{limit}")
    List<IrrigationDecision> getAllHistory(@Param("limit") Integer limit);

    @Update("UPDATE irrigation_decisions SET actual_water = #{actualWater} WHERE id = #{id}")
    int updateActualWater(@Param("id") Integer id, @Param("actualWater") Double actualWater);

    /** 删除某地块的全部决策记录（删除地块时清理关联数据） */
    @Delete("DELETE FROM irrigation_decisions WHERE farmland_id = #{farmlandId}")
    int deleteByFarmland(Integer farmlandId);

    /**
     * 统计：决策次数与建议水量（farmlandId 为 null 时统计全部，userId 为 null 时不限农户）。
     * 列别名统一使用小写下划线命名，避免 H2(DATABASE_TO_LOWER) 下 Map 取值失败。
     */
    @Select("<script>" +
            "SELECT COUNT(*) AS decision_count, " +
            "       COALESCE(SUM(CASE WHEN decision_result = '需要灌溉' THEN 1 ELSE 0 END), 0) AS irrigation_count, " +
            "       COALESCE(SUM(recommended_water), 0) AS total_recommended_water, " +
            "       COALESCE(SUM(actual_water), 0) AS total_actual_water, " +
            "       AVG(confidence) AS avg_confidence " +
            "FROM irrigation_decisions d " +
            "WHERE d.decision_time >= #{since} " +
            "<if test='farmlandId != null'> AND d.farmland_id = #{farmlandId} </if>" +
            "<if test='userId != null'> AND d.farmland_id IN (SELECT f.id FROM farmlands f WHERE f.user_id = #{userId}) </if>" +
            "</script>")
    Map<String, Object> stats(@Param("farmlandId") Integer farmlandId,
                              @Param("userId") Integer userId,
                              @Param("since") Date since);
}
