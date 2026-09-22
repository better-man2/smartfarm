package com.example.smartfarm.mapper;

import com.example.smartfarm.entity.Farmland;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface FarmlandMapper {

    @Select("SELECT f.*, u.username, s.name AS strategy_name " +
            "FROM farmlands f " +
            "LEFT JOIN users u ON f.user_id = u.id " +
            "LEFT JOIN irrigation_strategies s ON f.strategy_id = s.id " +
            "WHERE f.user_id = #{userId} " +
            "ORDER BY f.id ASC")
    List<Farmland> findByUserId(Integer userId);

    @Select("SELECT f.*, u.username, s.name AS strategy_name " +
            "FROM farmlands f " +
            "LEFT JOIN users u ON f.user_id = u.id " +
            "LEFT JOIN irrigation_strategies s ON f.strategy_id = s.id " +
            "WHERE f.id = #{id}")
    Farmland findById(Integer id);

    /** 管理端：全部地块汇总（含设备数、在线设备数、待处理告警数） */
    @Select("SELECT f.*, u.username, s.name AS strategy_name, " +
            "  (SELECT COUNT(*) FROM devices d WHERE d.farmland_id = f.id) AS device_count, " +
            "  (SELECT COUNT(*) FROM devices d WHERE d.farmland_id = f.id AND d.status = 'ONLINE') AS online_device_count, " +
            "  (SELECT COUNT(*) FROM alarms a WHERE a.farmland_id = f.id AND a.status <> 'RESOLVED') AS pending_alarm_count " +
            "FROM farmlands f " +
            "LEFT JOIN users u ON f.user_id = u.id " +
            "LEFT JOIN irrigation_strategies s ON f.strategy_id = s.id " +
            "ORDER BY f.id ASC")
    List<Farmland> findAllWithStats();

    /** 自动灌溉巡检：所有开启了自动模式的地块 */
    @Select("SELECT f.*, u.username, s.name AS strategy_name " +
            "FROM farmlands f " +
            "LEFT JOIN users u ON f.user_id = u.id " +
            "LEFT JOIN irrigation_strategies s ON f.strategy_id = s.id " +
            "WHERE f.auto_irrigation = 1 " +
            "ORDER BY f.id ASC")
    List<Farmland> findAutoEnabled();

    @Insert("INSERT INTO farmlands(user_id, name, area, crop_type, location, auto_irrigation, strategy_id, created_at) " +
            "VALUES(#{userId}, #{name}, #{area}, #{cropType}, #{location}, #{autoIrrigation}, #{strategyId}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Farmland farmland);

    @Update("UPDATE farmlands SET name=#{name}, area=#{area}, crop_type=#{cropType}, " +
            "location=#{location} WHERE id=#{id}")
    int update(Farmland farmland);

    @Update("UPDATE farmlands SET auto_irrigation = #{autoIrrigation} WHERE id = #{id}")
    int updateAutoIrrigation(@Param("id") Integer id, @Param("autoIrrigation") Integer autoIrrigation);

    @Update("UPDATE farmlands SET strategy_id = #{strategyId} WHERE id = #{id}")
    int updateStrategy(@Param("id") Integer id, @Param("strategyId") Integer strategyId);

    @Update("UPDATE farmlands SET user_id = #{userId} WHERE id = #{id}")
    int updateOwner(@Param("id") Integer id, @Param("userId") Integer userId);

    @Delete("DELETE FROM farmlands WHERE id = #{id}")
    int delete(Integer id);

    @Select("SELECT COUNT(*) FROM farmlands")
    int countAll();
}
