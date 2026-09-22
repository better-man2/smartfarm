package com.example.smartfarm.controller;

import com.example.smartfarm.common.ApiResult;
import com.example.smartfarm.common.RoleRequired;
import com.example.smartfarm.common.SessionUtil;
import com.example.smartfarm.entity.User;
import com.example.smartfarm.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端：农户账号与权限管理。
 */
@RestController
@RequestMapping("/api/admin/user")
@RoleRequired
public class AdminUserController {

    @Autowired
    private UserService userService;

    /**
     * 账号列表
     * GET /api/admin/user/list?role=farmer
     */
    @GetMapping("/list")
    public ApiResult<List<User>> list(@RequestParam(required = false) String role) {
        return ApiResult.ok(userService.listUsers(role));
    }

    /**
     * 新增账号
     * POST /api/admin/user/add
     */
    @PostMapping("/add")
    public ApiResult<User> add(@RequestBody User user) {
        // 未填写密码时由服务层赋予初始密码 123456
        return ApiResult.ok("账号创建成功", userService.saveUser(user, true));
    }

    /**
     * 修改账号（用户名、角色、联系方式）
     * POST /api/admin/user/update
     */
    @PostMapping("/update")
    public ApiResult<User> update(@RequestBody User user) {
        return ApiResult.ok("账号信息已更新", userService.saveUser(user, false));
    }

    /**
     * 启用 / 禁用账号（权限控制）
     * POST /api/admin/user/{id}/status?status=1
     */
    @PostMapping("/{id}/status")
    public ApiResult<Void> toggleStatus(@PathVariable Integer id,
                                        @RequestParam Integer status,
                                        HttpSession session) {
        Integer operatorId = SessionUtil.requireUserId(session);
        userService.toggleStatus(id, status, operatorId);
        return ApiResult.ok(status == 1 ? "账号已启用" : "账号已禁用", null);
    }

    /**
     * 重置密码
     * POST /api/admin/user/{id}/reset-password
     * 请求体：{ password }  不传则重置为 123456
     */
    @PostMapping("/{id}/reset-password")
    public ApiResult<Map<String, Object>> resetPassword(@PathVariable Integer id,
                                                        @RequestBody(required = false) Map<String, String> params) {
        String password = params == null ? null : params.get("password");
        userService.resetPassword(id, password);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("password", password == null || password.isEmpty() ? "123456" : password);
        return ApiResult.ok("密码已重置", data);
    }

    /**
     * 删除账号
     * DELETE /api/admin/user/delete/{id}
     */
    @DeleteMapping("/delete/{id}")
    public ApiResult<Void> delete(@PathVariable Integer id, HttpSession session) {
        Integer operatorId = SessionUtil.requireUserId(session);
        userService.deleteUser(id, operatorId);
        return ApiResult.ok("账号已删除", null);
    }
}
