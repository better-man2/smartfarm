package com.example.smartfarm.service;

import com.example.smartfarm.common.BusinessException;
import com.example.smartfarm.common.Constants;
import com.example.smartfarm.entity.User;
import com.example.smartfarm.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 用户服务：登录认证、农户账号与权限管理（管理端）。
 */
@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    /**
     * 登录校验。被管理员禁用的账号无法登录。
     *
     * @return 校验通过返回用户，否则返回 null
     */
    public User login(String username, String password) {
        if (username == null || password == null) {
            return null;
        }
        User user = userMapper.findByUsername(username.trim());
        if (user == null || !password.equals(user.getPassword())) {
            return null;
        }
        if (user.getStatus() != null && user.getStatus() == Constants.USER_DISABLED) {
            return null;
        }
        return user;
    }

    /** 判断账号是否因被禁用而无法登录，用于给出更准确的提示 */
    public boolean isDisabled(String username) {
        User user = userMapper.findByUsername(username == null ? null : username.trim());
        return user != null && user.getStatus() != null && user.getStatus() == Constants.USER_DISABLED;
    }

    public User getUserById(Integer id) {
        return userMapper.findById(id);
    }

    /** 管理端：账号列表 */
    public List<User> listUsers(String role) {
        return userMapper.findUsers(role);
    }

    /** 管理端：新增或更新账号 */
    public User saveUser(User user, boolean isCreate) {
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            throw new BusinessException("请填写用户名");
        }
        String username = user.getUsername().trim();
        user.setUsername(username);

        if (user.getRole() == null || user.getRole().isEmpty()) {
            user.setRole(Constants.ROLE_FARMER);
        }
        if (!Constants.ROLE_ADMIN.equals(user.getRole()) && !Constants.ROLE_FARMER.equals(user.getRole())) {
            throw new BusinessException("角色只能是 admin 或 farmer");
        }

        if (isCreate) {
            if (userMapper.countByUsername(username) > 0) {
                throw new BusinessException("用户名已存在：" + username);
            }
            if (user.getPassword() == null || user.getPassword().isEmpty()) {
                user.setPassword("123456"); // 新建账号默认初始密码
            }
            user.setStatus(user.getStatus() == null ? Constants.USER_ENABLED : user.getStatus());
            user.setCreatedAt(new Date());
            userMapper.insert(user);
        } else {
            if (user.getId() == null) {
                throw new BusinessException("缺少账号 ID");
            }
            User existing = userMapper.findById(user.getId());
            if (existing == null) {
                throw new BusinessException("账号不存在");
            }
            // 用户名变更时校验唯一性
            if (!username.equals(existing.getUsername()) && userMapper.countByUsername(username) > 0) {
                throw new BusinessException("用户名已被占用：" + username);
            }
            userMapper.update(user);
        }
        return userMapper.findById(user.getId());
    }

    /** 管理端：启用 / 禁用账号（权限控制） */
    public void toggleStatus(Integer userId, Integer status, Integer operatorId) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException("账号不存在");
        }
        if (userId.equals(operatorId)) {
            throw new BusinessException("不能禁用当前登录的账号");
        }
        // 保护最后一个可用管理员，避免把自己锁在系统外
        if (Constants.ROLE_ADMIN.equals(user.getRole())
                && status != null && status == Constants.USER_DISABLED
                && userMapper.countEnabledByRole(Constants.ROLE_ADMIN) <= 1) {
            throw new BusinessException("系统至少需要保留一个启用状态的管理员账号");
        }
        userMapper.updateStatus(userId, status);
    }

    /** 管理端：重置密码 */
    public void resetPassword(Integer userId, String newPassword) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException("账号不存在");
        }
        String password = newPassword == null || newPassword.isEmpty() ? "123456" : newPassword;
        userMapper.updatePassword(userId, password);
    }

    /** 用户自助修改密码 */
    public void changePassword(Integer userId, String oldPassword, String newPassword) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException("账号不存在");
        }
        if (!user.getPassword().equals(oldPassword)) {
            throw new BusinessException("原密码不正确");
        }
        if (newPassword == null || newPassword.trim().length() < 4) {
            throw new BusinessException("新密码长度不能少于 4 位");
        }
        userMapper.updatePassword(userId, newPassword.trim());
    }

    /** 管理端：删除账号，并保护内置管理员 */
    public void deleteUser(Integer userId, Integer operatorId) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException("账号不存在");
        }
        if (userId.equals(operatorId)) {
            throw new BusinessException("不能删除当前登录的账号");
        }
        if (Constants.ROLE_ADMIN.equals(user.getRole())
                && userMapper.countEnabledByRole(Constants.ROLE_ADMIN) <= 1) {
            throw new BusinessException("系统至少需要保留一个启用状态的管理员账号");
        }
        userMapper.delete(userId);
    }

    /** 修改个人联系方式 */
    public void updateProfile(User user) {
        User existing = userMapper.findById(user.getId());
        if (existing == null) {
            throw new BusinessException("账号不存在");
        }
        existing.setPhone(user.getPhone());
        userMapper.updateUserInfo(existing);
    }
}
