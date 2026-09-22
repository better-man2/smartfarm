package com.example.smartfarm.mapper;

import com.example.smartfarm.entity.AlarmRule;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AlarmRuleMapper {

    /** 查询规则，farmlandId 为空时返回全部（管理端） */
    @Select("<script>" +
            "SELECT r.*, f.name AS farmland_name FROM alarm_rules r " +
            "LEFT JOIN farmlands f ON r.farmland_id = f.id " +
            "<where>" +
            "  <if test='farmlandId != null'> AND r.farmland_id = #{farmlandId} </if>" +
            "  <if test='onlyEnabled'> AND r.enabled = 1 </if>" +
            "</where>" +
            "ORDER BY r.metric ASC, r.alarm_level ASC" +
            "</script>")
    List<AlarmRule> findRules(@Param("farmlandId") Integer farmlandId,
                              @Param("onlyEnabled") boolean onlyEnabled);

    /**
     * 告警引擎使用：取适用于某地块的全部启用规则
     * （全局规则 + 该地块专属规则）。
     */
    @Select("SELECT * FROM alarm_rules " +
            "WHERE enabled = 1 AND (farmland_id IS NULL OR farmland_id = #{farmlandId}) " +
            "ORDER BY rule_name ASC")
    List<AlarmRule> findEffectiveRules(Integer farmlandId);

    @Select("SELECT * FROM alarm_rules WHERE id = #{id}")
    AlarmRule findById(Integer id);

    @Insert("INSERT INTO alarm_rules(rule_name, metric, compare_op, threshold, alarm_level, farmland_id, " +
            "push_enabled, enabled, description, created_at, updated_at) " +
            "VALUES(#{ruleName}, #{metric}, #{compareOp}, #{threshold}, #{alarmLevel}, #{farmlandId}, " +
            "#{pushEnabled}, #{enabled}, #{description}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AlarmRule rule);

    @Update("UPDATE alarm_rules SET rule_name = #{ruleName}, metric = #{metric}, compare_op = #{compareOp}, " +
            "threshold = #{threshold}, alarm_level = #{alarmLevel}, farmland_id = #{farmlandId}, " +
            "push_enabled = #{pushEnabled}, enabled = #{enabled}, description = #{description}, " +
            "updated_at = #{updatedAt} WHERE id = #{id}")
    int update(AlarmRule rule);

    @Delete("DELETE FROM alarm_rules WHERE id = #{id}")
    int delete(Integer id);

    @Select("SELECT COUNT(*) FROM alarm_rules")
    int countAll();
}
