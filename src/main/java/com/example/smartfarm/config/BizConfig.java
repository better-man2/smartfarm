package com.example.smartfarm.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 业务参数配置（对应 application.properties 中的 smartfarm.* 项）。
 */
@Getter
@Component
public class BizConfig {

    /** 传感器数据有效期(分钟)，超过视为数据陈旧，自动灌溉不据此执行 */
    @Value("${smartfarm.sensor.valid-minutes:30}")
    private int sensorValidMinutes;

    /** 自动灌溉冷却期(分钟)，同一地块两次自动灌溉的最小间隔 */
    @Value("${smartfarm.auto-irrigation.cooldown-minutes:20}")
    private int autoIrrigationCooldownMinutes;

    /** 设备离线判定(分钟) */
    @Value("${smartfarm.device.offline-minutes:30}")
    private int deviceOfflineMinutes;

    /** 告警去重窗口(分钟) */
    @Value("${smartfarm.alarm.dedup-minutes:30}")
    private int alarmDedupMinutes;

    /** 是否开启传感器数据模拟采集 */
    @Value("${smartfarm.simulator.enabled:false}")
    private boolean simulatorEnabled;

    /** 模拟采集间隔(毫秒) */
    @Value("${smartfarm.simulator.interval:60000}")
    private long simulatorInterval;
}
