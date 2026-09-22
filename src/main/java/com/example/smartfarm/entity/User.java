package com.example.smartfarm.entity;

import lombok.Data;

import java.util.Date;

@Data
public class User {
    private Integer id;
    private String username;
    private String password;
    private String role;  // admin 管理员 / farmer 农户
    private String phone;
    private Integer status;  // 1 启用 / 0 禁用
    private Date createdAt;

    /** 农户名下地块数量（管理端账号列表展示用） */
    private Integer farmlandCount;
}
