package com.example.smartfarm.mapper;

import com.example.smartfarm.entity.Device;
import org.apache.ibatis.annotations.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Mapper
public interface DeviceMapper {

    /** 设备列表（管理端），支持按类型/状态/地块/关键字过滤 */
    @Select("<script>" +
            "SELECT d.*, f.name AS farmland_name, u.username AS owner_name " +
            "FROM devices d " +
            "LEFT JOIN farmlands f ON d.farmland_id = f.id " +
            "LEFT JOIN users u ON f.user_id = u.id " +
            "<where>" +
            "  <if test='deviceType != null and deviceType != \"\"'> AND d.device_type = #{deviceType} </if>" +
            "  <if test='status != null and status != \"\"'> AND d.status = #{status} </if>" +
            "  <if test='farmlandId != null'> AND d.farmland_id = #{farmlandId} </if>" +
            "  <if test='keyword != null and keyword != \"\"'> " +
            "    AND (d.device_code LIKE CONCAT('%', #{keyword}, '%') " +
            "         OR d.device_name LIKE CONCAT('%', #{keyword}, '%')) </if>" +
            "</where>" +
            "ORDER BY d.id ASC" +
            "</script>")
    List<Device> findDevices(@Param("deviceType") String deviceType,
                             @Param("status") String status,
                             @Param("farmlandId") Integer farmlandId,
                             @Param("keyword") String keyword);

    /** 农户视角：自己名下地块上的设备 */
    @Select("SELECT d.*, f.name AS farmland_name FROM devices d " +
            "LEFT JOIN farmlands f ON d.farmland_id = f.id " +
            "WHERE f.user_id = #{userId} " +
            "ORDER BY d.id ASC")
    List<Device> findByUserId(Integer userId);

    @Select("SELECT d.*, f.name AS farmland_name FROM devices d " +
            "LEFT JOIN farmlands f ON d.farmland_id = f.id " +
            "WHERE d.farmland_id = #{farmlandId} " +
            "ORDER BY d.id ASC")
    List<Device> findByFarmlandId(Integer farmlandId);

    @Select("SELECT * FROM devices WHERE id = #{id}")
    Device findById(Integer id);

    @Select("SELECT * FROM devices WHERE device_code = #{deviceCode}")
    Device findByCode(String deviceCode);

    @Insert("INSERT INTO devices(device_code, device_name, device_type, farmland_id, status, " +
            "install_location, last_online_time, firmware, remark, created_at, updated_at) " +
            "VALUES(#{deviceCode}, #{deviceName}, #{deviceType}, #{farmlandId}, #{status}, " +
            "#{installLocation}, #{lastOnlineTime}, #{firmware}, #{remark}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Device device);

    @Update("UPDATE devices SET device_name = #{deviceName}, device_type = #{deviceType}, " +
            "install_location = #{installLocation}, firmware = #{firmware}, remark = #{remark}, " +
            "updated_at = #{updatedAt} WHERE id = #{id}")
    int update(Device device);

    /**
     * 绑定 / 解绑地块。
     * 目标状态与最后在线时间由 Service 计算后传入，避免在 SQL 里对可能为空的参数做 CASE 判断
     * （不同数据库对 `? IS NULL` 的处理存在差异）。
     */
    @Update("UPDATE devices SET farmland_id = #{farmlandId}, status = #{status}, " +
            "last_online_time = #{lastOnlineTime}, updated_at = #{updatedAt} WHERE id = #{id}")
    int bindFarmland(@Param("id") Integer id,
                     @Param("farmlandId") Integer farmlandId,
                     @Param("status") String status,
                     @Param("lastOnlineTime") Date lastOnlineTime,
                     @Param("updatedAt") Date updatedAt);

    @Update("UPDATE devices SET status = #{status}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateStatus(@Param("id") Integer id,
                     @Param("status") String status,
                     @Param("updatedAt") Date updatedAt);

    @Update("UPDATE devices SET last_online_time = #{time}, " +
            "status = CASE WHEN status = 'UNBOUND' THEN status ELSE 'ONLINE' END, " +
            "updated_at = #{time} WHERE id = #{id}")
    int heartbeat(@Param("id") Integer id, @Param("time") Date time);

    /** 离线巡检：把所有心跳超时且非维护状态的设备判为离线 */
    @Update("UPDATE devices SET status = 'OFFLINE', updated_at = #{now} " +
            "WHERE status = 'ONLINE' AND (last_online_time IS NULL OR last_online_time < #{deadline})")
    int markOffline(@Param("deadline") Date deadline, @Param("now") Date now);

    @Delete("DELETE FROM devices WHERE id = #{id}")
    int delete(Integer id);

    /** 管理端看板：按状态统计设备数量（status / cnt） */
    @Select("SELECT status, COUNT(*) AS cnt FROM devices GROUP BY status")
    List<Map<String, Object>> countByStatus();

    @Select("SELECT COUNT(*) FROM devices")
    int countAll();

    /** 某地块是否有指定类型的设备 */
    @Select("SELECT COUNT(*) FROM devices WHERE farmland_id = #{farmlandId} AND device_type = #{deviceType}")
    int countByFarmlandAndType(@Param("farmlandId") Integer farmlandId,
                               @Param("deviceType") String deviceType);
}
