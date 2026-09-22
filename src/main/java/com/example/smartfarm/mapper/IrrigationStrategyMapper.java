package com.example.smartfarm.mapper;

import com.example.smartfarm.entity.IrrigationStrategy;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface IrrigationStrategyMapper {

    /** 策略列表，可按地块过滤（管理端传 null 查全部） */
    @Select("<script>" +
            "SELECT s.*, f.name AS farmland_name, u.username AS creator_name " +
            "FROM irrigation_strategies s " +
            "LEFT JOIN farmlands f ON s.farmland_id = f.id " +
            "LEFT JOIN users u ON s.created_by = u.id " +
            "<where>" +
            "  <if test='farmlandId != null'> AND s.farmland_id = #{farmlandId} </if>" +
            "  <if test='globalOnly'> AND s.is_global = 1 </if>" +
            "</where>" +
            "ORDER BY s.is_global DESC, s.id ASC" +
            "</script>")
    List<IrrigationStrategy> findStrategies(@Param("farmlandId") Integer farmlandId,
                                            @Param("globalOnly") boolean globalOnly);

    @Select("SELECT s.*, f.name AS farmland_name FROM irrigation_strategies s " +
            "LEFT JOIN farmlands f ON s.farmland_id = f.id " +
            "WHERE s.farmland_id = #{farmlandId} AND s.enabled = 1 " +
            "ORDER BY s.id ASC")
    List<IrrigationStrategy> findEnabledByFarmland(Integer farmlandId);

    /** 全局默认策略：取第一条启用的全局策略 */
    @Select("SELECT * FROM irrigation_strategies WHERE is_global = 1 AND enabled = 1 ORDER BY id ASC LIMIT 1")
    IrrigationStrategy findGlobalDefault();

    @Select("SELECT * FROM irrigation_strategies WHERE id = #{id}")
    IrrigationStrategy findById(Integer id);

    @Insert("INSERT INTO irrigation_strategies(name, farmland_id, is_global, soil_humidity_min, target_humidity, " +
            "temperature_max, light_intensity_max, irrigation_amount, duration_minutes, " +
            "allowed_start_time, allowed_end_time, enabled, created_by, created_at, updated_at) " +
            "VALUES(#{name}, #{farmlandId}, #{isGlobal}, #{soilHumidityMin}, #{targetHumidity}, " +
            "#{temperatureMax}, #{lightIntensityMax}, #{irrigationAmount}, #{durationMinutes}, " +
            "#{allowedStartTime}, #{allowedEndTime}, #{enabled}, #{createdBy}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(IrrigationStrategy strategy);

    @Update("UPDATE irrigation_strategies SET name = #{name}, farmland_id = #{farmlandId}, " +
            "is_global = #{isGlobal}, soil_humidity_min = #{soilHumidityMin}, target_humidity = #{targetHumidity}, " +
            "temperature_max = #{temperatureMax}, light_intensity_max = #{lightIntensityMax}, " +
            "irrigation_amount = #{irrigationAmount}, duration_minutes = #{durationMinutes}, " +
            "allowed_start_time = #{allowedStartTime}, allowed_end_time = #{allowedEndTime}, " +
            "enabled = #{enabled}, updated_at = #{updatedAt} WHERE id = #{id}")
    int update(IrrigationStrategy strategy);

    @Update("UPDATE irrigation_strategies SET enabled = #{enabled}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateEnabled(@Param("id") Integer id,
                      @Param("enabled") Integer enabled,
                      @Param("updatedAt") java.util.Date updatedAt);

    @Delete("DELETE FROM irrigation_strategies WHERE id = #{id}")
    int delete(Integer id);

    @Select("SELECT COUNT(*) FROM irrigation_strategies")
    int countAll();
}
