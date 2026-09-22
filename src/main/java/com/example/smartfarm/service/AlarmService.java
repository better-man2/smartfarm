package com.example.smartfarm.service;

import com.example.smartfarm.common.BusinessException;
import com.example.smartfarm.common.Constants;
import com.example.smartfarm.config.BizConfig;
import com.example.smartfarm.entity.Alarm;
import com.example.smartfarm.entity.AlarmRule;
import com.example.smartfarm.entity.Device;
import com.example.smartfarm.entity.Farmland;
import com.example.smartfarm.entity.SensorData;
import com.example.smartfarm.mapper.AlarmMapper;
import com.example.smartfarm.mapper.AlarmRuleMapper;
import com.example.smartfarm.mapper.FarmlandMapper;
import com.example.smartfarm.mapper.SensorDataMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 告警服务（P0 核心）。
 *
 * 职责：
 * 1) 告警引擎——按管理员配置的 alarm_rules 逐条比对传感器数据与设备心跳，产生分级告警；
 * 2) 分级策略——只有 HIGH 等级且规则开启推送的告警才进入推送队列，
 *    MEDIUM/LOW 一律仅落库、在页面告警列表留痕（pushStatus 恒为 0）；
 * 3) 去重——同地块、同指标、同等级在配置的去重窗口内只产生一条告警，避免刷屏；
 * 4) 处理流转——待处理 / 处理中 / 已解决，并记录处理人与处理备注。
 */
@Service
public class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);

    /** 传感器物理量程：超出范围说明采集设备异常，属于设备故障类告警 */    private static final double HUMIDITY_MIN = 0.0;
    private static final double HUMIDITY_MAX = 100.0;
    private static final double TEMPERATURE_MIN = -40.0;
    private static final double TEMPERATURE_MAX = 80.0;
    private static final double LIGHT_MIN = 0.0;
    private static final double LIGHT_MAX = 150000.0;

    @Autowired
    private AlarmMapper alarmMapper;
    @Autowired
    private AlarmRuleMapper alarmRuleMapper;
    @Autowired
    private FarmlandMapper farmlandMapper;
    @Autowired
    private SensorDataMapper sensorDataMapper;
    @Autowired
    private BizConfig bizConfig;

    // ==================================================================
    // 一、告警引擎
    // ==================================================================

    /**
     * 对一条新的采集数据执行告警评估。由数据上报接口与数据模拟器调用。
     */
    public void evaluateSensorData(SensorData data) {
        if (data == null || data.getFarmlandId() == null) {
            return;
        }

        // 1) 量程校验：读数越界属于设备/传感器故障，直接按 HIGH 级处理
        String rangeError = checkRange(data);
        if (rangeError != null) {
            raiseAlarm(data.getFarmlandId(), data.getDeviceId(),
                    Constants.ALARM_TYPE_SENSOR_ABNORMAL, Constants.ALARM_HIGH,
                    "传感器采集数据异常", rangeError, "SENSOR_RANGE", null, true);
            return;
        }

        // 2) 按适用于该地块的启用规则逐条比对
        List<AlarmRule> rules = alarmRuleMapper.findEffectiveRules(data.getFarmlandId());
        for (AlarmRule rule : rules) {
            Double value = metricValue(data, rule.getMetric());
            if (value == null || rule.getThreshold() == null) {
                continue;
            }
            if (!violated(rule.getCompareOp(), value, rule.getThreshold())) {
                continue;
            }
            raiseAlarm(data.getFarmlandId(), data.getDeviceId(),
                    alarmTypeOf(rule.getMetric()), rule.getAlarmLevel(),
                    rule.getRuleName(), buildContent(rule, value),
                    rule.getMetric(), value, isPushEnabled(rule.getPushEnabled()));
        }
    }

    /**
     * 启动时或管理员手动触发：对全部地块的最新数据重新评估一次。
     */
    public int evaluateAllFarmlands() {
        List<Farmland> farmlands = farmlandMapper.findAllWithStats();
        int evaluated = 0;
        for (Farmland farmland : farmlands) {
            SensorData latest = sensorDataMapper.getLatestOne(farmland.getId());
            if (latest != null) {
                evaluateSensorData(latest);
                evaluated++;
            }
        }
        return evaluated;
    }

    /**
     * 设备离线告警，由设备离线巡检任务调用。
     * 同样走阈值规则，管理员可在后台调整离线判定时长与告警等级。
     *
     * @return 是否产生了新的告警
     */
    public boolean evaluateDeviceOffline(Device device, long offlineMinutes) {
        if (device == null) {
            return false;
        }

        List<AlarmRule> rules = alarmRuleMapper.findEffectiveRules(device.getFarmlandId());
        boolean raised = false;

        for (AlarmRule rule : rules) {
            if (!Constants.METRIC_DEVICE_OFFLINE_MINUTES.equals(rule.getMetric()) || rule.getThreshold() == null) {
                continue;
            }
            if (!violated(rule.getCompareOp(), (double) offlineMinutes, rule.getThreshold())) {
                continue;
            }
            boolean created = raiseAlarm(device.getFarmlandId(), device.getId(),
                    Constants.ALARM_TYPE_DEVICE_OFFLINE, rule.getAlarmLevel(),
                    "设备离线：" + device.getDeviceName(),
                    "设备 " + device.getDeviceCode() + "（" + device.getDeviceName() + "）已连续 "
                            + offlineMinutes + " 分钟无数据上报，判定为离线，请检查供电与网络连接。",
                    Constants.METRIC_DEVICE_OFFLINE_MINUTES, (double) offlineMinutes,
                    isPushEnabled(rule.getPushEnabled()));
            raised = raised || created;
        }

        // 未配置离线规则时，按默认 HIGH 级兜底，保证设备故障不会被漏报
        if (rules.stream().noneMatch(r -> Constants.METRIC_DEVICE_OFFLINE_MINUTES.equals(r.getMetric()))) {
            raised = raiseAlarm(device.getFarmlandId(), device.getId(),
                    Constants.ALARM_TYPE_DEVICE_OFFLINE, Constants.ALARM_HIGH,
                    "设备离线：" + device.getDeviceName(),
                    "设备 " + device.getDeviceCode() + " 已连续 " + offlineMinutes + " 分钟无数据上报。",
                    Constants.METRIC_DEVICE_OFFLINE_MINUTES, (double) offlineMinutes, true);
        }

        return raised;
    }

    /** 灌溉执行失败告警（设备动作类异常，属于高等级故障） */
    public void raiseIrrigationFailure(Farmland farmland, Integer deviceId, String reason) {
        raiseAlarm(farmland == null ? null : farmland.getId(), deviceId,
                Constants.ALARM_TYPE_IRRIGATION_FAIL, Constants.ALARM_HIGH,
                "灌溉执行失败",
                "地块「" + (farmland == null ? "-" : farmland.getName()) + "」灌溉未能正常执行：" + reason,
                "IRRIGATION", null, true);
    }

    /**
     * 产生一条告警（含去重与推送判定）。
     *
     * @param pushRequested 规则是否要求推送；最终是否推送还会强制校验等级必须为 HIGH
     * @return 是否真正落库（被去重则返回 false）
     */
    public boolean raiseAlarm(Integer farmlandId, Integer deviceId, String alarmType, String level,
                              String title, String content, String metric, Double metricValue,
                              boolean pushRequested) {

        // 去重：同地块 + 同指标 + 同等级，在窗口期内只保留一条
        Date since = minutesAgo(bizConfig.getAlarmDedupMinutes());
        if (alarmMapper.countRecent(farmlandId, metric, level, since) > 0) {
            return false;
        }

        Alarm alarm = new Alarm();
        alarm.setFarmlandId(farmlandId);
        alarm.setDeviceId(deviceId);
        alarm.setAlarmType(alarmType);
        alarm.setAlarmLevel(level);
        alarm.setTitle(title);
        alarm.setContent(content);
        alarm.setMetric(metric);
        alarm.setMetricValue(metricValue);
        alarm.setStatus(Constants.ALARM_PENDING);
        // 需求约束：仅高等级故障/异常推送通知，普通数据异常只在页面记录。
        // 因此这里强制要求 level 为 HIGH 且规则开启了推送，两个条件缺一不可。
        boolean shouldPush = pushRequested && Constants.ALARM_HIGH.equals(level);
        alarm.setPushStatus(shouldPush ? 1 : 0);
        alarm.setPushTime(null);
        alarm.setCreatedAt(new Date());
        alarmMapper.insert(alarm);

        if (alarm.getPushStatus() == 1) {
            log.warn("[高等级告警待推送] {} | 地块{} | {}", title, farmlandId, content);
        } else {
            log.info("[告警记录-仅留痕] {} | 地块{} | {}", title, farmlandId, content);
        }
        return true;
    }

    // ==================================================================
    // 二、查询与处理
    // ==================================================================

    public List<Alarm> listForFarmer(Integer userId, String level, String status, Integer farmlandId, Integer limit) {
        return alarmMapper.findForFarmer(userId, level, status, farmlandId, limit == null ? 100 : limit);
    }

    public List<Alarm> listAll(String level, String status, Integer farmlandId, String alarmType, Integer limit) {
        return alarmMapper.findAll(level, status, farmlandId, alarmType, limit == null ? 200 : limit);
    }

    /** 待推送告警（仅 HIGH 等级、尚未弹窗确认）。userId 为 null 表示推送全部地块。 */
    public List<Alarm> findPendingPush(Integer userId) {
        return alarmMapper.findPendingPush(userId);
    }

    /** 前端弹窗后确认，避免同一告警重复推送 */
    public int ackPushed(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        // ids 已由 Integer 反序列化保证为数字，此处仅做拼接
        String joined = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return alarmMapper.ackPushed(joined, new Date());
    }

    /** 农户排查/管理员处理告警 */
    public void handle(Integer alarmId, String status, String remark, Integer handledBy) {
        if (alarmMapper.findById(alarmId) == null) {
            throw new BusinessException("告警记录不存在");
        }
        if (status == null || status.isEmpty()) {
            status = Constants.ALARM_RESOLVED;
        }
        alarmMapper.handle(alarmId, status, handledBy, new Date(), remark);
    }

    /** 批量处理（管理端） */
    public int batchHandle(List<Integer> alarmIds, String status, String remark, Integer handledBy) {
        if (alarmIds == null || alarmIds.isEmpty()) {
            throw new BusinessException("请先选择要处理的告警");
        }
        int count = 0;
        for (Integer id : alarmIds) {
            if (alarmMapper.findById(id) != null) {
                alarmMapper.handle(id, status, handledBy, new Date(), remark);
                count++;
            }
        }
        return count;
    }

    /**
     * 未处理告警统计。
     *
     * @param userId 传入农户 ID 时只统计其名下地块；传 null 表示统计全平台（管理端）
     */
    public int countUnread(Integer userId) {
        return alarmMapper.countUnread(userId);
    }

    public int countUnreadHigh(Integer userId) {
        return alarmMapper.countUnreadHigh(userId);
    }

    public int countUnresolved(Integer userId) {
        return alarmMapper.countUnresolved(userId);
    }

    public int countUnresolvedHigh(Integer userId) {
        return alarmMapper.countUnresolvedHigh(userId);
    }

    /** 全平台统计（启动日志与管理端使用） */
    public int countUnread() {
        return alarmMapper.countUnread(null);
    }

    public int countUnreadHigh() {
        return alarmMapper.countUnreadHigh(null);
    }

    public List<Alarm> allPendingPush() {
        return alarmMapper.findPendingPush(null);
    }

    // ==================================================================
    // 三、内部工具
    // ==================================================================

    /** 取指标对应的实时值 */
    private Double metricValue(SensorData data, String metric) {
        switch (metric) {
            case Constants.METRIC_SOIL_HUMIDITY:
                return data.getSoilHumidity();
            case Constants.METRIC_TEMPERATURE:
                return data.getTemperature();
            case Constants.METRIC_LIGHT_INTENSITY:
                return data.getLightIntensity();
            default:
                return null;
        }
    }

    private boolean violated(String compareOp, double value, double threshold) {
        return Constants.OP_LT.equals(compareOp) ? value < threshold : value > threshold;
    }

    /** 指标 → 告警类型 */
    private String alarmTypeOf(String metric) {
        switch (metric) {
            case Constants.METRIC_SOIL_HUMIDITY:
                return Constants.ALARM_TYPE_SOIL_DRY;
            case Constants.METRIC_TEMPERATURE:
                return Constants.ALARM_TYPE_TEMP_HIGH;
            case Constants.METRIC_LIGHT_INTENSITY:
                return Constants.ALARM_TYPE_LIGHT_ABNORMAL;
            case Constants.METRIC_DEVICE_OFFLINE_MINUTES:
                return Constants.ALARM_TYPE_DEVICE_OFFLINE;
            default:
                return Constants.ALARM_TYPE_SENSOR_ABNORMAL;
        }
    }

    private String buildContent(AlarmRule rule, double value) {
        String unit = unitOf(rule.getMetric());
        String direction = Constants.OP_LT.equals(rule.getCompareOp()) ? "低于" : "高于";
        return "当前" + measureName(rule.getMetric()) + " " + format(value) + unit
                + "，" + direction + "阈值 " + format(rule.getThreshold()) + unit
                + (rule.getDescription() == null ? "" : "（" + rule.getDescription() + "）");
    }

    private String measureName(String metric) {
        switch (metric) {
            case Constants.METRIC_SOIL_HUMIDITY:
                return "土壤湿度";
            case Constants.METRIC_TEMPERATURE:
                return "温度";
            case Constants.METRIC_LIGHT_INTENSITY:
                return "光照强度";
            case Constants.METRIC_DEVICE_OFFLINE_MINUTES:
                return "设备离线时长";
            default:
                return metric;
        }
    }

    private String unitOf(String metric) {
        switch (metric) {
            case Constants.METRIC_SOIL_HUMIDITY:
                return "%";
            case Constants.METRIC_TEMPERATURE:
                return "℃";
            case Constants.METRIC_LIGHT_INTENSITY:
                return "lux";
            case Constants.METRIC_DEVICE_OFFLINE_MINUTES:
                return "分钟";
            default:
                return "";
        }
    }

    /** 校验读数是否落在传感器物理量程内，越界返回错误描述 */
    private String checkRange(SensorData data) {
        List<String> errors = new ArrayList<>();
        if (data.getSoilHumidity() != null
                && (data.getSoilHumidity() < HUMIDITY_MIN || data.getSoilHumidity() > HUMIDITY_MAX)) {
            errors.add("土壤湿度 " + format(data.getSoilHumidity()) + "% 超出 0~100% 量程");
        }
        if (data.getTemperature() != null
                && (data.getTemperature() < TEMPERATURE_MIN || data.getTemperature() > TEMPERATURE_MAX)) {
            errors.add("温度 " + format(data.getTemperature()) + "℃ 超出 -40~80℃ 量程");
        }
        if (data.getLightIntensity() != null
                && (data.getLightIntensity() < LIGHT_MIN || data.getLightIntensity() > LIGHT_MAX)) {
            errors.add("光照强度 " + format(data.getLightIntensity()) + "lux 超出量程");
        }
        return errors.isEmpty() ? null : String.join("；", errors) + "，请检查传感器接线与校准状态";
    }

    private boolean isPushEnabled(Integer flag) {
        return flag != null && flag == 1;
    }

    private String format(Double value) {
        if (value == null) {
            return "-";
        }
        if (value == Math.floor(value)) {
            return String.valueOf(value.intValue());
        }
        return String.valueOf(Math.round(value * 10.0) / 10.0);
    }

    private Date minutesAgo(int minutes) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, -minutes);
        return cal.getTime();
    }
}
