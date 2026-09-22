package com.example.smartfarm.mapper;

import com.example.smartfarm.entity.FertilizerRecipe;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface FertilizerRecipeMapper {

    /** 方案列表：farmlandId 为空查全部；否则返回该地块专属方案 + 通用方案 */
    @Select("<script>" +
            "SELECT r.*, f.name AS farmland_name FROM fertilizer_recipes r " +
            "LEFT JOIN farmlands f ON r.farmland_id = f.id " +
            "<where>" +
            "  <if test='farmlandId != null'> AND (r.farmland_id = #{farmlandId} OR r.farmland_id IS NULL) </if>" +
            "  <if test='onlyEnabled'> AND r.enabled = 1 </if>" +
            "</where>" +
            "ORDER BY r.id ASC" +
            "</script>")
    List<FertilizerRecipe> findRecipes(@Param("farmlandId") Integer farmlandId,
                                       @Param("onlyEnabled") boolean onlyEnabled);

    @Select("SELECT * FROM fertilizer_recipes WHERE id = #{id}")
    FertilizerRecipe findById(Integer id);

    @Insert("INSERT INTO fertilizer_recipes(name, farmland_id, crop_type, n_ratio, p_ratio, k_ratio, " +
            "ec_target, ph_target, concentration, enabled, created_at, updated_at) " +
            "VALUES(#{name}, #{farmlandId}, #{cropType}, #{nRatio}, #{pRatio}, #{kRatio}, " +
            "#{ecTarget}, #{phTarget}, #{concentration}, #{enabled}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(FertilizerRecipe recipe);

    @Update("UPDATE fertilizer_recipes SET name = #{name}, farmland_id = #{farmlandId}, crop_type = #{cropType}, " +
            "n_ratio = #{nRatio}, p_ratio = #{pRatio}, k_ratio = #{kRatio}, ec_target = #{ecTarget}, " +
            "ph_target = #{phTarget}, concentration = #{concentration}, enabled = #{enabled}, " +
            "updated_at = #{updatedAt} WHERE id = #{id}")
    int update(FertilizerRecipe recipe);

    @Delete("DELETE FROM fertilizer_recipes WHERE id = #{id}")
    int delete(Integer id);

    @Select("SELECT COUNT(*) FROM fertilizer_recipes")
    int countAll();
}
