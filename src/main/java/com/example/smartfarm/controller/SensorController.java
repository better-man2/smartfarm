package com.example.smartfarm.controller;

import com.example.smartfarm.common.ApiResult;
import com.example.smartfarm.common.SessionUtil;
import com.example.smartfarm.entity.SensorData;
import com.example.smartfarm.service.FarmlandService;
import com.example.smartfarm.service.SensorDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 传感器数据接口（P0 土壤湿度监测）。
 */
@RestController
@RequestMapping("/api/sensor")
public class SensorController {

    @Autowired
    private SensorDataService sensorDataService;
    @Autowired
    private FarmlandService farmlandService;

    /**
     * 实时监测数据：最新读数 + 数据有效性 + 健康状态
     * GET /api/sensor/realtime/{farmlandId}
     */
    @GetMapping("/realtime/{farmlandId}")
    public ApiResult<Map<String, Object>> realtime(@PathVariable Integer farmlandId, HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        farmlandService.getOwnedFarmland(farmlandId, userId, SessionUtil.isAdmin(session));
        return ApiResult.ok(sensorDataService.getRealtime(farmlandId));
    }

    /**
     * 最近若干条原始数据
     * GET /api/sensor/latest/{farmlandId}?limit=10
     */
    @GetMapping("/latest/{farmlandId}")
    public ApiResult<List<SensorData>> latest(@PathVariable Integer farmlandId,
                                              @RequestParam(required = false) Integer limit,
                                              HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        farmlandService.getOwnedFarmland(farmlandId, userId, SessionUtil.isAdmin(session));
        return ApiResult.ok(sensorDataService.getLatestList(farmlandId, limit));
    }

    /**
     * 趋势数据（默认最近 24 小时），用于湿度/温度曲线
     * GET /api/sensor/trend/{farmlandId}?hours=24
     */
    @GetMapping("/trend/{farmlandId}")
    public ApiResult<Map<String, Object>> trend(@PathVariable Integer farmlandId,
                                                @RequestParam(required = false, defaultValue = "24") Integer hours,
                                                HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        farmlandService.getOwnedFarmland(farmlandId, userId, SessionUtil.isAdmin(session));

        List<SensorData> list = sensorDataService.getTrend(farmlandId, hours);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("count", list.size());
        data.put("hours", hours);
        return ApiResult.ok(data);
    }

    /**
     * 按天聚合的监测统计
     * GET /api/sensor/stat/{farmlandId}?days=7
     */
    @GetMapping("/stat/{farmlandId}")
    public ApiResult<Map<String, Object>> statByDay(@PathVariable Integer farmlandId,
                                                    @RequestParam(required = false, defaultValue = "7") Integer days,
                                                    HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        farmlandService.getOwnedFarmland(farmlandId, userId, SessionUtil.isAdmin(session));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", sensorDataService.statByDay(farmlandId, days));
        data.put("summary", sensorDataService.summary(farmlandId, days));
        return ApiResult.ok(data);
    }

    /**
     * 设备数据上报入口（设备网关调用，无需登录）。
     * 上报后立即执行告警评估，因此这里是 P0 "监测 → 告警" 的入口。
     * POST /api/sensor/ingest
     */
    @PostMapping("/ingest")
    public ApiResult<SensorData> ingest(@RequestBody SensorData data) {
        return ApiResult.ok("数据上报成功", sensorDataService.ingest(data));
    }
}
