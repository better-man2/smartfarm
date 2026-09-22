package com.example.smartfarm.controller;

import com.example.smartfarm.common.ApiResult;
import com.example.smartfarm.common.BusinessException;
import com.example.smartfarm.common.RoleRequired;
import com.example.smartfarm.common.SessionUtil;
import com.example.smartfarm.entity.IrrigationStrategy;
import com.example.smartfarm.service.StrategyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 灌溉策略接口（管理端配置全局策略，也可配置地块专属策略）。
 */
@RestController
@RequestMapping("/api/strategy")
public class StrategyController {

    @Autowired
    private StrategyService strategyService;

    /**
     * 策略列表
     * GET /api/strategy/list?farmlandId=&globalOnly=
     */
    @GetMapping("/list")
    @RoleRequired
    public ApiResult<List<IrrigationStrategy>> list(@RequestParam(required = false) Integer farmlandId,
                                                    @RequestParam(required = false, defaultValue = "false") boolean globalOnly) {
        return ApiResult.ok(strategyService.list(farmlandId, globalOnly));
    }

    /**
     * 当前有效的全局默认策略（配置页展示用）
     * GET /api/strategy/global
     */
    @GetMapping("/global")
    @RoleRequired
    public ApiResult<IrrigationStrategy> globalDefault() {
        return ApiResult.ok(strategyService.globalDefault());
    }

    /**
     * 策略详情
     * GET /api/strategy/{id}
     */
    @GetMapping("/{id}")
    @RoleRequired
    public ApiResult<IrrigationStrategy> getById(@PathVariable Integer id) {
        return ApiResult.ok(strategyService.getById(id));
    }

    /**
     * 新增 / 更新策略
     * POST /api/strategy/save
     */
    @PostMapping("/save")
    @RoleRequired
    public ApiResult<IrrigationStrategy> save(@RequestBody IrrigationStrategy strategy, HttpSession session) {
        Integer operatorId = SessionUtil.requireUserId(session);
        boolean isCreate = strategy.getId() == null;
        return ApiResult.ok(isCreate ? "策略已创建" : "策略已更新",
                strategyService.save(strategy, operatorId));
    }

    /**
     * 启用 / 停用策略
     * POST /api/strategy/{id}/toggle?enabled=1
     */
    @PostMapping("/{id}/toggle")
    @RoleRequired
    public ApiResult<Void> toggle(@PathVariable Integer id, @RequestParam Integer enabled) {
        strategyService.toggle(id, enabled);
        return ApiResult.ok(enabled == 1 ? "策略已启用" : "策略已停用", null);
    }

    /**
     * 删除策略
     * DELETE /api/strategy/delete/{id}
     */
    @DeleteMapping("/delete/{id}")
    @RoleRequired
    public ApiResult<Void> delete(@PathVariable Integer id) {
        strategyService.delete(id);
        return ApiResult.ok("策略已删除", null);
    }

    /**
     * 把策略应用到指定地块
     * POST /api/strategy/apply
     * 请求体：{ farmlandId, strategyId }  strategyId 传 null 表示改用全局策略
     */
    @PostMapping("/apply")
    @RoleRequired
    public ApiResult<Map<String, Object>> apply(@RequestBody Map<String, Object> params) {
        Integer farmlandId = toInteger(params.get("farmlandId"));
        if (farmlandId == null) {
            throw new BusinessException("请选择地块");
        }
        Integer strategyId = toInteger(params.get("strategyId"));

        strategyService.applyToFarmland(farmlandId, strategyId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("farmlandId", farmlandId);
        data.put("strategyId", strategyId);
        return ApiResult.ok(strategyId == null ? "该地块已改用全局策略" : "策略应用成功", data);
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
}
