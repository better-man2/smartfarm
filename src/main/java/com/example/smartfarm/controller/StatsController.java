package com.example.smartfarm.controller;

import com.example.smartfarm.common.ApiResult;
import com.example.smartfarm.common.RoleRequired;
import com.example.smartfarm.common.SessionUtil;
import com.example.smartfarm.entity.IrrigationRecord;
import com.example.smartfarm.service.FarmlandService;
import com.example.smartfarm.service.SensorDataService;
import com.example.smartfarm.service.StatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 统计与数据看板接口（P1）。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    @Autowired
    private StatsService statsService;
    @Autowired
    private SensorDataService sensorDataService;
    @Autowired
    private FarmlandService farmlandService;

    /**
     * 农户端数据看板：概览计数、环境均值、告警概览、灌溉概览、各地块实时摘要
     * GET /api/stats/dashboard
     */
    @GetMapping("/dashboard")
    public ApiResult<Map<String, Object>> farmerDashboard(HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        return ApiResult.ok(statsService.farmerDashboard(userId));
    }

    /**
     * 历史统计：按天用水量、环境汇总、灌溉汇总、决策汇总
     * GET /api/stats/history?farmlandId=&days=30
     *
     * 不传 farmlandId 时：农户统计自己名下全部地块，管理员统计全平台。
     */
    @GetMapping("/history")
    public ApiResult<Map<String, Object>> history(@RequestParam(required = false) Integer farmlandId,
                                                  @RequestParam(required = false, defaultValue = "30") Integer days,
                                                  HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        boolean isAdmin = SessionUtil.isAdmin(session);

        if (farmlandId != null) {
            // 校验地块归属，防止农户越权查看他人地块的统计数据
            farmlandService.getOwnedFarmland(farmlandId, userId, isAdmin);
        }

        return ApiResult.ok(statsService.historyStats(farmlandId, isAdmin ? null : userId, days));
    }

    /**
     * 管理端全局看板：全部地块汇总、设备在线率、告警统计、用水总量
     * GET /api/stats/admin/overview
     */
    @GetMapping("/admin/overview")
    @RoleRequired
    public ApiResult<Map<String, Object>> adminOverview() {
        return ApiResult.ok(statsService.adminOverview());
    }

    /**
     * 最近的灌溉记录（看板时间线）
     * GET /api/stats/recent-records?limit=10
     */
    @GetMapping("/recent-records")
    public ApiResult<List<IrrigationRecord>> recentRecords(@RequestParam(required = false) Integer limit,
                                                           HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        return ApiResult.ok(statsService.recentRecords(
                SessionUtil.isAdmin(session) ? null : userId, limit));
    }

    /**
     * 清理历史监测数据（管理端）
     * POST /api/stats/cleanup?keepDays=90
     */
    @PostMapping("/cleanup")
    @RoleRequired
    public ApiResult<Map<String, Object>> cleanup(@RequestParam(required = false, defaultValue = "90") Integer keepDays) {
        int deleted = sensorDataService.cleanup(keepDays);
        return ApiResult.ok("已清理 " + deleted + " 条过期监测数据",
                Collections.singletonMap("deleted", deleted));
    }
}
