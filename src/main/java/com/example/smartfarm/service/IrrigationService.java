package com.example.smartfarm.service;

import com.example.smartfarm.common.BusinessException;
import com.example.smartfarm.common.Constants;
import com.example.smartfarm.common.ValueUtil;
import com.example.smartfarm.entity.*;
import com.example.smartfarm.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 灌溉服务：决策算法、手动下发指令、自动策略执行与执行结果回收。
 *
 * 决策引擎基于"土壤湿度为主、温度与光照为辅"的多规则模型：
 *   规则1 湿度低于策略下限           → 需要灌溉，水量随缺水程度放大
 *   规则2 高温且湿度未达目标         → 建议灌溉（蒸发量大）
 *   规则3 强光照且湿度偏低           → 建议灌溉（水分蒸腾加快）
 *   规则4 湿度达到目标               → 无需灌溉
 * 置信度由"距阈值的裕度 + 数据新鲜度 + 环境是否越界"共同决定，而非固定值。
 */
@Service
public class IrrigationService {

    private static final Logger log = LoggerFactory.getLogger(IrrigationService.class);

    /** 默认策略参数，用于地块未绑定策略且系统无全局策略时的兜底 */
    private static final double DEFAULT_HUMIDITY_MIN = 40.0;
    private static final double DEFAULT_TARGET_HUMIDITY = 65.0;
    private static final double DEFAULT_TEMPERATURE_MAX = 38.0;
    private static final double DEFAULT_LIGHT_MAX = 60000.0;
    private static final double DEFAULT_WATER = 15.0;
    private static final int DEFAULT_DURATION = 10;

    private static final Random RANDOM = new Random();

    @Autowired
    private SensorDataMapper sensorDataMapper;
    @Autowired
    private IrrigationDecisionMapper decisionMapper;
    @Autowired
    private IrrigationRecordMapper recordMapper;
    @Autowired
    private IrrigationStrategyMapper strategyMapper;
    @Autowired
    private DeviceMapper deviceMapper;
    @Autowired
    private FarmlandMapper farmlandMapper;
    @Autowired
    private AlarmService alarmService;
    @Autowired
    private SensorDataService sensorDataService;

    // ==================================================================
    // 一、决策算法
    // ==================================================================

    /**
     * 生成灌溉决策（对外入口，供"生成灌溉决策"按钮与自动巡检调用）。
     */
    public Map<String, Object> makeDecision(SensorData data, IrrigationStrategy strategy) {
        Map<String, Object> result = new HashMap<>();

        if (data == null) {
            result.put("needIrrigation", false);
            result.put("recommendedWater", 0.0);
            result.put("reason", "暂无传感器数据，无法决策");
            result.put("confidence", 0.0);
            result.put("decisionResult", "数据不足");
            return result;
        }

        double humidityMin = strategy != null && strategy.getSoilHumidityMin() != null
                ? strategy.getSoilHumidityMin() : DEFAULT_HUMIDITY_MIN;
        double target = strategy != null && strategy.getTargetHumidity() != null
                ? strategy.getTargetHumidity() : DEFAULT_TARGET_HUMIDITY;
        double tempMax = strategy != null && strategy.getTemperatureMax() != null
                ? strategy.getTemperatureMax() : DEFAULT_TEMPERATURE_MAX;
        double lightMax = strategy != null && strategy.getLightIntensityMax() != null
                ? strategy.getLightIntensityMax() : DEFAULT_LIGHT_MAX;
        double baseWater = strategy != null && strategy.getIrrigationAmount() != null
                ? strategy.getIrrigationAmount() : DEFAULT_WATER;

        Double humidityObj = data.getSoilHumidity();
        Double tempObj = data.getTemperature();
        Double lightObj = data.getLightIntensity();
        if (humidityObj == null) {
            result.put("needIrrigation", false);
            result.put("recommendedWater", 0.0);
            result.put("reason", "缺少土壤湿度数据，无法决策");
            result.put("confidence", 0.0);
            result.put("decisionResult", "数据不足");
            return result;
        }

        double humidity = humidityObj;
        double temperature = tempObj == null ? 25.0 : tempObj;
        double light = lightObj == null ? 0.0 : lightObj;

        boolean needIrrigation;
        double recommendedWater;
        String reason;

        if (humidity < humidityMin) {
            // 规则1：低于下限，必须灌溉，缺水越多补水量越大
            needIrrigation = true;
            recommendedWater = calcWater(baseWater, humidity, target);
            reason = String.format("土壤湿度 %.1f%% 低于策略下限 %.1f%%，作物存在缺水风险，建议灌溉 %.1f m³",
                    humidity, humidityMin, recommendedWater);
        } else if (temperature > 30.0 && humidity < target) {
            // 规则2：高温加速蒸发
            needIrrigation = true;
            recommendedWater = calcWater(baseWater, humidity, target) * 0.8;
            reason = String.format("气温 %.1f℃ 偏高且土壤湿度 %.1f%% 未达目标 %.1f%%，蒸发量大，建议少量补灌",
                    temperature, humidity, target);
        } else if (light > 40000.0 && humidity < humidityMin + 10.0) {
            // 规则3：强光照加剧蒸腾
            needIrrigation = true;
            recommendedWater = calcWater(baseWater, humidity, target) * 0.7;
            reason = String.format("光照强度 %.0f lux 较强且土壤湿度 %.1f%% 偏低，建议适量补灌",
                    light, humidity);
        } else {
            // 规则4：湿度适宜
            needIrrigation = false;
            recommendedWater = 0.0;
            reason = String.format("土壤湿度 %.1f%% 处于适宜区间（下限 %.1f%%），环境条件良好，无需灌溉",
                    humidity, humidityMin);
        }

        double confidence = calcConfidence(humidity, humidityMin, temperature, tempMax, light, lightMax,
                data.getCollectTime());

        result.put("needIrrigation", needIrrigation);
        result.put("recommendedWater", ValueUtil.round1(recommendedWater));
        result.put("reason", reason);
        result.put("confidence", ValueUtil.round1(confidence));
        result.put("decisionResult", needIrrigation ? "需要灌溉" : "无需灌溉");
        result.put("strategyName", strategy == null ? "系统默认参数" : strategy.getName());
        return result;
    }

    /** 按缺水程度计算灌溉量：缺得越多补得越多，并限制在基础水量的 0.6~1.6 倍 */
    private double calcWater(double baseWater, double humidity, double target) {
        if (target <= 0) {
            return baseWater;
        }
        double deficitRatio = (target - humidity) / target;
        deficitRatio = Math.max(0.0, Math.min(1.0, deficitRatio));
        return baseWater * (0.6 + 1.0 * deficitRatio);
    }

    /**
     * 置信度计算：
     * 基础 0.75 + 距阈值裕度(最多 +0.20) + 环境未越界(+0.03) - 数据陈旧(-0.15)，
     * 最终裁剪到 [0.50, 0.97]。
     */
    private double calcConfidence(double humidity, double humidityMin,
                                  double temperature, double tempMax,
                                  double light, double lightMax,
                                  Date collectTime) {
        double confidence = 0.75;

        // 湿度越低于下限（或越高于下限），结论越可靠
        double margin = Math.abs(humidity - humidityMin) / Math.max(humidityMin, 1.0);
        confidence += Math.min(0.20, margin * 0.5);

        // 温度、光照未越过策略上限时环境判断更可靠
        if (temperature <= tempMax && light <= lightMax) {
            confidence += 0.03;
        }

        // 数据陈旧则降低置信度
        if (collectTime != null) {
            long ageMinutes = (System.currentTimeMillis() - collectTime.getTime()) / 60000L;
            if (ageMinutes > 15) {
                confidence -= 0.15;
            } else if (ageMinutes > 5) {
                confidence -= 0.05;
            }
        }

        confidence = Math.max(0.50, Math.min(0.97, confidence));
        return confidence * 100.0;
    }

    /** 解析地块应当使用的策略：优先地块绑定策略，其次全局默认策略 */
    public IrrigationStrategy resolveStrategy(Farmland farmland) {
        if (farmland != null && farmland.getStrategyId() != null) {
            IrrigationStrategy strategy = strategyMapper.findById(farmland.getStrategyId());
            if (strategy != null && isEnabled(strategy.getEnabled())) {
                return strategy;
            }
        }
        return strategyMapper.findGlobalDefault();
    }

    // ==================================================================
    // 二、灌溉执行
    // ==================================================================

    /**
     * 手动模式：农户选择地块后直接下发灌溉指令。
     * 会先校验地块上的灌溉阀门状态，阀门故障时拒绝执行并产生高等级告警。
     */
    public IrrigationRecord manualIrrigate(Farmland farmland, Double waterAmount, Integer durationMinutes,
                                           Integer fertilizerRecipeId, Integer operatorId, String remark) {
        if (farmland == null) {
            throw new BusinessException("地块不存在");
        }
        if (waterAmount == null || waterAmount <= 0) {
            throw new BusinessException("请填写有效的灌溉水量");
        }
        if (recordMapper.countRunning(farmland.getId()) > 0) {
            throw new BusinessException("该地块已有灌溉任务正在执行，请等待完成后再下发指令");
        }

        // 校验灌溉阀门设备状态
        Device valve = findIrrigationValve(farmland.getId());
        if (valve != null && (Constants.DEVICE_FAULT.equals(valve.getStatus())
                || Constants.DEVICE_MAINTENANCE.equals(valve.getStatus()))) {
            String reason = "灌溉阀门 " + valve.getDeviceCode() + " 当前状态为"
                    + statusText(valve.getStatus()) + "，指令未执行";
            alarmService.raiseIrrigationFailure(farmland, valve.getId(), reason);
            throw new BusinessException(reason + "，请先排查设备故障");
        }

        Date now = new Date();
        int duration = durationMinutes == null || durationMinutes <= 0 ? DEFAULT_DURATION : durationMinutes;

        // 记录决策
        IrrigationDecision decision = new IrrigationDecision();
        decision.setFarmlandId(farmland.getId());
        decision.setDecisionTime(now);
        decision.setRecommendedWater(waterAmount);
        decision.setActualWater(waterAmount);
        decision.setDecisionResult("需要灌溉");
        decision.setReason("农户手动下发灌溉指令");
        decision.setConfidence(100.0);
        decision.setTriggerSource(Constants.TRIGGER_MANUAL);
        decisionMapper.insert(decision);

        IrrigationRecord record = new IrrigationRecord();
        record.setFarmlandId(farmland.getId());
        record.setDecisionId(decision.getId());
        record.setTriggerType(Constants.TRIGGER_MANUAL);
        record.setWaterAmount(waterAmount);
        record.setDurationMinutes(duration);
        record.setFertilizerRecipeId(fertilizerRecipeId);
        record.setStatus(Constants.IRRIGATION_RUNNING);
        record.setStartTime(now);
        record.setOperatorId(operatorId);
        record.setRemark(remark == null || remark.isEmpty() ? "农户手动下发" : remark);
        record.setCreatedAt(now);
        recordMapper.insert(record);

        if (valve != null) {
            deviceMapper.heartbeat(valve.getId(), now);
        }

        log.info("手动灌溉指令已下发：地块={} 水量={}m³ 时长={}分钟 操作人={}",
                farmland.getName(), waterAmount, duration, operatorId);
        return record;
    }

    /**
     * 自动模式：由自动灌溉巡检任务调用，按策略生成决策并执行。
     */
    public IrrigationRecord autoIrrigate(Farmland farmland, IrrigationStrategy strategy,
                                         SensorData latest, Map<String, Object> decision) {
        Date now = new Date();
        double water = ValueUtil.toDouble(decision.get("recommendedWater"), DEFAULT_WATER);
        int duration = strategy != null && strategy.getDurationMinutes() != null
                ? strategy.getDurationMinutes() : DEFAULT_DURATION;

        IrrigationDecision decisionRecord = new IrrigationDecision();
        decisionRecord.setFarmlandId(farmland.getId());
        decisionRecord.setDecisionTime(now);
        decisionRecord.setRecommendedWater(water);
        decisionRecord.setDecisionResult("需要灌溉");
        decisionRecord.setReason("自动策略触发：" + decision.get("reason"));
        decisionRecord.setConfidence(ValueUtil.toDouble(decision.get("confidence"), 85.0));
        decisionRecord.setTriggerSource(Constants.TRIGGER_AUTO);
        decisionMapper.insert(decisionRecord);

        IrrigationRecord record = new IrrigationRecord();
        record.setFarmlandId(farmland.getId());
        record.setStrategyId(strategy == null ? null : strategy.getId());
        record.setDecisionId(decisionRecord.getId());
        record.setTriggerType(Constants.TRIGGER_AUTO);
        record.setWaterAmount(water);
        record.setDurationMinutes(duration);
        record.setStatus(Constants.IRRIGATION_RUNNING);
        record.setStartTime(now);
        record.setRemark("自动灌溉：当前湿度 " + fmt(latest.getSoilHumidity()) + "% 低于策略下限 "
                + fmt(strategy == null ? DEFAULT_HUMIDITY_MIN : strategy.getSoilHumidityMin()) + "%");
        record.setCreatedAt(now);
        recordMapper.insert(record);

        // 联动灌溉阀门心跳
        Device valve = findIrrigationValve(farmland.getId());
        if (valve != null) {
            deviceMapper.heartbeat(valve.getId(), now);
        }

        log.info("自动灌溉已触发：地块={} 湿度={}% 水量={}m³ 策略={}",
                farmland.getName(), latest.getSoilHumidity(), water,
                strategy == null ? "默认" : strategy.getName());
        return record;
    }

    /**
     * 回收已到时的灌溉任务：把 RUNNING 且已超过计划时长的记录置为 SUCCESS，
     * 并写入一条灌溉后的湿度回升数据，形成"监测 → 灌溉 → 监测"的闭环。
     *
     * @return 本次完成的记录数
     */
    public int completeRunningRecords() {
        List<IrrigationRecord> running = recordMapper.findAll(null, null, Constants.IRRIGATION_RUNNING, 100);
        int completed = 0;
        long nowMillis = System.currentTimeMillis();

        for (IrrigationRecord record : running) {
            if (record.getStartTime() == null) {
                continue;
            }
            int duration = record.getDurationMinutes() == null ? DEFAULT_DURATION : record.getDurationMinutes();
            long finishAt = record.getStartTime().getTime() + duration * 60_000L;
            if (nowMillis < finishAt) {
                continue;
            }

            Date endTime = new Date(finishAt);
            recordMapper.updateStatus(record.getId(), Constants.IRRIGATION_SUCCESS, endTime);
            decisionMapper.updateActualWater(record.getDecisionId(), record.getWaterAmount());
            simulatePostIrrigation(record);
            completed++;

            log.info("灌溉任务完成：记录ID={} 地块={} 水量={}m³",
                    record.getId(), record.getFarmlandId(), record.getWaterAmount());
        }
        return completed;
    }

    /** 灌溉后湿度回升的模拟：仅在数据模拟器开启时写入，真实环境由设备自行上报 */
    private void simulatePostIrrigation(IrrigationRecord record) {
        Farmland farmland = farmlandMapper.findById(record.getFarmlandId());
        if (farmland == null) {
            return;
        }
        IrrigationStrategy strategy = resolveStrategy(farmland);
        double target = strategy != null && strategy.getTargetHumidity() != null
                ? strategy.getTargetHumidity() : DEFAULT_TARGET_HUMIDITY;

        SensorData latest = sensorDataMapper.getLatestOne(record.getFarmlandId());
        if (latest == null || latest.getSoilHumidity() == null) {
            return;
        }

        double current = latest.getSoilHumidity();
        if (current >= target) {
            return; // 已经达到目标湿度，无需模拟回升
        }
        double recovered = current + (target - current) * 0.75;

        SensorData after = new SensorData();
        after.setFarmlandId(record.getFarmlandId());
        after.setDeviceId(latest.getDeviceId());
        after.setSoilHumidity(ValueUtil.round1(recovered));
        after.setTemperature(latest.getTemperature());
        after.setLightIntensity(latest.getLightIntensity());
        after.setCollectTime(new Date());
        sensorDataMapper.insert(after);
    }

    // ==================================================================
    // 三、数据模拟（仅开发环境使用）
    // ==================================================================

    /**
     * 生成一条模拟采集数据。湿度会在上一次读数附近随机游走，
     * 使演示过程既有自然波动，也会出现低于阈值需要灌溉的情形。
     */
    public SensorData simulateReading(Farmland farmland, Integer deviceId) {
        SensorData latest = sensorDataMapper.getLatestOne(farmland.getId());

        double humidity;
        double temperature;
        double light;

        if (latest != null && latest.getSoilHumidity() != null) {
            // 自然蒸发：湿度缓慢下降，并有小幅波动
            humidity = latest.getSoilHumidity() - 0.4 - RANDOM.nextDouble() * 1.2
                    + (RANDOM.nextDouble() < 0.15 ? RANDOM.nextDouble() * 8.0 : 0);
            humidity = Math.max(12.0, Math.min(95.0, humidity));
            temperature = latest.getTemperature() == null ? 26.0
                    : clamp(latest.getTemperature() + RANDOM.nextDouble() * 2.0 - 1.0, 12.0, 42.0);
            light = latest.getLightIntensity() == null ? 18000.0
                    : clamp(latest.getLightIntensity() + RANDOM.nextDouble() * 3000.0 - 1500.0, 200.0, 70000.0);
        } else {
            humidity = 42.0 + RANDOM.nextDouble() * 18.0;
            temperature = 22.0 + RANDOM.nextDouble() * 10.0;
            light = 8000.0 + RANDOM.nextDouble() * 16000.0;
        }

        SensorData data = new SensorData();
        data.setFarmlandId(farmland.getId());
        data.setDeviceId(deviceId);
        data.setSoilHumidity(ValueUtil.round1(humidity));
        data.setTemperature(ValueUtil.round1(temperature));
        data.setLightIntensity(ValueUtil.round1(light));
        data.setCollectTime(new Date());
        return data;
    }

    /** 兼容旧接口：按地块生成一条模拟数据并入库 */
    public SensorData generateMockData(Integer farmlandId) {
        Farmland farmland = farmlandMapper.findById(farmlandId);
        if (farmland == null) {
            throw new BusinessException("地块不存在");
        }
        Device sensor = findFirstSensor(farmlandId);
        SensorData data = simulateReading(farmland, sensor == null ? null : sensor.getId());
        sensorDataMapper.insert(data);
        alarmService.evaluateSensorData(data);
        return data;
    }

    // ==================================================================
    // 四、查询
    // ==================================================================

    public List<IrrigationRecord> recordsByFarmland(Integer farmlandId, Integer limit) {
        return recordMapper.findByFarmland(farmlandId, limit == null ? 50 : limit);
    }

    public List<IrrigationRecord> recordsByUser(Integer userId, Integer limit) {
        return recordMapper.findByUser(userId, limit == null ? 100 : limit);
    }

    public List<IrrigationRecord> allRecords(Integer farmlandId, String triggerType, String status, Integer limit) {
        return recordMapper.findAll(farmlandId, triggerType, status, limit == null ? 200 : limit);
    }

    public List<IrrigationDecision> decisionHistory(Integer farmlandId, Integer limit) {
        return decisionMapper.getHistory(farmlandId, limit == null ? 20 : limit);
    }

    /** 取消尚未开始的灌溉任务，并把对应决策标记为未执行 */
    public void cancelRecord(Integer recordId) {
        IrrigationRecord record = recordMapper.findById(recordId);
        if (record == null) {
            throw new BusinessException("灌溉任务不存在");
        }
        if (!Constants.IRRIGATION_RUNNING.equals(record.getStatus())
                && !Constants.IRRIGATION_PENDING.equals(record.getStatus())) {
            throw new BusinessException("该任务已结束，无法取消");
        }
        recordMapper.updateStatus(recordId, Constants.IRRIGATION_CANCELLED, new Date());
        if (record.getDecisionId() != null) {
            decisionMapper.updateActualWater(record.getDecisionId(), 0.0);
        }
    }

    // ==================================================================
    // 五、内部工具
    // ==================================================================

    private Device findIrrigationValve(Integer farmlandId) {
        for (Device device : deviceMapper.findByFarmlandId(farmlandId)) {
            if (Constants.DEVICE_IRRIGATION_VALVE.equals(device.getDeviceType())) {
                return device;
            }
        }
        return null;
    }

    private Device findFirstSensor(Integer farmlandId) {
        for (Device device : deviceMapper.findByFarmlandId(farmlandId)) {
            if (SensorDataService.isDeviceTypeSensor(device.getDeviceType())) {
                return device;
            }
        }
        return null;
    }

    private boolean isEnabled(Integer flag) {
        return flag != null && flag == 1;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String fmt(Double value) {
        return value == null ? "-" : String.valueOf(ValueUtil.round1(value));
    }

    private String statusText(String status) {
        if (Constants.DEVICE_FAULT.equals(status)) {
            return "故障";
        }
        if (Constants.DEVICE_MAINTENANCE.equals(status)) {
            return "维护中";
        }
        if (Constants.DEVICE_OFFLINE.equals(status)) {
            return "离线";
        }
        return status;
    }

    /** 用于前端展示的手动灌溉可选时长档位 */
    public List<Integer> durationOptions() {
        List<Integer> options = new ArrayList<>();
        options.add(5);
        options.add(10);
        options.add(15);
        options.add(20);
        options.add(30);
        return options;
    }
}
