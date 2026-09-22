package com.example.smartfarm.controller;

import com.example.smartfarm.common.ApiResult;
import com.example.smartfarm.common.Constants;
import com.example.smartfarm.common.SessionUtil;
import com.example.smartfarm.entity.User;
import com.example.smartfarm.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * 登录与个人账号接口。
 */
@RestController
@RequestMapping("/api")
public class LoginController {

    @Autowired
    private UserService userService;

    /**
     * 用户登录
     * POST /api/login
     */
    @PostMapping("/login")
    public ApiResult<Map<String, Object>> login(@RequestBody Map<String, String> params, HttpSession session) {
        String username = params.get("username");
        String password = params.get("password");

        User user = userService.login(username, password);
        if (user == null) {
            // 区分"账号被禁用"与"密码错误"，便于用户定位问题
            if (userService.isDisabled(username)) {
                return ApiResult.fail("该账号已被管理员禁用，请联系管理员");
            }
            return ApiResult.fail("用户名或密码错误");
        }

        SessionUtil.save(session, user);

        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("role", user.getRole());
        data.put("phone", user.getPhone());
        // 按角色给出跳转页面，前端据此分流到农户端或管理后台
        data.put("home", Constants.ROLE_ADMIN.equals(user.getRole()) ? "/admin.html" : "/farmer.html");
        return ApiResult.ok("登录成功", data);
    }

    /**
     * 获取当前登录用户信息
     * GET /api/user/info
     */
    @GetMapping("/user/info")
    public ApiResult<Map<String, Object>> getUserInfo(HttpSession session) {
        Integer userId = SessionUtil.getUserId(session);
        if (userId == null) {
            return ApiResult.fail("用户未登录");
        }
        User user = userService.getUserById(userId);
        if (user == null) {
            session.invalidate();
            return ApiResult.fail("账号不存在，请重新登录");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("role", user.getRole());
        data.put("phone", user.getPhone());
        data.put("home", Constants.ROLE_ADMIN.equals(user.getRole()) ? "/admin.html" : "/farmer.html");
        return ApiResult.ok(data);
    }

    /**
     * 退出登录
     * GET /api/logout
     */
    @GetMapping("/logout")
    public ApiResult<Void> logout(HttpSession session) {
        session.invalidate();
        return ApiResult.ok("已退出登录", null);
    }

    /**
     * 修改本人密码
     * POST /api/user/change-password
     */
    @PostMapping("/user/change-password")
    public ApiResult<Void> changePassword(@RequestBody Map<String, String> params, HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        userService.changePassword(userId, params.get("oldPassword"), params.get("newPassword"));
        return ApiResult.ok("密码修改成功，请重新登录", null);
    }

    /**
     * 修改本人联系方式
     * POST /api/user/profile
     */
    @PostMapping("/user/profile")
    public ApiResult<Void> updateProfile(@RequestBody Map<String, String> params, HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        User user = new User();
        user.setId(userId);
        user.setPhone(params.get("phone"));
        userService.updateProfile(user);
        return ApiResult.ok("联系方式已更新", null);
    }
}
