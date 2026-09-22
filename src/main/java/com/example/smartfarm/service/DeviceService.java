package com.example.smartfarm.service;

import com.example.smartfarm.common.BusinessException;
import com.example.smartfarm.common.Constants;
import com.example.smartfarm.config.BizConfig;
import com.example.smartfarm.entity.Device;
import com.example.smartfarm.entity.Farmland;
import com.example.smartfarm.mapper.DeviceMapper;
import com.example.smartfarm.mapper.FarmlandMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设备管理服务（P2 多设备批量管理）。
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    /** 合法的设备状态 */
    private static final Set<String> VALID_STATUS = new LinkedHashSet<>(Arrays.asList(
            Constants.DEVICE_ONLINE, Constants.DEVICE_OFFLINE, Constants.DEVICE_FAULT,
            Constants.DEVICE_MAINTENANCE, Constants.DEVICE_UNBOUND));

    /** 合法的设备类型 */
    private static final Set<String> VALID_TYPES = new LinkedHashSet<>(Arrays.asList(
            Constants.DEVICE_SOIL_MOISTURE, Constants.DEVICE_TEMPERATURE, Constants.DEVICE_LIGHT,
            Constants.DEVICE_WEATHER, Constants.DEVICE_IRRIGATION_VALVE, Constants.DEVICE_FERTILIZER_MIXER));

    @Autowired
    private DeviceMapper deviceMapper;
    @Autowired
    private FarmlandMapper farmlandMapper;
    @Autowired
    private BizConfig bizConfig;

    // ==================================================================
    // 查询
    // ==================================================================

    /** 管理端设备列表（支持类型/状态/地块/关键字过滤），并标注心跳超时情况 */
    public List<Device> listDevices(String deviceType, String status, Integer farmlandId, String keyword) {
        List<Device> devices = deviceMapper.findDevices(deviceType, status, farmlandId, keyword);
        devices.forEach(this::fillOfflineMinutes);
        return devices;
    }

    /** 农户端：自己名下地块的设备 */
    public List<Device> listByUser(Integer userId) {
        List<Device> devices = deviceMapper.findByUserId(userId);
        devices.forEach(this::fillOfflineMinutes);
        return devices;
    }

    public List<Device> listByFarmland(Integer farmlandId) {
        List<Device> devices = deviceMapper.findByFarmlandId(farmlandId);
        devices.forEach(this::fillOfflineMinutes);
        return devices;
    }

    public Device getById(Integer id) {
        Device device = deviceMapper.findById(id);
        if (device == null) {
            throw new BusinessException("设备不存在");
        }
        fillOfflineMinutes(device);
        return device;
    }

    // ==================================================================
    // 新增 / 修改 / 删除
    // ==================================================================

    public Device addDevice(Device device) {
        validate(device, true);

        if (deviceMapper.findByCode(device.getDeviceCode().trim()) != null) {
            throw new BusinessException("设备编号已存在：" + device.getDeviceCode());
        }
        device.setDeviceCode(device.getDeviceCode().trim());

        // 绑定地块时校验地块存在
        if (device.getFarmlandId() != null) {
            if (farmlandMapper.findById(device.getFarmlandId()) == null) {
                throw new BusinessException("绑定的地块不存在");
            }
            device.setStatus(Constants.DEVICE_ONLINE);
            device.setLastOnlineTime(new Date());
        } else {
            device.setStatus(Constants.DEVICE_UNBOUND);
        }

        device.setCreatedAt(new Date());
        device.setUpdatedAt(new Date());
        deviceMapper.insert(device);
        log.info("新增设备：{}（{}）", device.getDeviceCode(), device.getDeviceName());
        return device;
    }

    public Device updateDevice(Device device) {
        if (device.getId() == null) {
            throw new BusinessException("缺少设备 ID");
        }
        if (deviceMapper.findById(device.getId()) == null) {
            throw new BusinessException("设备不存在");
        }
        validate(device, false);
        device.setUpdatedAt(new Date());
        deviceMapper.update(device);
        return deviceMapper.findById(device.getId());
    }

    public void deleteDevice(Integer id) {
        if (deviceMapper.findById(id) == null) {
            throw new BusinessException("设备不存在");
        }
        deviceMapper.delete(id);
        log.info("删除设备：ID={}", id);
    }

    // ==================================================================
    // 绑定与批量操作（P2）
    // ==================================================================

    /**
     * 绑定 / 解绑设备到地块。
     *
     * @param farmlandId 传 null 表示解绑
     */
    public void bind(Integer deviceId, Integer farmlandId, Integer operatorId) {
        Device device = deviceMapper.findById(deviceId);
        if (device == null) {
            throw new BusinessException("设备不存在");
        }
        if (farmlandId != null && farmlandMapper.findById(farmlandId) == null) {
            throw new BusinessException("地块不存在");
        }
        applyBinding(deviceId, farmlandId);

        if (farmlandId == null) {
            log.info("设备 {} 已解绑", device.getDeviceCode());
        } else {
            log.info("设备 {} 已绑定到地块 {}", device.getDeviceCode(), farmlandId);
        }
    }

    /**
     * 执行绑定写入。
     * 绑定即视为设备上线并刷新心跳，避免刚绑定就被离线巡检判为离线；
     * 解绑则回到"未绑定"状态，并清空心跳时间。
     */
    private void applyBinding(Integer deviceId, Integer farmlandId) {
        Date now = new Date();
        boolean binding = farmlandId != null;
        deviceMapper.bindFarmland(deviceId,
                farmlandId,
                binding ? Constants.DEVICE_ONLINE : Constants.DEVICE_UNBOUND,
                binding ? now : null,
                now);
    }

    /** 批量绑定：一次把多台设备绑定到同一地块（解绑传 farmlandId=null） */
    public int batchBind(List<Integer> deviceIds, Integer farmlandId) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            throw new BusinessException("请先选择要操作的设备");
        }
        if (farmlandId != null && farmlandMapper.findById(farmlandId) == null) {
            throw new BusinessException("地块不存在");
        }

        int count = 0;
        for (Integer id : deviceIds) {
            if (deviceMapper.findById(id) == null) {
                continue;
            }
            applyBinding(id, farmlandId);
            count++;
        }
        log.info("批量绑定完成：{} 台设备 -> 地块 {}", count, farmlandId);
        return count;
    }

    /** 批量修改设备状态，例如批量转入维护、批量标记故障 */
    public int batchUpdateStatus(List<Integer> deviceIds, String status) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            throw new BusinessException("请先选择要操作的设备");
        }
        if (status == null || !VALID_STATUS.contains(status)) {
            throw new BusinessException("非法的设备状态：" + status);
        }
        int count = 0;
        Date now = new Date();
        for (Integer id : deviceIds) {
            if (deviceMapper.findById(id) == null) {
                continue;
            }
            deviceMapper.updateStatus(id, status, now);
            count++;
        }
        log.info("批量更新设备状态完成：{} 台设备 -> {}", count, status);
        return count;
    }

    /** 批量解绑 */
    public int batchUnbind(List<Integer> deviceIds) {
        return batchBind(deviceIds, null);
    }

    /** 设备心跳上报 */
    public void heartbeat(Integer deviceId) {
        if (deviceMapper.findById(deviceId) == null) {
            throw new BusinessException("设备不存在");
        }
        deviceMapper.heartbeat(deviceId, new Date());
    }

    // ==================================================================
    // 统计
    // ==================================================================

    /** 设备状态分布统计 */
    public Map<String, Object> statusStatistics() {
        Map<String, Integer> statusMap = new LinkedHashMap<>();
        for (String status : VALID_STATUS) {
            statusMap.put(status, 0);
        }
        int total = 0;
        for (Map<String, Object> row : deviceMapper.countByStatus()) {
            int count = row.get("cnt") == null ? 0 : Integer.parseInt(row.get("cnt").toString());
            statusMap.put(String.valueOf(row.get("status")), count);
            total += count;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("byStatus", statusMap);

        int online = statusMap.getOrDefault(Constants.DEVICE_ONLINE, 0);
        result.put("onlineRate", total == 0 ? 0.0 : Math.round(online * 1000.0 / total) / 10.0);
        result.put("abnormalCount", statusMap.getOrDefault(Constants.DEVICE_OFFLINE, 0)
                + statusMap.getOrDefault(Constants.DEVICE_FAULT, 0));
        return result;
    }

    /** 设备类型的中文名称，供前端展示 */
    public static String typeText(String deviceType) {
        if (deviceType == null) {
            return "-";
        }
        switch (deviceType) {
            case Constants.DEVICE_SOIL_MOISTURE:
                return "土壤湿度传感器";
            case Constants.DEVICE_TEMPERATURE:
                return "温度传感器";
            case Constants.DEVICE_LIGHT:
                return "光照传感器";
            case Constants.DEVICE_WEATHER:
                return "农业气象站";
            case Constants.DEVICE_IRRIGATION_VALVE:
                return "灌溉电磁阀";
            case Constants.DEVICE_FERTILIZER_MIXER:
                return "水肥一体机";
            default:
                return deviceType;
        }
    }

    /** 全部可选的设备类型（前端下拉框） */
    public List<Map<String, String>> typeOptions() {
        List<Map<String, String>> options = new ArrayList<>();
        for (String type : VALID_TYPES) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("value", type);
            item.put("label", typeText(type));
            options.add(item);
        }
        return options;
    }

    /** 全部可选的设备状态（前端下拉框） */
    public List<Map<String, String>> statusOptions() {
        List<Map<String, String>> options = new ArrayList<>();
        for (String status : VALID_STATUS) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("value", status);
            item.put("label", statusText(status));
            options.add(item);
        }
        return options;
    }

    public static String statusText(String status) {
        if (status == null) {
            return "-";
        }
        switch (status) {
            case Constants.DEVICE_ONLINE:
                return "在线";
            case Constants.DEVICE_OFFLINE:
                return "离线";
            case Constants.DEVICE_FAULT:
                return "故障";
            case Constants.DEVICE_MAINTENANCE:
                return "维护中";
            case Constants.DEVICE_UNBOUND:
                return "未绑定";
            default:
                return status;
        }
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private void validate(Device device, boolean isCreate) {
        if (isCreate && (device.getDeviceCode() == null || device.getDeviceCode().trim().isEmpty())) {
            throw new BusinessException("请填写设备编号");
        }
        if (device.getDeviceName() == null || device.getDeviceName().trim().isEmpty()) {
            throw new BusinessException("请填写设备名称");
        }
        if (device.getDeviceType() == null || !VALID_TYPES.contains(device.getDeviceType())) {
            throw new BusinessException("请选择合法的设备类型");
        }
    }

    private void fillOfflineMinutes(Device device) {
        if (device.getLastOnlineTime() == null) {
            device.setOfflineMinutes(null);
            return;
        }
        device.setOfflineMinutes((System.currentTimeMillis() - device.getLastOnlineTime().getTime()) / 60000L);
    }

    /** 心跳是否已超时（供前端提示与调度器判定复用） */
    public boolean isHeartbeatTimeout(Device device) {
        if (device.getLastOnlineTime() == null) {
            return true;
        }
        long minutes = (System.currentTimeMillis() - device.getLastOnlineTime().getTime()) / 60000L;
        return minutes > bizConfig.getDeviceOfflineMinutes();
    }

    /** 某地块是否已安装灌溉阀门 */
    public boolean hasIrrigationValve(Integer farmlandId) {
        return deviceMapper.countByFarmlandAndType(farmlandId, Constants.DEVICE_IRRIGATION_VALVE) > 0;
    }

    /** 未绑定设备列表（供绑定操作选择） */
    public List<Device> listUnbound() {
        return deviceMapper.findDevices(null, Constants.DEVICE_UNBOUND, null, null);
    }

    /** 全部地块（供绑定下拉框），返回 id 与名称 */
    public List<Map<String, Object>> farmlandOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        for (Farmland farmland : farmlandMapper.findAllWithStats()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", farmland.getId());
            item.put("name", farmland.getName());
            item.put("ownerName", farmland.getUsername());
            options.add(item);
        }
        return options;
    }
}
