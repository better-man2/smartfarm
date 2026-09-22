package com.example.smartfarm.service;

import com.example.smartfarm.common.BusinessException;
import com.example.smartfarm.entity.Device;
import com.example.smartfarm.entity.Farmland;
import com.example.smartfarm.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 地块服务。同时承担农户数据隔离职责：
 * 农户只能访问自己名下的地块，越权访问统一在此拦截。
 */
@Service
public class FarmlandService {

    private static final Logger log = LoggerFactory.getLogger(FarmlandService.class);

    @Autowired
    private FarmlandMapper farmlandMapper;
    @Autowired
    private DeviceMapper deviceMapper;
    @Autowired
    private SensorDataMapper sensorDataMapper;
    @Autowired
    private IrrigationRecordMapper recordMapper;
    @Autowired
    private IrrigationDecisionMapper decisionMapper;
    @Autowired
    private IrrigationStrategyMapper strategyMapper;

    public List<Farmland> getFarmlandsByUserId(Integer userId) {
        return farmlandMapper.findByUserId(userId);
    }

    /** 管理端：全部地块汇总（含设备数、在线设备数、待处理告警数） */
    public List<Farmland> getAllWithStats() {
        return farmlandMapper.findAllWithStats();
    }

    public Farmland getFarmlandById(Integer id) {
        Farmland farmland = farmlandMapper.findById(id);
        if (farmland == null) {
            throw new BusinessException("地块不存在");
        }
        return farmland;
    }

    /**
     * 取地块并校验归属。农户访问非本人地块时抛出异常（数据隔离）。
     */
    public Farmland getOwnedFarmland(Integer farmlandId, Integer userId, boolean isAdmin) {
        Farmland farmland = getFarmlandById(farmlandId);
        if (!isAdmin && !userId.equals(farmland.getUserId())) {
            throw new BusinessException("无权访问该地块的数据");
        }
        return farmland;
    }

    public Farmland addFarmland(Farmland farmland, Integer userId) {
        validate(farmland);
        farmland.setUserId(userId);
        farmland.setCreatedAt(new Date());
        if (farmland.getAutoIrrigation() == null) {
            farmland.setAutoIrrigation(0);
        }
        farmlandMapper.insert(farmland);
        log.info("新增地块：{}（农户 {}）", farmland.getName(), userId);
        return farmland;
    }

    public Farmland updateFarmland(Farmland farmland, Integer userId, boolean isAdmin) {
        getOwnedFarmland(farmland.getId(), userId, isAdmin);
        validate(farmland);
        farmlandMapper.update(farmland);
        return farmlandMapper.findById(farmland.getId());
    }

    /**
     * 删除地块，同时清理其关联数据，避免产生悬空引用：
     * 设备解绑、监测数据、灌溉记录、决策记录一并删除。
     */
    public void deleteFarmland(Integer farmlandId, Integer userId, boolean isAdmin) {
        Farmland farmland = getOwnedFarmland(farmlandId, userId, isAdmin);

        for (Device device : deviceMapper.findByFarmlandId(farmlandId)) {
            // 地块被删除后设备回到"未绑定"状态，并清空心跳时间
            deviceMapper.bindFarmland(device.getId(), null,
                    com.example.smartfarm.common.Constants.DEVICE_UNBOUND, null, new Date());
        }
        int sensorRows = sensorDataMapper.deleteByFarmland(farmlandId);
        int recordRows = recordMapper.deleteByFarmland(farmlandId);
        int decisionRows = decisionMapper.deleteByFarmland(farmlandId);
        farmlandMapper.delete(farmlandId);

        log.info("删除地块「{}」(ID={})，同时清理监测数据 {} 条、灌溉记录 {} 条、决策记录 {} 条",
                farmland.getName(), farmlandId, sensorRows, recordRows, decisionRows);
    }

    /**
     * 切换手动 / 自动灌溉模式。
     * 开启自动模式前会校验系统存在可用策略，否则自动灌溉永远不会触发，
     * 提前拦截并给出明确提示，而不是让农户开启后毫无反应。
     */
    public void setAutoIrrigation(Integer farmlandId, boolean enabled, Integer userId, boolean isAdmin) {
        Farmland farmland = getOwnedFarmland(farmlandId, userId, isAdmin);

        if (enabled) {
            boolean hasStrategy = false;
            if (farmland.getStrategyId() != null
                    && strategyMapper.findById(farmland.getStrategyId()) != null) {
                hasStrategy = true;
            } else if (strategyMapper.findGlobalDefault() != null) {
                hasStrategy = true;
            }
            if (!hasStrategy) {
                throw new BusinessException("系统尚未配置可用的灌溉策略，请联系管理员在后台配置后再开启自动模式");
            }
        }

        farmlandMapper.updateAutoIrrigation(farmlandId, enabled ? 1 : 0);
        log.info("地块「{}」灌溉模式切换为：{}", farmland.getName(), enabled ? "自动" : "手动");
    }

    public int countAll() {
        return farmlandMapper.countAll();
    }

    private void validate(Farmland farmland) {
        if (farmland.getName() == null || farmland.getName().trim().isEmpty()) {
            throw new BusinessException("请填写地块名称");
        }
        if (farmland.getArea() != null && farmland.getArea() < 0) {
            throw new BusinessException("地块面积不能为负数");
        }
        farmland.setName(farmland.getName().trim());
    }
}
