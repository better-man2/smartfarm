package com.example.smartfarm.config;

import com.example.smartfarm.common.Constants;
import com.example.smartfarm.entity.*;
import com.example.smartfarm.mapper.*;
import com.example.smartfarm.service.AlarmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Calendar;
import java.util.Date;
import java.util.Random;

/**
 * 启动初始化：写入演示所需的种子数据（幂等，仅在 users 表为空时执行）。
 * 使用 Java 代码而非 data.sql，避免 H2 / MySQL 的方言差异。
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    /** 固定随机种子，保证每次启动生成的演示数据形态一致 */
    private static final Random RANDOM = new Random(20260101L);

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private FarmlandMapper farmlandMapper;
    @Autowired
    private DeviceMapper deviceMapper;
    @Autowired
    private SensorDataMapper sensorDataMapper;
    @Autowired
    private IrrigationStrategyMapper strategyMapper;
    @Autowired
    private IrrigationDecisionMapper decisionMapper;
    @Autowired
    private IrrigationRecordMapper recordMapper;
    @Autowired
    private FertilizerRecipeMapper fertilizerMapper;
    @Autowired
    private AlarmRuleMapper alarmRuleMapper;
    @Autowired
    private AlarmService alarmService;

    @Override
    public void run(ApplicationArguments args) {
        if (userMapper.countByUsername("admin") > 0) {
            log.info("检测到已有数据，跳过种子数据初始化");
            // 仍需对当前最新数据做一次告警评估，保证页面一打开就能看到真实告警
            alarmService.evaluateAllFarmlands();
            return;
        }

        log.info("首次启动，开始初始化演示数据...");
        Date now = new Date();

        // ---------- 1. 用户 ----------
        int adminId = insertUser("admin", "123456", Constants.ROLE_ADMIN, "13800000000", now);
        int farmer1Id = insertUser("farmer1", "123456", Constants.ROLE_FARMER, "13800000001", now);
        int farmer2Id = insertUser("farmer2", "123456", Constants.ROLE_FARMER, "13800000002", now);

        // ---------- 2. 灌溉策略 ----------
        IrrigationStrategy globalStrategy = new IrrigationStrategy();
        globalStrategy.setName("全局默认策略");
        globalStrategy.setIsGlobal(1);
        globalStrategy.setSoilHumidityMin(40.0);
        globalStrategy.setTargetHumidity(65.0);
        globalStrategy.setTemperatureMax(38.0);
        globalStrategy.setLightIntensityMax(60000.0);
        globalStrategy.setIrrigationAmount(15.0);
        globalStrategy.setDurationMinutes(10);
        globalStrategy.setAllowedStartTime("06:00");
        globalStrategy.setAllowedEndTime("20:00");
        globalStrategy.setEnabled(1);
        globalStrategy.setCreatedBy(adminId);
        globalStrategy.setCreatedAt(now);
        strategyMapper.insert(globalStrategy);

        IrrigationStrategy greenhouseStrategy = new IrrigationStrategy();
        greenhouseStrategy.setName("大棚精细灌溉策略");
        greenhouseStrategy.setIsGlobal(0);
        greenhouseStrategy.setSoilHumidityMin(55.0);
        greenhouseStrategy.setTargetHumidity(75.0);
        greenhouseStrategy.setTemperatureMax(32.0);
        greenhouseStrategy.setLightIntensityMax(40000.0);
        greenhouseStrategy.setIrrigationAmount(6.5);
        greenhouseStrategy.setDurationMinutes(6);
        greenhouseStrategy.setAllowedStartTime("05:30");
        greenhouseStrategy.setAllowedEndTime("19:30");
        greenhouseStrategy.setEnabled(1);
        greenhouseStrategy.setCreatedBy(adminId);
        greenhouseStrategy.setCreatedAt(now);
        // farmlandId 稍后随地块创建再回填
        strategyMapper.insert(greenhouseStrategy);

        // ---------- 3. 地块 ----------
        // 地块2(小麦试验田) 开启自动灌溉，用于演示"自动模式"闭环
        int f1 = insertFarmland(farmer1Id, "玉米田1号", 12.5, "玉米", "北京市昌平区小汤山镇", 0,
                globalStrategy.getId(), now);
        int f2 = insertFarmland(farmer1Id, "小麦试验田", 8.0, "小麦", "北京市昌平区小汤山镇", 1,
                globalStrategy.getId(), now);
        int f3 = insertFarmland(farmer2Id, "蔬菜大棚", 3.5, "番茄", "北京市顺义区赵全营镇", 1,
                greenhouseStrategy.getId(), now);

        // 回填大棚策略归属地块
        greenhouseStrategy.setFarmlandId(f3);
        strategyMapper.update(greenhouseStrategy);

        // ---------- 4. 设备 ----------
        int sm1 = insertDevice("SM-001", "土壤湿度传感器#1", Constants.DEVICE_SOIL_MOISTURE, f1,
                Constants.DEVICE_ONLINE, "玉米田1号-中央", now);
        insertDevice("ST-001", "温度传感器#1", Constants.DEVICE_TEMPERATURE, f1,
                Constants.DEVICE_ONLINE, "玉米田1号-东侧", now);
        // 故意留一个故障设备，用于演示"告警 -> 查看设备状态 -> 故障排查"
        insertDevice("SL-001", "光照传感器#1", Constants.DEVICE_LIGHT, f1,
                Constants.DEVICE_FAULT, "玉米田1号-西侧", now);

        int sm2 = insertDevice("SM-002", "土壤湿度传感器#2", Constants.DEVICE_SOIL_MOISTURE, f2,
                Constants.DEVICE_ONLINE, "小麦试验田-中央", now);
        insertDevice("ST-002", "温度传感器#2", Constants.DEVICE_TEMPERATURE, f2,
                Constants.DEVICE_ONLINE, "小麦试验田-北侧", now);
        insertDevice("VV-001", "灌溉电磁阀#1", Constants.DEVICE_IRRIGATION_VALVE, f2,
                Constants.DEVICE_ONLINE, "小麦试验田-泵房", now);

        int sm3 = insertDevice("SM-003", "土壤湿度传感器#3", Constants.DEVICE_SOIL_MOISTURE, f3,
                Constants.DEVICE_ONLINE, "蔬菜大棚-中央", now);
        insertDevice("WS-001", "农业气象站", Constants.DEVICE_WEATHER, f3,
                Constants.DEVICE_ONLINE, "蔬菜大棚-顶部", now);
        insertDevice("FM-001", "水肥一体机", Constants.DEVICE_FERTILIZER_MIXER, f3,
                Constants.DEVICE_ONLINE, "蔬菜大棚-泵房", now);

        // 未绑定设备，用于演示管理端"设备绑定"
        insertDevice("SM-004", "土壤湿度传感器#4", Constants.DEVICE_SOIL_MOISTURE, null,
                Constants.DEVICE_UNBOUND, "仓库", now);
        insertDevice("ST-003", "温度传感器#3", Constants.DEVICE_TEMPERATURE, null,
                Constants.DEVICE_UNBOUND, "仓库", now);

        // ---------- 5. 水肥配比方案 ----------
        insertRecipe("玉米拔节期水肥方案", null, "玉米", 3.0, 1.0, 2.0, 1.80, 6.20, 0.20, now);
        insertRecipe("小麦返青期水肥方案", null, "小麦", 2.5, 1.2, 1.5, 1.60, 6.50, 0.18, now);
        insertRecipe("番茄结果期水肥方案", f3, "番茄", 1.0, 1.0, 2.5, 2.20, 6.00, 0.25, now);

        // ---------- 6. 告警阈值规则 ----------
        // 只有 HIGH 等级 + pushEnabled=1 的规则会推送通知；其余仅页面记录
        insertRule("土壤湿度严重不足", Constants.METRIC_SOIL_HUMIDITY, Constants.OP_LT, 30.0,
                Constants.ALARM_HIGH, 1, "低于30%判定为严重干旱，需立即灌溉", now);
        insertRule("土壤湿度偏低", Constants.METRIC_SOIL_HUMIDITY, Constants.OP_LT, 40.0,
                Constants.ALARM_LOW, 0, "低于40%提示偏干，仅页面记录", now);
        insertRule("温度过高", Constants.METRIC_TEMPERATURE, Constants.OP_GT, 38.0,
                Constants.ALARM_HIGH, 1, "高于38℃可能造成作物热害", now);
        insertRule("温度偏高", Constants.METRIC_TEMPERATURE, Constants.OP_GT, 35.0,
                Constants.ALARM_LOW, 0, "高于35℃提示高温，仅页面记录", now);
        insertRule("光照过强", Constants.METRIC_LIGHT_INTENSITY, Constants.OP_GT, 60000.0,
                Constants.ALARM_LOW, 0, "光照超过60000lux，蒸发加快", now);
        insertRule("设备离线超时", Constants.METRIC_DEVICE_OFFLINE_MINUTES, Constants.OP_GT, 30.0,
                Constants.ALARM_HIGH, 1, "设备超过30分钟无心跳判定为离线故障", now);

        // ---------- 7. 24 小时传感器历史数据 ----------
        // 各地块最后一条数据的湿度分别设置为：适宜 / 偏低(触发自动灌溉) / 严重干旱(触发HIGH告警推送)
        seedSensorHistory(f1, sm1, 52.0, 26.0, 18000.0, now);
        seedSensorHistory(f2, sm2, 35.0, 28.0, 22000.0, now);
        seedSensorHistory(f3, sm3, 27.0, 33.0, 12000.0, now);

        // ---------- 8. 历史灌溉记录与决策（用于历史统计） ----------
        seedIrrigationHistory(f1, farmer1Id, now);
        seedIrrigationHistory(f2, farmer1Id, now);

        log.info("演示数据初始化完成：用户3个 / 地块3块 / 设备11台 / 策略2条 / 阈值规则6条 / 水肥方案3个");

        // ---------- 9. 对最新数据立即执行一次告警评估 ----------
        alarmService.evaluateAllFarmlands();
        log.info("已完成首次告警评估，当前未处理告警 {} 条（其中高等级 {} 条）",
                alarmService.countUnread(), alarmService.countUnreadHigh());
    }

    private int insertUser(String username, String password, String role, String phone, Date now) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        user.setRole(role);
        user.setPhone(phone);
        user.setStatus(Constants.USER_ENABLED);
        user.setCreatedAt(now);
        userMapper.insert(user);
        return user.getId();
    }

    private int insertFarmland(int userId, String name, double area, String cropType, String location,
                               int autoIrrigation, Integer strategyId, Date now) {
        Farmland farmland = new Farmland();
        farmland.setUserId(userId);
        farmland.setName(name);
        farmland.setArea(area);
        farmland.setCropType(cropType);
        farmland.setLocation(location);
        farmland.setAutoIrrigation(autoIrrigation);
        farmland.setStrategyId(strategyId);
        farmland.setCreatedAt(now);
        farmlandMapper.insert(farmland);
        return farmland.getId();
    }

    private int insertDevice(String code, String name, String type, Integer farmlandId, String status,
                             String location, Date now) {
        Device device = new Device();
        device.setDeviceCode(code);
        device.setDeviceName(name);
        device.setDeviceType(type);
        device.setFarmlandId(farmlandId);
        device.setStatus(status);
        device.setInstallLocation(location);
        device.setFirmware("v1.2.0");
        // 维护中的设备不参与离线判定
        device.setLastOnlineTime(Constants.DEVICE_MAINTENANCE.equals(status) ? null : now);
        device.setCreatedAt(now);
        device.setUpdatedAt(now);
        deviceMapper.insert(device);
        return device.getId();
    }

    private void insertRecipe(String name, Integer farmlandId, String cropType, double n, double p, double k,
                              double ec, double ph, double concentration, Date now) {
        FertilizerRecipe recipe = new FertilizerRecipe();
        recipe.setName(name);
        recipe.setFarmlandId(farmlandId);
        recipe.setCropType(cropType);
        recipe.setNRatio(n);
        recipe.setPRatio(p);
        recipe.setKRatio(k);
        recipe.setEcTarget(ec);
        recipe.setPhTarget(ph);
        recipe.setConcentration(concentration);
        recipe.setEnabled(1);
        recipe.setCreatedAt(now);
        fertilizerMapper.insert(recipe);
    }

    private void insertRule(String name, String metric, String op, double threshold, String level,
                            int pushEnabled, String description, Date now) {
        AlarmRule rule = new AlarmRule();
        rule.setRuleName(name);
        rule.setMetric(metric);
        rule.setCompareOp(op);
        rule.setThreshold(threshold);
        rule.setAlarmLevel(level);
        rule.setPushEnabled(pushEnabled);
        rule.setEnabled(1);
        rule.setDescription(description);
        rule.setCreatedAt(now);
        alarmRuleMapper.insert(rule);
    }

    /**
     * 生成最近 24 小时的采集数据（每 30 分钟一条），并让湿度在一天内自然波动，
     * 最后一条使用传入的目标湿度，以便演示自动灌溉与告警。
     */
    private void seedSensorHistory(int farmlandId, Integer deviceId, double lastHumidity,
                                   double baseTemperature, double baseLight, Date now) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(now);
        cal.add(Calendar.HOUR_OF_DAY, -24);
        Date start = cal.getTime();

        int points = 48;
        long stepMillis = 30L * 60L * 1000L;

        for (int i = 0; i < points; i++) {
            Date collectTime = new Date(start.getTime() + stepMillis * i);
            double progress = (double) i / (points - 1);

            // 湿度：白天蒸发下降、夜间回升，最后一条收敛到指定值
            double humidity = 45.0 + 12.0 * Math.sin(progress * Math.PI * 2) + RANDOM.nextDouble() * 4.0;
            if (i == points - 1) {
                humidity = lastHumidity;
            }

            SensorData data = new SensorData();
            data.setFarmlandId(farmlandId);
            data.setDeviceId(deviceId);
            data.setSoilHumidity(round(humidity));
            data.setTemperature(round(baseTemperature + RANDOM.nextDouble() * 5.0 - 2.5));
            data.setLightIntensity(round(baseLight + RANDOM.nextDouble() * 6000.0 - 3000.0));
            data.setCollectTime(collectTime);
            sensorDataMapper.insert(data);
        }
    }

    /** 生成最近 7 天的灌溉决策与执行记录，让历史统计有真实数据可算 */
    private void seedIrrigationHistory(int farmlandId, int operatorId, Date now) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(now);
        cal.add(Calendar.DAY_OF_MONTH, -6);   // 往前 6 天，保证全部记录落在"近7天"统计窗口内

        for (int day = 0; day < 7; day++) {
            if (RANDOM.nextDouble() < 0.35) {
                continue; // 有些天没有灌溉
            }
            Calendar dayCal = Calendar.getInstance();
            dayCal.setTime(cal.getTime());
            dayCal.add(Calendar.DAY_OF_MONTH, day);
            dayCal.set(Calendar.HOUR_OF_DAY, 7 + RANDOM.nextInt(9));
            dayCal.set(Calendar.MINUTE, RANDOM.nextInt(60));
            Date decisionTime = dayCal.getTime();

            double recommended = round(10.0 + RANDOM.nextDouble() * 10.0);
            double actual = round(recommended * (0.85 + RANDOM.nextDouble() * 0.15));
            double confidence = round(78.0 + RANDOM.nextDouble() * 18.0);

            IrrigationDecision decision = new IrrigationDecision();
            decision.setFarmlandId(farmlandId);
            decision.setDecisionTime(decisionTime);
            decision.setRecommendedWater(recommended);
            decision.setActualWater(actual);
            decision.setDecisionResult("需要灌溉");
            decision.setReason("土壤湿度低于策略下限，按策略建议灌溉 "
                    + recommended + "m³");
            decision.setConfidence(confidence);
            decision.setTriggerSource(RANDOM.nextBoolean() ? Constants.TRIGGER_AUTO : Constants.TRIGGER_MANUAL);
            decisionMapper.insert(decision);

            IrrigationRecord record = new IrrigationRecord();
            record.setFarmlandId(farmlandId);
            record.setDecisionId(decision.getId());
            record.setTriggerType(decision.getTriggerSource());
            record.setWaterAmount(actual);
            record.setDurationMinutes(8 + RANDOM.nextInt(8));
            record.setStatus(Constants.IRRIGATION_SUCCESS);
            record.setStartTime(decisionTime);
            record.setEndTime(new Date(decisionTime.getTime() + 10 * 60 * 1000L));
            record.setOperatorId(operatorId);
            record.setRemark("历史灌溉记录");
            record.setCreatedAt(decisionTime);
            recordMapper.insert(record);
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
