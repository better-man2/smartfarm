package com.example.smartfarm.service;

import com.example.smartfarm.common.Constants;
import com.example.smartfarm.config.BizConfig;
import com.example.smartfarm.entity.Device;
import com.example.smartfarm.entity.Farmland;
import com.example.smartfarm.entity.IrrigationStrategy;
import com.example.smartfarm.entity.SensorData;
import com.example.smartfarm.mapper.DeviceMapper;
import com.example.smartfarm.mapper.FarmlandMapper;
import com.example.smartfarm.mapper.IrrigationRecordMapper;
import com.example.smartfarm.mapper.SensorDataMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 自动灌溉与设备监控调度器（P0 核心）。
 *
 * 定时任务：
 * 1) 自动灌溉巡检——按策略判断是否满足灌溉条件并自动执行；
 * 2) 设备离线巡检——心跳超时的设备触发高等级告警并推送；
 * 3) 灌溉任务回收——把到时的灌溉任务置为完成并回写灌溉后的湿度；
 * 4) 传感器数据模拟采集——仅开发环境，真实环境由设备上报。
 *
 * 自动灌溉并非"湿度低就浇水"，而是需同时满足以下全部条件：
 *   ① 地块开启了自动模式
 *   ② 最新数据在有效期内（避免用陈旧数据浇水）
 *   ③ 湿度低于策略下限
 *   ④ 当前时间处于策略允许的灌溉时段
 *   ⑤ 温度、光照未超过策略上限
 *   ⑥ 该地块没有正在执行的灌溉任务
 *   ⑦ 距上次灌溉已超过冷却期
 */
@Component
public class AutoIrrigationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoIrrigationScheduler.class);

    @Autowired
    private FarmlandMapper farmlandMapper;
    @Autowired
    private SensorDataMapper sensorDataMapper;
    @Autowired
    private DeviceMapper deviceMapper;
    @Autowired
    private IrrigationRecordMapper recordMapper;
    @Autowired
    private IrrigationService irrigationService;
    @Autowired
    private SensorDataService sensorDataService;
    @Autowired
    private AlarmService alarmService;
    @Autowired
    private BizConfig bizConfig;

    // ==================================================================
    // 1. 自动灌溉巡检
    // ==================================================================

    @Scheduled(fixedDelayString = "${smartfarm.auto-irrigation.scan-interval:60000}", initialDelay = 10000)
    public void scanAutoIrrigation() {
        List<Farmland> farmlands = farmlandMapper.findAutoEnabled();
        for (Farmland farmland : farmlands) {
            try {
                evaluateAndIrrigate(farmland);
            } catch (Exception e) {
                log.error("自动灌溉巡检异常，地块={}", farmland.getName(), e);
            }
        }
    }

    /**
     * 判断单块地是否满足自动灌溉条件，满足则执行。
     *
     * @return 是否触发了灌溉
     */
    private boolean evaluateAndIrrigate(Farmland farmland) {
        // 条件①：自动模式（列表已在 SQL 层过滤，此处二次确认）
        if (farmland.getAutoIrrigation() == null || farmland.getAutoIrrigation() != 1) {
            return false;
        }

        // 条件②：取最新数据并校验有效期，避免用陈旧数据决策
        SensorData latest = sensorDataMapper.getLatestOne(farmland.getId());
        if (latest == null) {
            log.debug("地块「{}」暂无采集数据，跳过自动灌溉", farmland.getName());
            return false;
        }
        if (!sensorDataService.isFresh(latest)) {
            log.debug("地块「{}」最新数据已过期（采集于 {}），跳过自动灌溉",
                    farmland.getName(), latest.getCollectTime());
            return false;
        }

        // 条件⑥：已有进行中的灌溉任务
        if (recordMapper.countRunning(farmland.getId()) > 0) {
            return false;
        }

        IrrigationStrategy strategy = irrigationService.resolveStrategy(farmland);
        if (strategy == null || strategy.getEnabled() == null || strategy.getEnabled() != 1) {
            log.debug("地块「{}」无可用灌溉策略，跳过自动灌溉", farmland.getName());
            return false;
        }

        // 条件④：允许灌溉时段
        if (!inAllowedWindow(strategy)) {
            log.debug("地块「{}」当前不在允许灌溉时段（{}~{}），跳过",
                    farmland.getName(), strategy.getAllowedStartTime(), strategy.getAllowedEndTime());
            return false;
        }

        // 条件③⑤：湿度低于下限且温度/光照未超上限，由决策引擎统一判断
        Map<String, Object> decision = irrigationService.makeDecision(latest, strategy);
        if (!Boolean.TRUE.equals(decision.get("needIrrigation"))) {
            return false;
        }

        // 条件⑦：冷却期
        Date lastTime = recordMapper.findLastIrrigationTime(farmland.getId());
        if (lastTime != null) {
            long minutes = (System.currentTimeMillis() - lastTime.getTime()) / 60000L;
            if (minutes < bizConfig.getAutoIrrigationCooldownMinutes()) {
                log.debug("地块「{}」距上次灌溉仅 {} 分钟，处于冷却期（{} 分钟）",
                        farmland.getName(), minutes, bizConfig.getAutoIrrigationCooldownMinutes());
                return false;
            }
        }

        irrigationService.autoIrrigate(farmland, strategy, latest, decision);
        return true;
    }

    /** 判断当前时间是否落在策略允许的灌溉时段内，支持跨零点时段（如 20:00~06:00） */
    private boolean inAllowedWindow(IrrigationStrategy strategy) {
        String start = strategy.getAllowedStartTime();
        String end = strategy.getAllowedEndTime();
        if (start == null || end == null || start.isEmpty() || end.isEmpty()) {
            return true;
        }
        try {
            int nowMinutes = nowMinutesOfDay();
            int startMinutes = parseMinutes(start);
            int endMinutes = parseMinutes(end);

            if (startMinutes <= endMinutes) {
                return nowMinutes >= startMinutes && nowMinutes <= endMinutes;
            }
            return nowMinutes >= startMinutes || nowMinutes <= endMinutes;
        } catch (Exception e) {
            log.warn("策略「{}」的灌溉时段配置无法解析（{}~{}），本次跳过",
                    strategy.getName(), start, end);
            return false;
        }
    }

    private int nowMinutesOfDay() {
        Calendar cal = Calendar.getInstance();
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }

    private int parseMinutes(String hhmm) {
        String[] parts = hhmm.trim().split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    // ==================================================================
    // 2. 设备离线巡检
    // ==================================================================

    @Scheduled(fixedDelayString = "${smartfarm.device.check-interval:300000}", initialDelay = 20000)
    public void checkDeviceOffline() {
        Date deadline = minutesAgo(bizConfig.getDeviceOfflineMinutes());

        // 先把心跳超时的在线设备标记为离线
        int marked = deviceMapper.markOffline(deadline, new Date());
        if (marked > 0) {
            log.info("设备离线巡检：{} 台设备心跳超时，已标记为离线", marked);
        }

        // 再对离线设备产生高等级告警（未绑定地块的设备不产生业务告警）
        List<Device> devices = deviceMapper.findDevices(null, Constants.DEVICE_OFFLINE, null, null);
        for (Device device : devices) {
            if (device.getFarmlandId() == null) {
                continue;
            }
            long offlineMinutes = device.getLastOnlineTime() == null
                    ? bizConfig.getDeviceOfflineMinutes() * 2L
                    : (System.currentTimeMillis() - device.getLastOnlineTime().getTime()) / 60000L;
            try {
                alarmService.evaluateDeviceOffline(device, offlineMinutes);
            } catch (Exception e) {
                log.error("设备离线告警处理异常，设备={}", device.getDeviceCode(), e);
            }
        }
    }

    // ==================================================================
    // 3. 灌溉任务回收
    // ==================================================================

    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void completeIrrigationTasks() {
        try {
            irrigationService.completeRunningRecords();
        } catch (Exception e) {
            log.error("灌溉任务回收异常", e);
        }
    }

    // ==================================================================
    // 4. 传感器数据模拟采集（仅开发环境）
    // ==================================================================

    @Scheduled(fixedDelayString = "${smartfarm.simulator.interval:60000}", initialDelay = 30000)
    public void simulateCollect() {
        if (!bizConfig.isSimulatorEnabled()) {
            return;
        }

        List<Farmland> farmlands = farmlandMapper.findAllWithStats();
        for (Farmland farmland : farmlands) {
            try {
                Device sensor = firstSensorOf(farmland.getId());
                SensorData reading = irrigationService.simulateReading(farmland,
                        sensor == null ? null : sensor.getId());
                sensorDataService.ingest(reading);
                if (sensor != null) {
                    deviceMapper.heartbeat(sensor.getId(), new Date());
                }
            } catch (Exception e) {
                log.error("模拟采集异常，地块={}", farmland.getName(), e);
            }
        }
    }

    private Device firstSensorOf(Integer farmlandId) {
        for (Device device : deviceMapper.findByFarmlandId(farmlandId)) {
            if (SensorDataService.isDeviceTypeSensor(device.getDeviceType())) {
                return device;
            }
        }
        return null;
    }

    // ==================================================================
    // 5. 管理端手动触发
    // ==================================================================

    /**
     * 立即执行一轮自动灌溉巡检（管理端"立即巡检"按钮）。
     *
     * @return 本轮新触发的灌溉任务数量
     */
    public int runScanNow() {
        int before = recordMapper.countAll();
        for (Farmland farmland : farmlandMapper.findAutoEnabled()) {
            try {
                evaluateAndIrrigate(farmland);
            } catch (Exception e) {
                log.error("手动触发自动灌溉异常，地块={}", farmland.getName(), e);
            }
        }
        return recordMapper.countAll() - before;
    }

    private Date minutesAgo(int minutes) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MINUTE, -minutes);
        return cal.getTime();
    }
}
