package com.example.smartfarm.common;

import com.example.smartfarm.entity.User;

import javax.servlet.http.HttpSession;

/**
 * 会话工具：统一读写登录态，避免各 Controller 重复强转。
 */
public final class SessionUtil {

    private SessionUtil() {
    }

    public static void save(HttpSession session, User user) {
        session.setAttribute(Constants.SESSION_USER_ID, user.getId());
        session.setAttribute(Constants.SESSION_USERNAME, user.getUsername());
        session.setAttribute(Constants.SESSION_ROLE, user.getRole());
    }

    public static Integer getUserId(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(Constants.SESSION_USER_ID);
        return value == null ? null : (Integer) value;
    }

    public static String getUsername(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(Constants.SESSION_USERNAME);
        return value == null ? null : value.toString();
    }

    public static String getRole(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(Constants.SESSION_ROLE);
        return value == null ? null : value.toString();
    }

    public static boolean isAdmin(HttpSession session) {
        return Constants.ROLE_ADMIN.equals(getRole(session));
    }

    /** 取当前登录用户 ID，未登录直接抛业务异常 */
    public static Integer requireUserId(HttpSession session) {
        Integer userId = getUserId(session);
        if (userId == null) {
            throw new BusinessException("登录已过期，请重新登录");
        }
        return userId;
    }
}
