package com.example.smartfarm.service;

import com.example.smartfarm.common.BusinessException;
import com.example.smartfarm.common.Constants;
import com.example.smartfarm.config.BizConfig;
import com.example.smartfarm.entity.Device;
import com.example.smartfarm.entity.SensorData;
import com.example.smartfarm.mapper.DeviceMapper;
import com.example.smartfarm.mapper.SensorDataMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 传感器数据服务：数据上报入库、实时查询、趋势查询与历史清理。
 * 数据上报后会立即交给告警引擎评估，形成"采集 → 监测 → 告警"的闭环。
 */
@Service
public class SensorDataService {

    private static final Logger log = LoggerFactory.getLogger(SensorDataService.class);

    @Autowired
    private SensorDataMapper sensorDataMapper;
    @Autowired
    private DeviceMapper deviceMapper;
    @Autowired
    private AlarmService alarmService;
    @Autowired
    private BizConfig bizConfig;

    /**
     * 数据上报（设备网关入口）。
     * 支持两种定位方式：deviceCode（真实设备）或 farmlandId（模拟/手动录入）。
     */
    public SensorData ingest(SensorData data) {
        if (data == null) {
            throw new BusinessException("上报数据不能为空");
        }

        // 1) 通过设备编号定位设备与地块
        if (data.getDeviceCode() != null && !data.getDeviceCode().isEmpty()) {
            Device device = deviceMapper.findByCode(data.getDeviceCode());
            if (device == null) {
                throw new BusinessException("设备编号不存在：" + data.getDeviceCode());
            }
            data.setDeviceId(device.getId());
            if (data.getFarmlandId() == null) {
                data.setFarmlandId(device.getFarmlandId());
            }
            // 上报即视为心跳
            deviceMapper.heartbeat(device.getId(), new Date());
        }

        if (data.getFarmlandId() == null) {
            throw new BusinessException("无法确定数据归属地块，请提供 deviceCode 或 farmlandId");
        }

        if (data.getCollectTime() == null) {
            data.setCollectTime(new Date());
        }

        sensorDataMapper.insert(data);

        // 2) 立即执行告警评估（P0：监测 → 告警）
        alarmService.evaluateSensorData(data);

        return data;
    }

    /** 某地块最新一条数据 */
    public SensorData getLatest(Integer farmlandId) {
        return sensorDataMapper.getLatestOne(farmlandId);
    }

    /** 实时看板：最新数据 + 数据是否在有效期内 + 附加健康度判断 */
    public Map<String, Object> getRealtime(Integer farmlandId) {
        SensorData latest = sensorDataMapper.getLatestOne(farmlandId);
        Map<String, Object> result = new HashMap<>();
        result.put("latest", latest);
        result.put("hasData", latest != null);

        if (latest == null) {
            result.put("dataValid", false);
            result.put("status", "NO_DATA");
            return result;
        }

        long ageMinutes = (System.currentTimeMillis() - latest.getCollectTime().getTime()) / 60000L;
        result.put("dataAgeMinutes", ageMinutes);
        result.put("dataValid", ageMinutes <= bizConfig.getSensorValidMinutes());
        result.put("status", judgeStatus(latest));
        return result;
    }

    /** 最近 N 条数据 */
    public List<SensorData> getLatestList(Integer farmlandId, Integer limit) {
        return sensorDataMapper.getLatestData(farmlandId, limit == null ? 10 : limit);
    }

    /** 趋势数据（默认最近 24 小时） */
    public List<SensorData> getTrend(Integer farmlandId, Integer hours) {
        int h = hours == null || hours <= 0 ? 24 : hours;
        return sensorDataMapper.getDataSince(farmlandId, hoursAgo(h));
    }

    /** 按天聚合的统计（历史统计页面用） */
    public List<Map<String, Object>> statByDay(Integer farmlandId, Integer days) {
        return sensorDataMapper.statByDay(farmlandId, daysAgo(days == null ? 7 : days));
    }

    /** 时间段汇总指标 */
    public Map<String, Object> summary(Integer farmlandId, Integer days) {
        return sensorDataMapper.summary(farmlandId, daysAgo(days == null ? 7 : days));
    }

    /** 清理指定天数之前的历史数据（管理端"清理历史数据"） */
    public int cleanup(int keepDays) {
        int deleted = sensorDataMapper.deleteBefore(daysAgo(keepDays));
        log.info("清理 {} 天前的历史数据，共删除 {} 条", keepDays, deleted);
        return deleted;
    }

    /** 数据是否在有效期内（自动灌溉决策依赖此判断，避免用陈旧数据浇水） */
    public boolean isFresh(SensorData data) {
        if (data == null || data.getCollectTime() == null) {
            return false;
        }
        long ageMinutes = (System.currentTimeMillis() - data.getCollectTime().getTime()) / 60000L;
        return ageMinutes <= bizConfig.getSensorValidMinutes();
    }

    /** 单条数据的健康状态，供前端徽章展示 */
    public String judgeStatus(SensorData data) {
        if (data == null) {
            return "NO_DATA";
        }
        Double humidity = data.getSoilHumidity();
        Double temperature = data.getTemperature();
        if (humidity == null || temperature == null) {
            return "NO_DATA";
        }
        if (humidity < 30.0 || temperature > 38.0) {
            return "DANGER";
        }
        if (humidity < 40.0 || temperature > 35.0) {
            return "WARNING";
        }
        return "NORMAL";
    }

    private Date hoursAgo(int hours) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR_OF_DAY, -hours);
        return cal.getTime();
    }

    private Date daysAgo(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -days);
        return cal.getTime();
    }

    /** 供其他服务复用 */
    public static boolean isDeviceTypeSensor(String deviceType) {
        return Constants.DEVICE_SOIL_MOISTURE.equals(deviceType)
                || Constants.DEVICE_TEMPERATURE.equals(deviceType)
                || Constants.DEVICE_LIGHT.equals(deviceType)
                || Constants.DEVICE_WEATHER.equals(deviceType);
    }
}
