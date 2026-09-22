package com.example.smartfarm.mapper;

import com.example.smartfarm.entity.SensorData;
import org.apache.ibatis.annotations.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Mapper
public interface SensorDataMapper {

    /** 最新 N 条采集数据（默认取 10 条） */
    @Select("SELECT * FROM sensor_data " +
            "WHERE farmland_id = #{farmlandId} " +
            "ORDER BY collect_time DESC " +
            "LIMIT #{limit}")
    List<SensorData> getLatestData(@Param("farmlandId") Integer farmlandId, @Param("limit") Integer limit);

    /** 最新一条数据 */
    @Select("SELECT * FROM sensor_data " +
            "WHERE farmland_id = #{farmlandId} " +
            "ORDER BY collect_time DESC LIMIT 1")
    SensorData getLatestOne(Integer farmlandId);

    /**
     * 时间段内的采集数据（趋势图）。
     * 时间边界由 Java 侧计算后传入，避免使用各数据库方言的日期函数。
     */
    @Select("SELECT * FROM sensor_data " +
            "WHERE farmland_id = #{farmlandId} " +
            "AND collect_time >= #{since} " +
            "ORDER BY collect_time ASC")
    List<SensorData> getDataSince(@Param("farmlandId") Integer farmlandId, @Param("since") Date since);

    @Insert("INSERT INTO sensor_data(farmland_id, device_id, soil_humidity, temperature, light_intensity, collect_time) " +
            "VALUES(#{farmlandId}, #{deviceId}, #{soilHumidity}, #{temperature}, #{lightIntensity}, #{collectTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SensorData data);

    /**
     * 按天聚合，用于历史统计（返回列：stat_day / avg_humidity / avg_temperature / avg_light / sample_count）
     * 注意：聚合结果的列别名一律使用小写下划线命名。
     * H2 在 DATABASE_TO_LOWER=TRUE 下会把别名转成小写，而 MyBatis 返回 Map 时按列标签原样作 key，
     * 使用大小写混排的别名会导致取值为 null。
     */
    @Select("SELECT CAST(collect_time AS DATE) AS stat_day, " +
            "       AVG(soil_humidity) AS avg_humidity, " +
            "       AVG(temperature) AS avg_temperature, " +
            "       AVG(light_intensity) AS avg_light, " +
            "       COUNT(*) AS sample_count " +
            "FROM sensor_data " +
            "WHERE farmland_id = #{farmlandId} AND collect_time >= #{since} " +
            "GROUP BY CAST(collect_time AS DATE) " +
            "ORDER BY stat_day ASC")
    List<Map<String, Object>> statByDay(@Param("farmlandId") Integer farmlandId, @Param("since") Date since);

    /** 时间段内的汇总指标 */
    @Select("SELECT COUNT(*) AS sample_count, " +
            "       AVG(soil_humidity) AS avg_humidity, " +
            "       MIN(soil_humidity) AS min_humidity, " +
            "       MAX(soil_humidity) AS max_humidity, " +
            "       AVG(temperature) AS avg_temperature, " +
            "       MAX(temperature) AS max_temperature, " +
            "       AVG(light_intensity) AS avg_light " +
            "FROM sensor_data " +
            "WHERE farmland_id = #{farmlandId} AND collect_time >= #{since}")
    Map<String, Object> summary(@Param("farmlandId") Integer farmlandId, @Param("since") Date since);

    /** 全部地块的汇总指标（管理端全局看板），farmlandId 传 null 表示不限地块 */
    @Select("<script>" +
            "SELECT COUNT(*) AS sample_count, " +
            "       AVG(soil_humidity) AS avg_humidity, " +
            "       MIN(soil_humidity) AS min_humidity, " +
            "       MAX(soil_humidity) AS max_humidity, " +
            "       AVG(temperature) AS avg_temperature, " +
            "       MAX(temperature) AS max_temperature, " +
            "       AVG(light_intensity) AS avg_light " +
            "FROM sensor_data " +
            "WHERE collect_time >= #{since} " +
            "<if test='farmlandId != null'> AND farmland_id = #{farmlandId} </if>" +
            "</script>")
    Map<String, Object> summaryMulti(@Param("farmlandId") Integer farmlandId, @Param("since") Date since);

    /** 清理指定时间之前的历史数据，返回删除行数 */
    @Delete("DELETE FROM sensor_data WHERE collect_time < #{before}")
    int deleteBefore(Date before);

    /** 删除某地块的全部监测数据（删除地块时清理关联数据） */
    @Delete("DELETE FROM sensor_data WHERE farmland_id = #{farmlandId}")
    int deleteByFarmland(Integer farmlandId);

    @Select("SELECT COUNT(*) FROM sensor_data")
    int countAll();
}
