package com.example.smartfarm.mapper;

import com.example.smartfarm.entity.Alarm;
import org.apache.ibatis.annotations.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Mapper
public interface AlarmMapper {

    @Insert("INSERT INTO alarms(farmland_id, device_id, alarm_type, alarm_level, title, content, " +
            "metric, metric_value, status, push_status, push_time, created_at) " +
            "VALUES(#{farmlandId}, #{deviceId}, #{alarmType}, #{alarmLevel}, #{title}, #{content}, " +
            "#{metric}, #{metricValue}, #{status}, #{pushStatus}, #{pushTime}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Alarm alarm);

    /** 农户视角：自己名下地块的告警 */
    @Select("<script>" +
            "SELECT a.*, f.name AS farmland_name, d.device_name, d.device_code, hu.username AS handled_by_name " +
            "FROM alarms a " +
            "LEFT JOIN farmlands f ON a.farmland_id = f.id " +
            "LEFT JOIN devices d ON a.device_id = d.id " +
            "LEFT JOIN users hu ON a.handled_by = hu.id " +
            "WHERE f.user_id = #{userId} " +
            "<if test='level != null and level != \"\"'> AND a.alarm_level = #{level} </if>" +
            "<if test='status != null and status != \"\"'> AND a.status = #{status} </if>" +
            "<if test='farmlandId != null'> AND a.farmland_id = #{farmlandId} </if>" +
            "ORDER BY a.created_at DESC " +
            "LIMIT #{limit}" +
            "</script>")
    List<Alarm> findForFarmer(@Param("userId") Integer userId,
                              @Param("level") String level,
                              @Param("status") String status,
                              @Param("farmlandId") Integer farmlandId,
                              @Param("limit") Integer limit);

    /** 管理端视角：全部告警 */
    @Select("<script>" +
            "SELECT a.*, f.name AS farmland_name, d.device_name, d.device_code, hu.username AS handled_by_name " +
            "FROM alarms a " +
            "LEFT JOIN farmlands f ON a.farmland_id = f.id " +
            "LEFT JOIN devices d ON a.device_id = d.id " +
            "LEFT JOIN users hu ON a.handled_by = hu.id " +
            "<where>" +
            "  <if test='level != null and level != \"\"'> AND a.alarm_level = #{level} </if>" +
            "  <if test='status != null and status != \"\"'> AND a.status = #{status} </if>" +
            "  <if test='farmlandId != null'> AND a.farmland_id = #{farmlandId} </if>" +
            "  <if test='alarmType != null and alarmType != \"\"'> AND a.alarm_type = #{alarmType} </if>" +
            "</where>" +
            "ORDER BY a.created_at DESC " +
            "LIMIT #{limit}" +
            "</script>")
    List<Alarm> findAll(@Param("level") String level,
                        @Param("status") String status,
                        @Param("farmlandId") Integer farmlandId,
                        @Param("alarmType") String alarmType,
                        @Param("limit") Integer limit);

    /**
     * 待推送队列：仅 HIGH 等级、push_status = 1 且尚未确认推送的告警。
     * 前端轮询该接口后用 /api/alarm/push/ack 确认，避免同一告警重复弹窗。
     */
    @Select("<script>" +
            "SELECT a.*, f.name AS farmland_name, d.device_name, d.device_code " +
            "FROM alarms a " +
            "LEFT JOIN farmlands f ON a.farmland_id = f.id " +
            "LEFT JOIN devices d ON a.device_id = d.id " +
            "WHERE a.alarm_level = 'HIGH' AND a.push_status = 1 AND a.push_time IS NULL " +
            "<if test='userId != null'> AND f.user_id = #{userId} </if>" +
            "ORDER BY a.created_at DESC " +
            "</script>")
    List<Alarm> findPendingPush(@Param("userId") Integer userId);

    /** ids 为服务端校验过的纯数字串，此处直接拼接 */
    @Update("UPDATE alarms SET push_time = #{time} WHERE id IN (${ids}) AND push_time IS NULL")
    int ackPushed(@Param("ids") String ids, @Param("time") Date time);

    @Update("UPDATE alarms SET status = #{status}, handled_by = #{handledBy}, " +
            "handled_time = #{handledTime}, handle_remark = #{handleRemark} WHERE id = #{id}")
    int handle(@Param("id") Integer id,
               @Param("status") String status,
               @Param("handledBy") Integer handledBy,
               @Param("handledTime") Date handledTime,
               @Param("handleRemark") String handleRemark);

    @Select("SELECT * FROM alarms WHERE id = #{id}")
    Alarm findById(Integer id);

    /** 告警去重：同地块、同指标、同等级在时间窗口内是否已存在记录 */
    @Select("<script>" +
            "SELECT COUNT(*) FROM alarms " +
            "WHERE metric = #{metric} AND alarm_level = #{level} AND created_at >= #{since} " +
            "<if test='farmlandId != null'> AND farmland_id = #{farmlandId} </if>" +
            "<if test='farmlandId == null'> AND farmland_id IS NULL </if>" +
            "</script>")
    int countRecent(@Param("farmlandId") Integer farmlandId,
                    @Param("metric") String metric,
                    @Param("level") String level,
                    @Param("since") Date since);

    /**
     * 未处理告警统计。
     * userId 为 null 时统计全平台（管理端）；传入 userId 时只统计该农户名下地块的告警，
     * 避免农户看到他人地块的告警数量。
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM alarms a LEFT JOIN farmlands f ON a.farmland_id = f.id " +
            "WHERE a.status = 'PENDING' " +
            "<if test='userId != null'> AND f.user_id = #{userId} </if>" +
            "</script>")
    int countUnread(@Param("userId") Integer userId);

    @Select("<script>" +
            "SELECT COUNT(*) FROM alarms a LEFT JOIN farmlands f ON a.farmland_id = f.id " +
            "WHERE a.status = 'PENDING' AND a.alarm_level = 'HIGH' " +
            "<if test='userId != null'> AND f.user_id = #{userId} </if>" +
            "</script>")
    int countUnreadHigh(@Param("userId") Integer userId);

    @Select("<script>" +
            "SELECT COUNT(*) FROM alarms a LEFT JOIN farmlands f ON a.farmland_id = f.id " +
            "WHERE a.status &lt;&gt; 'RESOLVED' " +
            "<if test='userId != null'> AND f.user_id = #{userId} </if>" +
            "</script>")
    int countUnresolved(@Param("userId") Integer userId);

    @Select("<script>" +
            "SELECT COUNT(*) FROM alarms a LEFT JOIN farmlands f ON a.farmland_id = f.id " +
            "WHERE a.status &lt;&gt; 'RESOLVED' AND a.alarm_level = 'HIGH' " +
            "<if test='userId != null'> AND f.user_id = #{userId} </if>" +
            "</script>")
    int countUnresolvedHigh(@Param("userId") Integer userId);

    @Select("SELECT alarm_level, COUNT(*) AS cnt FROM alarms " +
            "WHERE created_at >= #{since} GROUP BY alarm_level")
    List<Map<String, Object>> countByLevelSince(@Param("since") Date since);

    @Select("SELECT alarm_type, COUNT(*) AS cnt FROM alarms " +
            "WHERE created_at >= #{since} GROUP BY alarm_type")
    List<Map<String, Object>> countByTypeSince(@Param("since") Date since);

    @Select("SELECT COUNT(*) FROM alarms WHERE created_at >= #{since}")
    int countSince(@Param("since") Date since);

    @Select("SELECT COUNT(*) FROM alarms WHERE status = 'PENDING' AND farmland_id = #{farmlandId}")
    int countPendingByFarmland(Integer farmlandId);
}
