package com.example.smartfarm.mapper;

import com.example.smartfarm.entity.User;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface UserMapper {

    @Select("SELECT * FROM users WHERE username = #{username}")
    User findByUsername(String username);

    @Select("SELECT * FROM users WHERE id = #{id}")
    User findById(Integer id);

    @Insert("INSERT INTO users(username, password, role, phone, status, created_at) " +
            "VALUES(#{username}, #{password}, #{role}, #{phone}, #{status}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    @Update("UPDATE users SET phone = #{phone} WHERE id = #{id}")
    int updateUserInfo(User user);

    @Update("UPDATE users SET password = #{password} WHERE id = #{id}")
    int updatePassword(@Param("id") Integer id, @Param("password") String password);

    @Update("UPDATE users SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Integer id, @Param("status") Integer status);

    @Update("UPDATE users SET username = #{username}, phone = #{phone}, role = #{role} WHERE id = #{id}")
    int update(User user);

    @Delete("DELETE FROM users WHERE id = #{id}")
    int delete(Integer id);

    @Select("SELECT COUNT(*) FROM users WHERE username = #{username}")
    int countByUsername(String username);

    /** 管理端：查询全部账号（可按角色过滤），并带上名下地块数量 */
    @Select("<script>" +
            "SELECT u.*, (SELECT COUNT(*) FROM farmlands f WHERE f.user_id = u.id) AS farmland_count " +
            "FROM users u " +
            "<where>" +
            "  <if test='role != null and role != \"\"'> u.role = #{role} </if>" +
            "</where>" +
            "ORDER BY u.role ASC, u.id ASC" +
            "</script>")
    List<User> findUsers(@Param("role") String role);

    @Select("SELECT COUNT(*) FROM users WHERE role = #{role} AND status = 1")
    int countEnabledByRole(String role);
}
