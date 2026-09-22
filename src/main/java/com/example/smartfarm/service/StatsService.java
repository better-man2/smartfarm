package com.example.smartfarm.service;

import com.example.smartfarm.common.Constants;
import com.example.smartfarm.common.ValueUtil;
import com.example.smartfarm.entity.Device;
import com.example.smartfarm.entity.Farmland;
import com.example.smartfarm.entity.IrrigationRecord;
import com.example.smartfarm.entity.SensorData;
import com.example.smartfarm.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计与数据看板服务（P1）。
 */
@Service
public class StatsService {

    /**
     * 估算节水率时使用的"传统定时满灌"基准倍数：
     * 传统做法按固定时长灌溉，通常比按需灌溉多出约 50% 用水量。
     * 该系数用于横向对比，前端展示时会显式标注为"估算值"。
     */
    private static final double TRADITIONAL_BASELINE_FACTOR = 1.5;

    @Autowired
    private FarmlandMapper farmlandMapper;
    @Autowired
    private DeviceMapper deviceMapper;
    @Autowired
    private AlarmMapper alarmMapper;
    @Autowired
    private SensorDataMapper sensorDataMapper;
    @Autowired
    private IrrigationRecordMapper recordMapper;
    @Autowired
    private IrrigationDecisionMapper decisionMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private SensorDataService sensorDataService;

    // ==================================================================
    // 农户端数据看板
    // ==================================================================

    /**
     * 农户看板：地块/设备概览 + 环境均值 + 告警概览 + 灌溉概览 + 各地块实时摘要。
     */
    public Map<String, Object> farmerDashboard(Integer userId) {
        List<Farmland> farmlands = farmlandMapper.findByUserId(userId);
        List<Device> devices = deviceMapper.findByUserId(userId);

        Map<String, Object> result = new LinkedHashMap<>();

        // ---- 概览计数 ----
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("farmlandCount", farmlands.size());
        overview.put("deviceCount", devices.size());
        overview.put("onlineDeviceCount", devices.stream()
                .filter(d -> Constants.DEVICE_ONLINE.equals(d.getStatus())).count());
        overview.put("faultDeviceCount", devices.stream()
                .filter(d -> Constants.DEVICE_FAULT.equals(d.getStatus())
                        || Constants.DEVICE_OFFLINE.equals(d.getStatus())).count());
        overview.put("autoIrrigationCount", farmlands.stream()
                .filter(f -> f.getAutoIrrigation() != null && f.getAutoIrrigation() == 1).count());

        // ---- 告警概览：区分"高等级待处理"与"普通记录" ----
        int pendingHigh = 0;
        int pendingNormal = 0;
        List<com.example.smartfarm.entity.Alarm> alarms =
                alarmMapper.findForFarmer(userId, null, null, null, 200);
        for (com.example.smartfarm.entity.Alarm alarm : alarms) {
            if (!Constants.ALARM_PENDING.equals(alarm.getStatus())) {
                continue;
            }
            if (Constants.ALARM_HIGH.equals(alarm.getAlarmLevel())) {
                pendingHigh++;
            } else {
                pendingNormal++;
            }
        }
        overview.put("pendingHighAlarmCount", pendingHigh);
        overview.put("pendingNormalAlarmCount", pendingNormal);
        result.put("overview", overview);

        // ---- 最近 7 天环境均值 ----
        Map<String, Object> sensorSummary = new LinkedHashMap<>();
        double humiditySum = 0;
        double temperatureSum = 0;
        double lightSum = 0;
        int humidityCount = 0;
        int temperatureCount = 0;
        int lightCount = 0;
        for (Farmland farmland : farmlands) {
            SensorData latest = sensorDataMapper.getLatestOne(farmland.getId());
            if (latest == null) {
                continue;
            }
            if (latest.getSoilHumidity() != null) {
                humiditySum += latest.getSoilHumidity();
                humidityCount++;
            }
            if (latest.getTemperature() != null) {
                temperatureSum += latest.getTemperature();
                temperatureCount++;
            }
            if (latest.getLightIntensity() != null) {
                lightSum += latest.getLightIntensity();
                lightCount++;
            }
        }
        sensorSummary.put("avgHumidity", humidityCount == 0 ? null
                : ValueUtil.round1(humiditySum / humidityCount));
        sensorSummary.put("avgTemperature", temperatureCount == 0 ? null
                : ValueUtil.round1(temperatureSum / temperatureCount));
        sensorSummary.put("avgLight", lightCount == 0 ? null
                : ValueUtil.round1(lightSum / lightCount));
        result.put("sensorSummary", sensorSummary);

        // ---- 灌溉概览（近 7 天 / 近 30 天） ----
        result.put("irrigationLast7Days", recordStats(null, userId, 7));
        result.put("irrigationLast30Days", recordStats(null, userId, 30));

        // ---- 各地块实时摘要 ----
        List<Map<String, Object>> farmlandSummaries = new ArrayList<>();
        for (Farmland farmland : farmlands) {
            farmlandSummaries.add(farmlandSummary(farmland));
        }
        result.put("farmlands", farmlandSummaries);

        return result;
    }

    /** 单块地的实时摘要：最新读数 + 设备状态 + 待处理告警 + 是否自动模式 */
    public Map<String, Object> farmlandSummary(Farmland farmland) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", farmland.getId());
        summary.put("name", farmland.getName());
        summary.put("location", farmland.getLocation());
        summary.put("cropType", farmland.getCropType());
        summary.put("area", farmland.getArea());
        summary.put("autoIrrigation", farmland.getAutoIrrigation());
        summary.put("strategyName", farmland.getStrategyName());

        SensorData latest = sensorDataMapper.getLatestOne(farmland.getId());
        if (latest != null) {
            summary.put("soilHumidity", latest.getSoilHumidity());
            summary.put("temperature", latest.getTemperature());
            summary.put("lightIntensity", latest.getLightIntensity());
            summary.put("collectTime", latest.getCollectTime());
            summary.put("dataValid", sensorDataService.isFresh(latest));
            summary.put("status", sensorDataService.judgeStatus(latest));
        } else {
            summary.put("dataValid", false);
            summary.put("status", "NO_DATA");
        }

        List<Device> devices = deviceMapper.findByFarmlandId(farmland.getId());
        summary.put("deviceCount", devices.size());
        summary.put("onlineDeviceCount", devices.stream()
                .filter(d -> Constants.DEVICE_ONLINE.equals(d.getStatus())).count());
        summary.put("pendingAlarmCount", alarmMapper.countPendingByFarmland(farmland.getId()));
        return summary;
    }

    // ==================================================================
    // 历史统计
    // ==================================================================

    /**
     * 历史统计：按天汇总灌溉用水量与环境均值，并给出汇总指标。
     *
     * @param farmlandId 指定地块；为 null 时统计该农户名下全部地块
     */
    public Map<String, Object> historyStats(Integer farmlandId, Integer userId, Integer days) {
        int d = days == null || days <= 0 ? 30 : days;
        Date since = daysAgo(d);

        Map<String, Object> result = new LinkedHashMap<>();

        // 每日灌溉用水量
        List<Map<String, Object>> waterByDay = recordMapper.statWaterByDay(farmlandId, userId, since);
        result.put("waterByDay", normalizeDayStats(waterByDay, d));

        // 每日环境均值（仅单地块时有意义，多地块时按地块逐个统计）
        if (farmlandId != null) {
            result.put("sensorByDay", sensorDataMapper.statByDay(farmlandId, since));
        }

        // 灌溉汇总
        result.put("irrigation", recordStats(farmlandId, userId, d));

        // 环境汇总
        Map<String, Object> sensorSummary = farmlandId == null
                ? sensorDataMapper.summaryMulti(null, since)
                : sensorDataMapper.summary(farmlandId, since);
        Map<String, Object> normalizedSensor = new LinkedHashMap<>();
        normalizedSensor.put("sampleCount", ValueUtil.toInt(sensorSummary.get("sample_count"), 0));
        normalizedSensor.put("avgHumidity", ValueUtil.round1(sensorSummary.get("avg_humidity")));
        normalizedSensor.put("minHumidity", ValueUtil.round1(sensorSummary.get("min_humidity")));
        normalizedSensor.put("maxHumidity", ValueUtil.round1(sensorSummary.get("max_humidity")));
        normalizedSensor.put("avgTemperature", ValueUtil.round1(sensorSummary.get("avg_temperature")));
        normalizedSensor.put("maxTemperature", ValueUtil.round1(sensorSummary.get("max_temperature")));
        normalizedSensor.put("avgLight", ValueUtil.round1(sensorSummary.get("avg_light")));
        result.put("sensor", normalizedSensor);

        // 决策汇总
        Map<String, Object> decisionStats = decisionMapper.stats(farmlandId, userId, since);
        Map<String, Object> normalizedDecision = new LinkedHashMap<>();
        normalizedDecision.put("decisionCount", ValueUtil.toInt(decisionStats.get("decision_count"), 0));
        normalizedDecision.put("irrigationCount", ValueUtil.toInt(decisionStats.get("irrigation_count"), 0));
        normalizedDecision.put("totalRecommendedWater",
                ValueUtil.round1(decisionStats.get("total_recommended_water")));
        normalizedDecision.put("avgConfidence", ValueUtil.round1(decisionStats.get("avg_confidence")));
        result.put("decisions", normalizedDecision);

        result.put("days", d);
        return result;
    }

    /** 灌溉记录统计：次数、用水量、自动/手动占比、估算节水率 */
    public Map<String, Object> recordStats(Integer farmlandId, Integer userId, int days) {
        Map<String, Object> raw = recordMapper.stats(farmlandId, userId, daysAgo(days));

        int successCount = ValueUtil.toInt(raw.get("success_count"), 0);
        double totalWater = ValueUtil.toDouble(raw.get("total_water"), 0.0);

        // 估算节水率：以"传统定时满灌"为基准对比按需灌溉的实际用水量
        double baseline = totalWater * TRADITIONAL_BASELINE_FACTOR;
        Double savingRate = baseline <= 0 ? null
                : ValueUtil.round1((baseline - totalWater) / baseline * 100.0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordCount", ValueUtil.toInt(raw.get("record_count"), 0));
        result.put("successCount", successCount);
        result.put("failedCount", ValueUtil.toInt(raw.get("failed_count"), 0));
        result.put("autoCount", ValueUtil.toInt(raw.get("auto_count"), 0));
        result.put("manualCount", ValueUtil.toInt(raw.get("manual_count"), 0));
        result.put("totalWater", ValueUtil.round1(totalWater));
        result.put("avgWaterPerTime", successCount == 0 ? null
                : ValueUtil.round1(totalWater / successCount));
        result.put("savingRate", savingRate);
        result.put("savingRateNote", "估算值：以传统定时满灌用水量为基准（约 1.5 倍）对比得出");
        result.put("days", days);
        return result;
    }

    // ==================================================================
    // 管理端全局看板
    // ==================================================================

    /**
     * 管理端全局看板：全部地块汇总、设备在线率、告警统计、灌溉与用水总量。
     */
    public Map<String, Object> adminOverview() {
        Map<String, Object> result = new LinkedHashMap<>();

        List<Farmland> farmlands = farmlandMapper.findAllWithStats();
        List<Device> devices = deviceMapper.findDevices(null, null, null, null);
        int deviceCount = devices.size();
        long onlineCount = devices.stream()
                .filter(d -> Constants.DEVICE_ONLINE.equals(d.getStatus())).count();

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("farmerCount", userMapper.countEnabledByRole(Constants.ROLE_FARMER));
        overview.put("farmlandCount", farmlands.size());
        overview.put("deviceCount", deviceCount);
        overview.put("onlineDeviceCount", onlineCount);
        overview.put("deviceOnlineRate", deviceCount == 0 ? 0.0
                : ValueUtil.round1(onlineCount * 100.0 / deviceCount));
        overview.put("totalArea", ValueUtil.round1(
                farmlands.stream().filter(f -> f.getArea() != null)
                        .mapToDouble(Farmland::getArea).sum()));
        result.put("overview", overview);

        // 设备状态分布
        Map<String, Integer> deviceStatus = new LinkedHashMap<>();
        for (Map<String, Object> row : deviceMapper.countByStatus()) {
            deviceStatus.put(String.valueOf(row.get("status")), ValueUtil.toInt(row.get("cnt"), 0));
        }
        result.put("deviceStatus", deviceStatus);

        // 告警统计（近 30 天）
        result.put("alarms", alarmStatsForAdmin(30));

        // 近 30 天灌溉与用水
        result.put("irrigationLast30Days", recordStats(null, null, 30));
        result.put("irrigationLast7Days", recordStats(null, null, 7));

        // 全部地块汇总明细
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Farmland farmland : farmlands) {
            Map<String, Object> item = farmlandSummary(farmland);
            item.put("ownerName", farmland.getUsername());
            summaries.add(item);
        }
        result.put("farmlands", summaries);

        return result;
    }

    /** 管理端告警统计，含按等级与按类型的分布 */
    public Map<String, Object> alarmStatsForAdmin(int days) {
        Date since = daysAgo(days);
        Map<String, Object> alarms = new LinkedHashMap<>();
        alarms.put("total", alarmMapper.countSince(since));
        // 管理端看板统计全平台，userId 传 null
        alarms.put("unresolved", alarmMapper.countUnresolved(null));
        alarms.put("unresolvedHigh", alarmMapper.countUnresolvedHigh(null));

        Map<String, Integer> byLevel = new LinkedHashMap<>();
        byLevel.put(Constants.ALARM_HIGH, 0);
        byLevel.put(Constants.ALARM_MEDIUM, 0);
        byLevel.put(Constants.ALARM_LOW, 0);
        for (Map<String, Object> row : alarmMapper.countByLevelSince(since)) {
            byLevel.put(String.valueOf(row.get("alarm_level")), ValueUtil.toInt(row.get("cnt"), 0));
        }
        alarms.put("byLevel", byLevel);

        Map<String, Integer> byType = new LinkedHashMap<>();
        for (Map<String, Object> row : alarmMapper.countByTypeSince(since)) {
            byType.put(String.valueOf(row.get("alarm_type")), ValueUtil.toInt(row.get("cnt"), 0));
        }
        alarms.put("byType", byType);
        alarms.put("days", days);
        return alarms;
    }

    /** 最近的灌溉记录（看板用） */
    public List<IrrigationRecord> recentRecords(Integer userId, Integer limit) {
        if (userId == null) {
            return recordMapper.findAll(null, null, null, limit == null ? 10 : limit);
        }
        return recordMapper.findByUser(userId, limit == null ? 10 : limit);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 把稀疏的按天统计补齐为连续日期序列，避免图表出现断点。
     */
    private List<Map<String, Object>> normalizeDayStats(List<Map<String, Object>> raw, int days) {
        Map<String, Map<String, Object>> indexed = new HashMap<>();
        for (Map<String, Object> row : raw) {
            Object day = row.get("stat_day");
            if (day != null) {
                indexed.put(day.toString().substring(0, 10), row);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -(days - 1));

        for (int i = 0; i < days; i++) {
            String key = formatDate(cal.getTime());
            Map<String, Object> row = indexed.get(key);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("day", key);
            item.put("irrigationCount", row == null ? 0 : ValueUtil.toInt(row.get("irrigation_count"), 0));
            item.put("totalWater", row == null ? 0.0 : ValueUtil.round1(row.get("total_water")));
            result.add(item);

            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return result;
    }

    private String formatDate(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        return String.format("%04d-%02d-%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private Date daysAgo(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -days);
        return cal.getTime();
    }
}
