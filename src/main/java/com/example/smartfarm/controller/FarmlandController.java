package com.example.smartfarm.controller;

import com.example.smartfarm.common.ApiResult;
import com.example.smartfarm.common.RoleRequired;
import com.example.smartfarm.common.SessionUtil;
import com.example.smartfarm.entity.Farmland;
import com.example.smartfarm.service.FarmlandService;
import com.example.smartfarm.service.StatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地块接口。农户只能操作自己的地块，管理员可查看全部地块汇总。
 */
@RestController
@RequestMapping("/api/farmland")
public class FarmlandController {

    @Autowired
    private FarmlandService farmlandService;
    @Autowired
    private StatsService statsService;

    /**
     * 当前农户的地块列表
     * GET /api/farmland/my
     */
    @GetMapping("/my")
    public ApiResult<List<Farmland>> getMyFarmlands(HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        return ApiResult.ok(farmlandService.getFarmlandsByUserId(userId));
    }

    /**
     * 当前农户的地块列表（含最新监测数据摘要，用于看板展示）
     * GET /api/farmland/my/summary
     */
    @GetMapping("/my/summary")
    public ApiResult<List<Map<String, Object>>> getMyFarmlandSummaries(HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Farmland farmland : farmlandService.getFarmlandsByUserId(userId)) {
            summaries.add(statsService.farmlandSummary(farmland));
        }
        return ApiResult.ok(summaries);
    }

    /**
     * 管理端：全部地块汇总
     * GET /api/farmland/all
     */
    @GetMapping("/all")
    @RoleRequired
    public ApiResult<List<Farmland>> getAllFarmlands() {
        return ApiResult.ok(farmlandService.getAllWithStats());
    }

    /**
     * 地块详情
     * GET /api/farmland/{id}
     */
    @GetMapping("/{id}")
    public ApiResult<Farmland> getFarmlandById(@PathVariable Integer id, HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        Farmland farmland = farmlandService.getOwnedFarmland(id, userId, SessionUtil.isAdmin(session));
        return ApiResult.ok(farmland);
    }

    /**
     * 新增地块
     * POST /api/farmland/add
     */
    @PostMapping("/add")
    public ApiResult<Farmland> addFarmland(@RequestBody Farmland farmland, HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        // 管理员新增地块时可通过 ownerId 指定归属农户，否则归自己
        Integer ownerId = SessionUtil.isAdmin(session) && farmland.getUserId() != null
                ? farmland.getUserId() : userId;
        return ApiResult.ok("地块添加成功", farmlandService.addFarmland(farmland, ownerId));
    }

    /**
     * 修改地块
     * POST /api/farmland/update
     */
    @PostMapping("/update")
    public ApiResult<Farmland> updateFarmland(@RequestBody Farmland farmland, HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        return ApiResult.ok("地块信息已更新",
                farmlandService.updateFarmland(farmland, userId, SessionUtil.isAdmin(session)));
    }

    /**
     * 删除地块（同时清理关联的监测数据与灌溉记录）
     * DELETE /api/farmland/delete/{id}
     */
    @DeleteMapping("/delete/{id}")
    public ApiResult<Void> deleteFarmland(@PathVariable Integer id, HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        farmlandService.deleteFarmland(id, userId, SessionUtil.isAdmin(session));
        return ApiResult.ok("地块已删除", null);
    }

    /**
     * 切换手动 / 自动灌溉模式
     * POST /api/farmland/{id}/auto?enabled=true
     */
    @PostMapping("/{id}/auto")
    public ApiResult<Map<String, Object>> switchAutoMode(@PathVariable Integer id,
                                                         @RequestParam boolean enabled,
                                                         HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        farmlandService.setAutoIrrigation(id, enabled, userId, SessionUtil.isAdmin(session));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("farmlandId", id);
        data.put("autoIrrigation", enabled ? 1 : 0);
        data.put("mode", enabled ? "自动模式" : "手动模式");
        return ApiResult.ok(enabled
                ? "已开启自动灌溉，系统将按策略自动执行"
                : "已切换为手动灌溉", data);
    }
}
