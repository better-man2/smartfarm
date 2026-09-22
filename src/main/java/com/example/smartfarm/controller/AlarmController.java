package com.example.smartfarm.controller;

import com.example.smartfarm.common.ApiResult;
import com.example.smartfarm.common.BusinessException;
import com.example.smartfarm.common.Constants;
import com.example.smartfarm.common.RoleRequired;
import com.example.smartfarm.common.SessionUtil;
import com.example.smartfarm.entity.Alarm;
import com.example.smartfarm.entity.AlarmRule;
import com.example.smartfarm.mapper.AlarmRuleMapper;
import com.example.smartfarm.service.AlarmService;
import com.example.smartfarm.service.StatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警接口（P0 告警通知）。
 *
 * 推送约定：仅 alarmLevel=HIGH 且规则开启推送的告警会出现在 /push/pending 队列中，
 * 前端轮询该接口后弹桌面通知，并调用 /push/ack 确认，避免重复弹窗。
 * MEDIUM/LOW 告警只在列表里留痕，不进入推送队列。
 */
@RestController
@RequestMapping("/api/alarm")
public class AlarmController {

    @Autowired
    private AlarmService alarmService;
    @Autowired
    private AlarmRuleMapper alarmRuleMapper;
    @Autowired
    private StatsService statsService;

    // ==================================================================
    // 告警记录
    // ==================================================================

    /**
     * 农户：我的告警列表
     * GET /api/alarm/my?level=&status=&farmlandId=&limit=
     */
    @GetMapping("/my")
    public ApiResult<List<Alarm>> myAlarms(@RequestParam(required = false) String level,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) Integer farmlandId,
                                           @RequestParam(required = false) Integer limit,
                                           HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        return ApiResult.ok(alarmService.listForFarmer(userId, level, status, farmlandId, limit));
    }

    /**
     * 管理端：全部告警列表
     * GET /api/alarm/list?level=&status=&farmlandId=&alarmType=&limit=
     */
    @GetMapping("/list")
    @RoleRequired
    public ApiResult<List<Alarm>> allAlarms(@RequestParam(required = false) String level,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Integer farmlandId,
                                            @RequestParam(required = false) String alarmType,
                                            @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(alarmService.listAll(level, status, farmlandId, alarmType, limit));
    }

    /**
     * 未读告警数量（铃铛红点）
     * GET /api/alarm/unread
     * 农户只统计自己名下地块，管理员统计全平台，避免红点数量与列表条数不一致。
     */
    @GetMapping("/unread")
    public ApiResult<Map<String, Object>> unread(HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        Integer scopeUserId = SessionUtil.isAdmin(session) ? null : userId;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("unread", alarmService.countUnread(scopeUserId));
        data.put("unreadHigh", alarmService.countUnreadHigh(scopeUserId));
        data.put("unresolved", alarmService.countUnresolved(scopeUserId));
        data.put("unresolvedHigh", alarmService.countUnresolvedHigh(scopeUserId));
        return ApiResult.ok(data);
    }

    // ==================================================================
    // 推送队列（仅高等级）
    // ==================================================================

    /**
     * 待推送告警（仅 HIGH 等级、尚未弹窗确认）
     * GET /api/alarm/push/pending
     */
    @GetMapping("/push/pending")
    public ApiResult<List<Alarm>> pendingPush(HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        // 管理员接收全部地块的高等级告警，农户只接收自己地块的
        List<Alarm> pending = SessionUtil.isAdmin(session)
                ? alarmService.allPendingPush()
                : alarmService.findPendingPush(userId);
        return ApiResult.ok(pending);
    }

    /**
     * 确认推送（前端弹窗后调用，避免重复通知）
     * POST /api/alarm/push/ack
     * 请求体：{ ids: [1,2] }
     */
    @PostMapping("/push/ack")
    public ApiResult<Map<String, Object>> ackPush(@RequestBody Map<String, Object> params, HttpSession session) {
        SessionUtil.requireUserId(session);
        List<Integer> ids = toIntegerList(params.get("ids"));
        int count = alarmService.ackPushed(ids);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", count);
        return ApiResult.ok(data);
    }

    // ==================================================================
    // 告警处理
    // ==================================================================

    /**
     * 处理单条告警（农户确认排查 / 管理员处理故障）
     * POST /api/alarm/{id}/handle
     * 请求体：{ status, remark }
     */
    @PostMapping("/{id}/handle")
    public ApiResult<Void> handle(@PathVariable Integer id,
                                  @RequestBody(required = false) Map<String, Object> params,
                                  HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        String status = params == null || params.get("status") == null
                ? Constants.ALARM_RESOLVED : String.valueOf(params.get("status"));
        String remark = params == null || params.get("remark") == null
                ? null : String.valueOf(params.get("remark"));

        alarmService.handle(id, status, remark, userId);
        return ApiResult.ok("告警已处理", null);
    }

    /**
     * 批量处理告警（管理端）
     * POST /api/alarm/batch-handle
     * 请求体：{ ids: [1,2], status, remark }
     */
    @PostMapping("/batch-handle")
    public ApiResult<Map<String, Object>> batchHandle(@RequestBody Map<String, Object> params, HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        List<Integer> ids = toIntegerList(params.get("ids"));
        String status = params.get("status") == null
                ? Constants.ALARM_RESOLVED : String.valueOf(params.get("status"));
        String remark = params.get("remark") == null ? null : String.valueOf(params.get("remark"));

        int count = alarmService.batchHandle(ids, status, remark, userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", count);
        return ApiResult.ok("已处理 " + count + " 条告警", data);
    }

    /**
     * 告警统计
     * GET /api/alarm/statistics?days=30
     */
    @GetMapping("/statistics")
    public ApiResult<Map<String, Object>> statistics(@RequestParam(required = false, defaultValue = "30") Integer days) {
        return ApiResult.ok(statsService.alarmStatsForAdmin(days));
    }

    // ==================================================================
    // 告警阈值规则（管理端配置）
    // ==================================================================

    /**
     * 阈值规则列表
     * GET /api/alarm/rule/list
     */
    @GetMapping("/rule/list")
    @RoleRequired
    public ApiResult<List<AlarmRule>> ruleList() {
        return ApiResult.ok(alarmRuleMapper.findRules(null, false));
    }

    /**
     * 新增 / 更新阈值规则
     * POST /api/alarm/rule/save
     */
    @PostMapping("/rule/save")
    @RoleRequired
    public ApiResult<AlarmRule> saveRule(@RequestBody AlarmRule rule) {
        validateRule(rule);

        boolean isCreate = rule.getId() == null;
        rule.setUpdatedAt(new Date());
        if (isCreate) {
            rule.setCreatedAt(new Date());
            rule.setEnabled(rule.getEnabled() == null ? 1 : rule.getEnabled());
            rule.setPushEnabled(rule.getPushEnabled() == null ? 0 : rule.getPushEnabled());
            alarmRuleMapper.insert(rule);
        } else {
            if (alarmRuleMapper.findById(rule.getId()) == null) {
                return ApiResult.fail("阈值规则不存在");
            }
            alarmRuleMapper.update(rule);
        }
        return ApiResult.ok(isCreate ? "阈值规则已新增" : "阈值规则已更新",
                alarmRuleMapper.findById(rule.getId()));
    }

    /**
     * 删除阈值规则
     * DELETE /api/alarm/rule/delete/{id}
     */
    @DeleteMapping("/rule/delete/{id}")
    @RoleRequired
    public ApiResult<Void> deleteRule(@PathVariable Integer id) {
        if (alarmRuleMapper.findById(id) == null) {
            return ApiResult.fail("阈值规则不存在");
        }
        alarmRuleMapper.delete(id);
        return ApiResult.ok("阈值规则已删除", null);
    }

    /**
     * 指标字典（前端配置阈值时的下拉项，附带单位与推送说明）
     * GET /api/alarm/rule/metrics
     */
    @GetMapping("/rule/metrics")
    @RoleRequired
    public ApiResult<List<Map<String, Object>>> metrics() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(metric(Constants.METRIC_SOIL_HUMIDITY, "土壤湿度", "%", "LT"));
        list.add(metric(Constants.METRIC_TEMPERATURE, "温度", "℃", "GT"));
        list.add(metric(Constants.METRIC_LIGHT_INTENSITY, "光照强度", "lux", "GT"));
        list.add(metric(Constants.METRIC_DEVICE_OFFLINE_MINUTES, "设备离线时长", "分钟", "GT"));
        return ApiResult.ok(list);
    }

    private Map<String, Object> metric(String value, String label, String unit, String op) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("value", value);
        item.put("label", label);
        item.put("unit", unit);
        item.put("defaultOp", op);
        item.put("pushable", Constants.METRIC_DEVICE_OFFLINE_MINUTES.equals(value)
                || Constants.METRIC_SOIL_HUMIDITY.equals(value)
                || Constants.METRIC_TEMPERATURE.equals(value));
        return item;
    }

    private void validateRule(AlarmRule rule) {
        if (rule.getRuleName() == null || rule.getRuleName().trim().isEmpty()) {
            throw new BusinessException("请填写规则名称");
        }
        if (rule.getMetric() == null || rule.getMetric().isEmpty()) {
            throw new BusinessException("请选择监测指标");
        }
        if (rule.getCompareOp() == null
                || (!Constants.OP_LT.equals(rule.getCompareOp()) && !Constants.OP_GT.equals(rule.getCompareOp()))) {
            throw new BusinessException("比较方式只能是 LT(小于) 或 GT(大于)");
        }
        if (rule.getThreshold() == null) {
            throw new BusinessException("请填写阈值");
        }
        if (rule.getAlarmLevel() == null
                || !(Constants.ALARM_HIGH.equals(rule.getAlarmLevel())
                || Constants.ALARM_MEDIUM.equals(rule.getAlarmLevel())
                || Constants.ALARM_LOW.equals(rule.getAlarmLevel()))) {
            throw new BusinessException("告警等级只能是 HIGH / MEDIUM / LOW");
        }
        // 需求约束：只有高等级才允许推送，这里直接纠正配置，避免出现"低等级却推送"的矛盾配置
        if (rule.getPushEnabled() != null && rule.getPushEnabled() == 1
                && !Constants.ALARM_HIGH.equals(rule.getAlarmLevel())) {
            throw new BusinessException(
                    "只有高等级(HIGH)告警允许推送通知；普通数据异常请设置为不推送，仅在页面记录");
        }
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Integer> toIntegerList(Object value) {
        if (!(value instanceof List)) {
            return null;
        }
        List<Integer> result = new ArrayList<>();
        for (Object item : (List<Object>) value) {
            Integer id = toInteger(item);
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }
}
