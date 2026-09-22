package com.example.smartfarm.mapper;

import com.example.smartfarm.entity.IrrigationRecord;
import org.apache.ibatis.annotations.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Mapper
public interface IrrigationRecordMapper {

    @Insert("INSERT INTO irrigation_records(farmland_id, strategy_id, decision_id, trigger_type, water_amount, " +
            "duration_minutes, fertilizer_recipe_id, status, start_time, end_time, operator_id, remark, created_at) " +
            "VALUES(#{farmlandId}, #{strategyId}, #{decisionId}, #{triggerType}, #{waterAmount}, " +
            "#{durationMinutes}, #{fertilizerRecipeId}, #{status}, #{startTime}, #{endTime}, #{operatorId}, " +
            "#{remark}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(IrrigationRecord record);

    @Select("SELECT r.*, f.name AS farmland_name, u.username AS operator_name, " +
            "       fr.name AS fertilizer_name, s.name AS strategy_name " +
            "FROM irrigation_records r " +
            "LEFT JOIN farmlands f ON r.farmland_id = f.id " +
            "LEFT JOIN users u ON r.operator_id = u.id " +
            "LEFT JOIN fertilizer_recipes fr ON r.fertilizer_recipe_id = fr.id " +
            "LEFT JOIN irrigation_strategies s ON r.strategy_id = s.id " +
            "WHERE r.farmland_id = #{farmlandId} " +
            "ORDER BY r.created_at DESC LIMIT #{limit}")
    List<IrrigationRecord> findByFarmland(@Param("farmlandId") Integer farmlandId, @Param("limit") Integer limit);

    @Select("SELECT r.*, f.name AS farmland_name, u.username AS operator_name, " +
            "       fr.name AS fertilizer_name, s.name AS strategy_name " +
            "FROM irrigation_records r " +
            "LEFT JOIN farmlands f ON r.farmland_id = f.id " +
            "LEFT JOIN users u ON r.operator_id = u.id " +
            "LEFT JOIN fertilizer_recipes fr ON r.fertilizer_recipe_id = fr.id " +
            "LEFT JOIN irrigation_strategies s ON r.strategy_id = s.id " +
            "WHERE f.user_id = #{userId} " +
            "ORDER BY r.created_at DESC LIMIT #{limit}")
    List<IrrigationRecord> findByUser(@Param("userId") Integer userId, @Param("limit") Integer limit);

    @Select("<script>" +
            "SELECT r.*, f.name AS farmland_name, u.username AS operator_name, " +
            "       fr.name AS fertilizer_name, s.name AS strategy_name " +
            "FROM irrigation_records r " +
            "LEFT JOIN farmlands f ON r.farmland_id = f.id " +
            "LEFT JOIN users u ON r.operator_id = u.id " +
            "LEFT JOIN fertilizer_recipes fr ON r.fertilizer_recipe_id = fr.id " +
            "LEFT JOIN irrigation_strategies s ON r.strategy_id = s.id " +
            "<where>" +
            "  <if test='farmlandId != null'> AND r.farmland_id = #{farmlandId} </if>" +
            "  <if test='triggerType != null and triggerType != \"\"'> AND r.trigger_type = #{triggerType} </if>" +
            "  <if test='status != null and status != \"\"'> AND r.status = #{status} </if>" +
            "</where>" +
            "ORDER BY r.created_at DESC LIMIT #{limit}" +
            "</script>")
    List<IrrigationRecord> findAll(@Param("farmlandId") Integer farmlandId,
                                   @Param("triggerType") String triggerType,
                                   @Param("status") String status,
                                   @Param("limit") Integer limit);

    @Select("SELECT * FROM irrigation_records WHERE id = #{id}")
    IrrigationRecord findById(Integer id);

    @Update("UPDATE irrigation_records SET status = #{status}, end_time = #{endTime} WHERE id = #{id}")
    int updateStatus(@Param("id") Integer id,
                     @Param("status") String status,
                     @Param("endTime") Date endTime);

    /** 自动灌溉冷却判断：该地块最近一次已完成的灌溉时间 */
    @Select("SELECT MAX(created_at) FROM irrigation_records " +
            "WHERE farmland_id = #{farmlandId} AND status IN ('PENDING', 'RUNNING', 'SUCCESS')")
    Date findLastIrrigationTime(Integer farmlandId);

    /** 该地块是否已有进行中的灌溉任务 */
    @Select("SELECT COUNT(*) FROM irrigation_records " +
            "WHERE farmland_id = #{farmlandId} AND status IN ('PENDING', 'RUNNING')")
    int countRunning(Integer farmlandId);

    /**
     * 统计：灌溉次数与累计用水量。
     * 列别名统一使用小写下划线命名——MyBatis 返回 Map 时按列标签原样作 key，
     * 大小写混排的别名在 H2(DATABASE_TO_LOWER) 下会取值失败。
     */
    @Select("<script>" +
            "SELECT COUNT(*) AS record_count, " +
            "       COALESCE(SUM(CASE WHEN r.status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success_count, " +
            "       COALESCE(SUM(CASE WHEN r.status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_count, " +
            "       COALESCE(SUM(CASE WHEN r.status = 'SUCCESS' THEN r.water_amount ELSE 0 END), 0) AS total_water, " +
            "       COALESCE(SUM(CASE WHEN r.status = 'SUCCESS' AND r.trigger_type = 'AUTO' THEN 1 ELSE 0 END), 0) AS auto_count, " +
            "       COALESCE(SUM(CASE WHEN r.status = 'SUCCESS' AND r.trigger_type = 'MANUAL' THEN 1 ELSE 0 END), 0) AS manual_count " +
            "FROM irrigation_records r " +
            "LEFT JOIN farmlands f ON r.farmland_id = f.id " +
            "WHERE r.created_at >= #{since} " +
            "<if test='farmlandId != null'> AND r.farmland_id = #{farmlandId} </if>" +
            "<if test='userId != null'> AND f.user_id = #{userId} </if>" +
            "</script>")
    Map<String, Object> stats(@Param("farmlandId") Integer farmlandId,
                              @Param("userId") Integer userId,
                              @Param("since") Date since);

    /** 按天统计灌溉用水量，用于历史统计图表 */
    @Select("<script>" +
            "SELECT CAST(r.created_at AS DATE) AS stat_day, " +
            "       COUNT(*) AS irrigation_count, " +
            "       COALESCE(SUM(r.water_amount), 0) AS total_water " +
            "FROM irrigation_records r " +
            "LEFT JOIN farmlands f ON r.farmland_id = f.id " +
            "WHERE r.created_at >= #{since} AND r.status = 'SUCCESS' " +
            "<if test='farmlandId != null'> AND r.farmland_id = #{farmlandId} </if>" +
            "<if test='userId != null'> AND f.user_id = #{userId} </if>" +
            "GROUP BY CAST(r.created_at AS DATE) " +
            "ORDER BY stat_day ASC" +
            "</script>")
    List<Map<String, Object>> statWaterByDay(@Param("farmlandId") Integer farmlandId,
                                             @Param("userId") Integer userId,
                                             @Param("since") Date since);

    /** 删除某地块的全部灌溉记录（删除地块时清理关联数据） */
    @Delete("DELETE FROM irrigation_records WHERE farmland_id = #{farmlandId}")
    int deleteByFarmland(Integer farmlandId);

    @Select("SELECT COUNT(*) FROM irrigation_records")
    int countAll();
}
