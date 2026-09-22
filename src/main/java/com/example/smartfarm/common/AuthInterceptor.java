package com.example.smartfarm.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;

/**
 * 登录态与角色校验拦截器。
 * 1) 未登录访问受保护接口 → 返回 success=false，提示重新登录；
 * 2) 方法/类上标注 @RoleRequired 时校验角色；
 * 3) 农户访问管理端接口默认被 @RoleRequired 拦截。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        // 放行静态资源、跨域预检
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        Integer userId = SessionUtil.getUserId(request.getSession(false));
        if (userId == null) {
            write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ApiResult.fail("登录已过期，请重新登录"));
            return false;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RoleRequired roleRequired = handlerMethod.getMethodAnnotation(RoleRequired.class);
        if (roleRequired == null) {
            roleRequired = handlerMethod.getBeanType().getAnnotation(RoleRequired.class);
        }

        if (roleRequired != null) {
            String role = SessionUtil.getRole(request.getSession(false));
            boolean allowed = role != null && Arrays.asList(roleRequired.value()).contains(role);
            if (!allowed) {
                write(response, HttpServletResponse.SC_FORBIDDEN,
                        ApiResult.fail("当前账号无权访问该功能，请使用管理员账号登录"));
                return false;
            }
        }

        return true;
    }

    private void write(HttpServletResponse response, int status, ApiResult<?> body) throws Exception {
        // HTTP 状态保持 200，让前端统一按 success 字段处理，避免 fetch 直接抛错
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
