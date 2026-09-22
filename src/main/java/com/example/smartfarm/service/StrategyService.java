package com.example.smartfarm.service;

import com.example.smartfarm.common.BusinessException;
import com.example.smartfarm.entity.Farmland;
import com.example.smartfarm.entity.IrrigationStrategy;
import com.example.smartfarm.mapper.FarmlandMapper;
import com.example.smartfarm.mapper.IrrigationStrategyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 灌溉策略服务：管理员配置全局策略，也可为单个地块配置专属策略。
 */
@Service
public class StrategyService {

    private static final Logger log = LoggerFactory.getLogger(StrategyService.class);

    /** HH:mm 格式校验 */
    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    @Autowired
    private IrrigationStrategyMapper strategyMapper;
    @Autowired
    private FarmlandMapper farmlandMapper;

    public List<IrrigationStrategy> list(Integer farmlandId, boolean globalOnly) {
        return strategyMapper.findStrategies(farmlandId, globalOnly);
    }

    public IrrigationStrategy getById(Integer id) {
        IrrigationStrategy strategy = strategyMapper.findById(id);
        if (strategy == null) {
            throw new BusinessException("策略不存在");
        }
        return strategy;
    }

    /** 新增或更新策略 */
    public IrrigationStrategy save(IrrigationStrategy strategy, Integer operatorId) {
        validate(strategy);

        boolean isCreate = strategy.getId() == null;
        strategy.setUpdatedAt(new Date());

        if (isCreate) {
            strategy.setCreatedBy(operatorId);
            strategy.setCreatedAt(new Date());
            strategy.setEnabled(strategy.getEnabled() == null ? 1 : strategy.getEnabled());
            strategy.setIsGlobal(strategy.getIsGlobal() == null ? 0 : strategy.getIsGlobal());
            strategyMapper.insert(strategy);
            log.info("新增灌溉策略：{}（{}）", strategy.getName(),
                    strategy.getIsGlobal() == 1 ? "全局" : "地块 " + strategy.getFarmlandId());
        } else {
            IrrigationStrategy existing = strategyMapper.findById(strategy.getId());
            if (existing == null) {
                throw new BusinessException("策略不存在");
            }
            strategyMapper.update(strategy);
            log.info("更新灌溉策略：{}（ID={}）", strategy.getName(), strategy.getId());
        }
        return strategyMapper.findById(strategy.getId());
    }

    public void toggle(Integer id, Integer enabled) {
        IrrigationStrategy strategy = strategyMapper.findById(id);
        if (strategy == null) {
            throw new BusinessException("策略不存在");
        }
        strategyMapper.updateEnabled(id, enabled, new Date());
    }

    public void delete(Integer id) {
        IrrigationStrategy strategy = strategyMapper.findById(id);
        if (strategy == null) {
            throw new BusinessException("策略不存在");
        }
        // 解除地块对该策略的引用，避免删除后地块找不到策略
        for (Farmland farmland : farmlandMapper.findAllWithStats()) {
            if (id.equals(farmland.getStrategyId())) {
                farmlandMapper.updateStrategy(farmland.getId(), null);
            }
        }
        strategyMapper.delete(id);
        log.info("删除灌溉策略：{}（ID={}）", strategy.getName(), id);
    }

    /** 把策略应用到指定地块 */
    public void applyToFarmland(Integer farmlandId, Integer strategyId) {
        if (farmlandMapper.findById(farmlandId) == null) {
            throw new BusinessException("地块不存在");
        }
        if (strategyId != null && strategyMapper.findById(strategyId) == null) {
            throw new BusinessException("策略不存在");
        }
        farmlandMapper.updateStrategy(farmlandId, strategyId);
    }

    /** 全局默认策略（自动灌溉兜底使用） */
    public IrrigationStrategy globalDefault() {
        return strategyMapper.findGlobalDefault();
    }

    // ==================================================================
    // 校验
    // ==================================================================

    private void validate(IrrigationStrategy strategy) {
        if (strategy.getName() == null || strategy.getName().trim().isEmpty()) {
            throw new BusinessException("请填写策略名称");
        }

        double humidityMin = require(strategy.getSoilHumidityMin(), "湿度下限");
        double target = require(strategy.getTargetHumidity(), "目标湿度");

        if (humidityMin < 0 || humidityMin > 100) {
            throw new BusinessException("湿度下限必须在 0~100 之间");
        }
        if (target < 0 || target > 100) {
            throw new BusinessException("目标湿度必须在 0~100 之间");
        }
        if (target <= humidityMin) {
            throw new BusinessException("目标湿度必须大于湿度下限，否则灌溉不会停止");
        }
        if (require(strategy.getIrrigationAmount(), "单次灌溉量") <= 0) {
            throw new BusinessException("单次灌溉量必须大于 0");
        }
        if (strategy.getDurationMinutes() == null || strategy.getDurationMinutes() <= 0) {
            throw new BusinessException("灌溉时长必须大于 0 分钟");
        }
        if (strategy.getTemperatureMax() != null
                && (strategy.getTemperatureMax() < 0 || strategy.getTemperatureMax() > 60)) {
            throw new BusinessException("温度上限建议设置在 0~60℃ 之间");
        }
        if (strategy.getLightIntensityMax() != null && strategy.getLightIntensityMax() < 0) {
            throw new BusinessException("光照上限不能为负数");
        }

        checkTime(strategy.getAllowedStartTime(), "允许灌溉起始时间");
        checkTime(strategy.getAllowedEndTime(), "允许灌溉结束时间");

        // 全局策略不应绑定具体地块
        if (strategy.getIsGlobal() != null && strategy.getIsGlobal() == 1) {
            strategy.setFarmlandId(null);
        } else if (strategy.getFarmlandId() != null
                && farmlandMapper.findById(strategy.getFarmlandId()) == null) {
            throw new BusinessException("指定的地块不存在");
        }
    }

    private void checkTime(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException("请填写" + label);
        }
        if (!TIME_PATTERN.matcher(value.trim()).matches()) {
            throw new BusinessException(label + "格式不正确，应形如 06:00");
        }
    }

    private double require(Double value, String label) {
        if (value == null) {
            throw new BusinessException("请填写" + label);
        }
        return value;
    }
}
