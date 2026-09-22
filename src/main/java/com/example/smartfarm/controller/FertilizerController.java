package com.example.smartfarm.controller;

import com.example.smartfarm.common.ApiResult;
import com.example.smartfarm.common.RoleRequired;
import com.example.smartfarm.common.SessionUtil;
import com.example.smartfarm.entity.FertilizerRecipe;
import com.example.smartfarm.service.FarmlandService;
import com.example.smartfarm.service.FertilizerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.List;

/**
 * 水肥配比接口（P2）。
 * 农户可查看自己地块可用的配比方案；管理员负责维护方案。
 */
@RestController
@RequestMapping("/api/fertilizer")
public class FertilizerController {

    @Autowired
    private FertilizerService fertilizerService;
    @Autowired
    private FarmlandService farmlandService;

    /**
     * 配比方案列表
     * GET /api/fertilizer/list?farmlandId=&onlyEnabled=
     * 传入 farmlandId 时返回"该地块专属方案 + 通用方案"
     */
    @GetMapping("/list")
    public ApiResult<List<FertilizerRecipe>> list(@RequestParam(required = false) Integer farmlandId,
                                                  @RequestParam(required = false, defaultValue = "false") boolean onlyEnabled,
                                                  HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        if (farmlandId != null) {
            farmlandService.getOwnedFarmland(farmlandId, userId, SessionUtil.isAdmin(session));
        }
        return ApiResult.ok(fertilizerService.list(farmlandId, onlyEnabled));
    }

    /**
     * 方案详情
     * GET /api/fertilizer/{id}
     */
    @GetMapping("/{id}")
    public ApiResult<FertilizerRecipe> getById(@PathVariable Integer id, HttpSession session) {
        SessionUtil.requireUserId(session);
        return ApiResult.ok(fertilizerService.getById(id));
    }

    /**
     * 新增 / 更新配比方案（管理端）
     * POST /api/fertilizer/save
     */
    @PostMapping("/save")
    @RoleRequired
    public ApiResult<FertilizerRecipe> save(@RequestBody FertilizerRecipe recipe) {
        boolean isCreate = recipe.getId() == null;
        return ApiResult.ok(isCreate ? "水肥配比方案已创建" : "水肥配比方案已更新",
                fertilizerService.save(recipe));
    }

    /**
     * 删除配比方案（管理端）
     * DELETE /api/fertilizer/delete/{id}
     */
    @DeleteMapping("/delete/{id}")
    @RoleRequired
    public ApiResult<Void> delete(@PathVariable Integer id) {
        fertilizerService.delete(id);
        return ApiResult.ok("水肥配比方案已删除", null);
    }
}
