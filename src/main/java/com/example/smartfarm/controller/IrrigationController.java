package com.example.smartfarm.controller;

import com.example.smartfarm.common.ApiResult;
import com.example.smartfarm.common.Constants;
import com.example.smartfarm.common.RoleRequired;
import com.example.smartfarm.common.SessionUtil;
import com.example.smartfarm.entity.Farmland;
import com.example.smartfarm.entity.IrrigationDecision;
import com.example.smartfarm.entity.IrrigationRecord;
import com.example.smartfarm.entity.IrrigationStrategy;
import com.example.smartfarm.entity.SensorData;
import com.example.smartfarm.service.AutoIrrigationScheduler;
import com.example.smartfarm.service.FarmlandService;
import com.example.smartfarm.service.IrrigationService;
import com.example.smartfarm.service.SensorDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 灌溉接口：手动下发指令、自动模式状态、决策生成、灌溉记录。
 */
@RestController
@RequestMapping("/api/irrigation")
public class IrrigationController {

    @Autowired
    private IrrigationService irrigationService;
    @Autowired
    private FarmlandService farmlandService;
    @Autowired
    private SensorDataService sensorDataService;
    @Autowired
    private AutoIrrigationScheduler scheduler;

    /**
     * 手动模式：选择地块后直接下发灌溉指令
     * POST /api/irrigation/manual
     * 请求体：{ farmlandId, waterAmount, durationMinutes, fertilizerRecipeId, remark }
     */
    @PostMapping("/manual")
    public ApiResult<IrrigationRecord> manualIrrigate(@RequestBody Map<String, Object> params, HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        Integer farmlandId = toInteger(params.get("farmlandId"));
        if (farmlandId == null) {
            return ApiResult.fail("请先选择地块");
        }

        Farmland farmland = farmlandService.getOwnedFarmland(farmlandId, userId, SessionUtil.isAdmin(session));
        Double waterAmount = toDouble(params.get("waterAmount"));
        Integer duration = toInteger(params.get("durationMinutes"));
        Integer recipeId = toInteger(params.get("fertilizerRecipeId"));
        String remark = params.get("remark") == null ? null : String.valueOf(params.get("remark"));

        IrrigationRecord record = irrigationService.manualIrrigate(
                farmland, waterAmount, duration, recipeId, userId, remark);

        return ApiResult.ok("灌溉指令已下发，预计 " + record.getDurationMinutes() + " 分钟后完成", record);
    }

    /**
     * 生成灌溉决策（不执行，仅给出分析结果）
     * POST /api/irrigation/decision/{farmlandId}
     */
    @PostMapping("/decision/{farmlandId}")
    public ApiResult<Map<String, Object>> generateDecision(@PathVariable Integer farmlandId, HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        Farmland farmland = farmlandService.getOwnedFarmland(farmlandId, userId, SessionUtil.isAdmin(session));

        SensorData latest = sensorDataService.getLatest(farmlandId);
        if (latest == null) {
            return ApiResult.fail("该地块暂无传感器数据，无法生成决策");
        }

        IrrigationStrategy strategy = irrigationService.resolveStrategy(farmland);
        Map<String, Object> decision = irrigationService.makeDecision(latest, strategy);

        Map<String, Object> data = new LinkedHashMap<>(decision);
        data.put("farmlandId", farmlandId);
        data.put("farmlandName", farmland.getName());
        data.put("currentData", latest);
        data.put("autoIrrigation", farmland.getAutoIrrigation());
        return ApiResult.ok("决策生成成功", data);
    }

    /**
     * 自动模式状态：当前模式、生效策略、是否满足灌溉条件
     * GET /api/irrigation/auto/status/{farmlandId}
     */
    @GetMapping("/auto/status/{farmlandId}")
    public ApiResult<Map<String, Object>> autoStatus(@PathVariable Integer farmlandId, HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        Farmland farmland = farmlandService.getOwnedFarmland(farmlandId, userId, SessionUtil.isAdmin(session));

        IrrigationStrategy strategy = irrigationService.resolveStrategy(farmland);
        SensorData latest = sensorDataService.getLatest(farmlandId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("farmlandId", farmlandId);
        data.put("autoIrrigation", farmland.getAutoIrrigation());
        data.put("mode", farmland.getAutoIrrigation() != null && farmland.getAutoIrrigation() == 1 ? "自动模式" : "手动模式");
        data.put("strategy", strategy);

        if (latest != null) {
            data.put("currentHumidity", latest.getSoilHumidity());
            data.put("dataValid", sensorDataService.isFresh(latest));
            if (strategy != null) {
                // 明确告知前端当前是否已达触发条件，避免用户困惑"为什么没自动浇水"
                boolean belowThreshold = latest.getSoilHumidity() != null
                        && strategy.getSoilHumidityMin() != null
                        && latest.getSoilHumidity() < strategy.getSoilHumidityMin();
                data.put("belowThreshold", belowThreshold);
                data.put("humidityMin", strategy.getSoilHumidityMin());
                data.put("allowedWindow", strategy.getAllowedStartTime() + " ~ " + strategy.getAllowedEndTime());
                if (!sensorDataService.isFresh(latest)) {
                    data.put("hint", "当前数据已过期，自动灌溉将等待设备上报新数据后才会判断");
                } else if (!belowThreshold) {
                    data.put("hint", "当前土壤湿度未低于策略下限，暂不需要灌溉");
                } else {
                    data.put("hint", "已满足触发条件，系统将在下一次巡检时执行灌溉");
                }
            }
        } else {
            data.put("dataValid", false);
            data.put("hint", "该地块暂无监测数据，自动灌溉无法判断");
        }
        return ApiResult.ok(data);
    }

    /**
     * 管理端：立即执行一轮自动灌溉巡检
     * POST /api/irrigation/auto/scan
     */
    @PostMapping("/auto/scan")
    @RoleRequired
    public ApiResult<Map<String, Object>> scanNow() {
        int triggered = scheduler.runScanNow();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("triggered", triggered);
        return ApiResult.ok(triggered > 0
                ? "巡检完成，已触发 " + triggered + " 个地块的自动灌溉"
                : "巡检完成，当前没有满足灌溉条件的地块", data);
    }

    /**
     * 取消执行中的灌溉任务
     * POST /api/irrigation/cancel/{recordId}
     */
    @PostMapping("/cancel/{recordId}")
    public ApiResult<Void> cancel(@PathVariable Integer recordId, HttpSession session) {
        SessionUtil.requireUserId(session);
        irrigationService.cancelRecord(recordId);
        return ApiResult.ok("灌溉任务已取消", null);
    }

    /**
     * 生成一条模拟监测数据（演示用，会同步触发告警评估）
     * POST /api/irrigation/mock/{farmlandId}
     */
    @PostMapping("/mock/{farmlandId}")
    public ApiResult<SensorData> generateMockData(@PathVariable Integer farmlandId, HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        farmlandService.getOwnedFarmland(farmlandId, userId, SessionUtil.isAdmin(session));
        return ApiResult.ok("模拟数据已生成", irrigationService.generateMockData(farmlandId));
    }

    /**
     * 某地块的灌溉执行记录
     * GET /api/irrigation/records/farmland/{farmlandId}?limit=50
     */
    @GetMapping("/records/farmland/{farmlandId}")
    public ApiResult<List<IrrigationRecord>> recordsByFarmland(@PathVariable Integer farmlandId,
                                                              @RequestParam(required = false) Integer limit,
                                                              HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        farmlandService.getOwnedFarmland(farmlandId, userId, SessionUtil.isAdmin(session));
        return ApiResult.ok(irrigationService.recordsByFarmland(farmlandId, limit));
    }

    /**
     * 当前农户的全部灌溉记录
     * GET /api/irrigation/records/my?limit=100
     */
    @GetMapping("/records/my")
    public ApiResult<List<IrrigationRecord>> myRecords(@RequestParam(required = false) Integer limit,
                                                       HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        return ApiResult.ok(irrigationService.recordsByUser(userId, limit));
    }

    /**
     * 管理端：全部灌溉记录，可按触发方式与状态过滤
     * GET /api/irrigation/records/all
     */
    @GetMapping("/records/all")
    @RoleRequired
    public ApiResult<List<IrrigationRecord>> allRecords(@RequestParam(required = false) Integer farmlandId,
                                                        @RequestParam(required = false) String triggerType,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(irrigationService.allRecords(farmlandId, triggerType, status, limit));
    }

    /**
     * 某地块的灌溉决策历史
     * GET /api/irrigation/history/{farmlandId}
     */
    @GetMapping("/history/{farmlandId}")
    public ApiResult<List<IrrigationDecision>> decisionHistory(@PathVariable Integer farmlandId,
                                                               @RequestParam(required = false) Integer limit,
                                                               HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        farmlandService.getOwnedFarmland(farmlandId, userId, SessionUtil.isAdmin(session));
        return ApiResult.ok(irrigationService.decisionHistory(farmlandId, limit));
    }

    /**
     * 灌溉可选项：时长档位与执行状态字典（供前端下拉框）
     * GET /api/irrigation/options
     */
    @GetMapping("/options")
    public ApiResult<Map<String, Object>> options() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("durations", irrigationService.durationOptions());
        data.put("triggerTypes", new String[]{Constants.TRIGGER_MANUAL, Constants.TRIGGER_AUTO});
        return ApiResult.ok(data);
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
