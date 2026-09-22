package com.example.smartfarm.service;

import com.example.smartfarm.common.BusinessException;
import com.example.smartfarm.entity.FertilizerRecipe;
import com.example.smartfarm.mapper.FarmlandMapper;
import com.example.smartfarm.mapper.FertilizerRecipeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 水肥配比服务（P2）。
 * 灌溉时可携带配比方案，配合水肥一体机实现按比例施肥。
 */
@Service
public class FertilizerService {

    private static final Logger log = LoggerFactory.getLogger(FertilizerService.class);

    @Autowired
    private FertilizerRecipeMapper recipeMapper;
    @Autowired
    private FarmlandMapper farmlandMapper;

    /**
     * 方案列表。
     *
     * @param farmlandId 传入地块时返回"该地块专属方案 + 通用方案"
     */
    public List<FertilizerRecipe> list(Integer farmlandId, boolean onlyEnabled) {
        return recipeMapper.findRecipes(farmlandId, onlyEnabled);
    }

    public FertilizerRecipe getById(Integer id) {
        FertilizerRecipe recipe = recipeMapper.findById(id);
        if (recipe == null) {
            throw new BusinessException("水肥配比方案不存在");
        }
        return recipe;
    }

    public FertilizerRecipe save(FertilizerRecipe recipe) {
        validate(recipe);

        boolean isCreate = recipe.getId() == null;
        recipe.setUpdatedAt(new Date());

        if (isCreate) {
            recipe.setEnabled(recipe.getEnabled() == null ? 1 : recipe.getEnabled());
            recipe.setCreatedAt(new Date());
            recipeMapper.insert(recipe);
            log.info("新增水肥配比方案：{}（N:P:K = {}:{}:{}）", recipe.getName(),
                    recipe.getNRatio(), recipe.getPRatio(), recipe.getKRatio());
        } else {
            if (recipeMapper.findById(recipe.getId()) == null) {
                throw new BusinessException("水肥配比方案不存在");
            }
            recipeMapper.update(recipe);
        }
        return recipeMapper.findById(recipe.getId());
    }

    public void delete(Integer id) {
        if (recipeMapper.findById(id) == null) {
            throw new BusinessException("水肥配比方案不存在");
        }
        recipeMapper.delete(id);
    }

    private void validate(FertilizerRecipe recipe) {
        if (recipe.getName() == null || recipe.getName().trim().isEmpty()) {
            throw new BusinessException("请填写方案名称");
        }
        if (recipe.getNRatio() == null || recipe.getPRatio() == null || recipe.getKRatio() == null) {
            throw new BusinessException("请填写完整的 N、P、K 配比");
        }
        if (recipe.getNRatio() < 0 || recipe.getPRatio() < 0 || recipe.getKRatio() < 0) {
            throw new BusinessException("N、P、K 配比不能为负数");
        }
        if (recipe.getNRatio() == 0 && recipe.getPRatio() == 0 && recipe.getKRatio() == 0) {
            throw new BusinessException("N、P、K 配比不能同时为 0");
        }
        if (recipe.getPhTarget() != null && (recipe.getPhTarget() < 0 || recipe.getPhTarget() > 14)) {
            throw new BusinessException("目标 pH 必须在 0~14 之间");
        }
        if (recipe.getEcTarget() != null && recipe.getEcTarget() < 0) {
            throw new BusinessException("目标电导率不能为负数");
        }
        if (recipe.getConcentration() != null && recipe.getConcentration() < 0) {
            throw new BusinessException("母液浓度不能为负数");
        }
        if (recipe.getFarmlandId() != null && farmlandMapper.findById(recipe.getFarmlandId()) == null) {
            throw new BusinessException("指定的地块不存在");
        }
    }
}
