package com.example.smartfarm.controller;

import com.example.smartfarm.common.ApiResult;
import com.example.smartfarm.common.RoleRequired;
import com.example.smartfarm.common.SessionUtil;
import com.example.smartfarm.entity.Device;
import com.example.smartfarm.service.DeviceService;
import com.example.smartfarm.service.FarmlandService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备管理接口。
 *
 * 权限划分：
 * - 农户：查看自己地块上的设备与状态，用于故障排查；
 * - 管理员：设备新增、绑定、批量管理、状态监控（@RoleRequired）。
 */
@RestController
@RequestMapping("/api/device")
public class DeviceController {

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private FarmlandService farmlandService;

    // ==================================================================
    // 农户端
    // ==================================================================

    /**
     * 我的设备（含心跳与离线时长，用于故障排查）
     * GET /api/device/my
     */
    @GetMapping("/my")
    public ApiResult<List<Device>> myDevices(HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        return ApiResult.ok(deviceService.listByUser(userId));
    }

    /**
     * 某地块的设备
     * GET /api/device/farmland/{farmlandId}
     */
    @GetMapping("/farmland/{farmlandId}")
    public ApiResult<List<Device>> devicesOfFarmland(@PathVariable Integer farmlandId, HttpSession session) {
        Integer userId = SessionUtil.requireUserId(session);
        farmlandService.getOwnedFarmland(farmlandId, userId, SessionUtil.isAdmin(session));
        return ApiResult.ok(deviceService.listByFarmland(farmlandId));
    }

    // ==================================================================
    // 管理端
    // ==================================================================

    /**
     * 设备列表（支持类型/状态/地块/关键字过滤）
     * GET /api/device/list?deviceType=&status=&farmlandId=&keyword=
     */
    @GetMapping("/list")
    @RoleRequired
    public ApiResult<List<Device>> listDevices(@RequestParam(required = false) String deviceType,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) Integer farmlandId,
                                               @RequestParam(required = false) String keyword) {
        return ApiResult.ok(deviceService.listDevices(deviceType, status, farmlandId, keyword));
    }

    /**
     * 未绑定设备
     * GET /api/device/unbound
     */
    @GetMapping("/unbound")
    @RoleRequired
    public ApiResult<List<Device>> unboundDevices() {
        return ApiResult.ok(deviceService.listUnbound());
    }

    /**
     * 设备详情
     * GET /api/device/{id}
     */
    @GetMapping("/{id}")
    public ApiResult<Device> getDevice(@PathVariable Integer id, HttpSession session) {
        SessionUtil.requireUserId(session);
        return ApiResult.ok(deviceService.getById(id));
    }

    /**
     * 新增设备
     * POST /api/device/add
     */
    @PostMapping("/add")
    @RoleRequired
    public ApiResult<Device> addDevice(@RequestBody Device device) {
        return ApiResult.ok("设备新增成功", deviceService.addDevice(device));
    }

    /**
     * 修改设备信息
     * POST /api/device/update
     */
    @PostMapping("/update")
    @RoleRequired
    public ApiResult<Device> updateDevice(@RequestBody Device device) {
        return ApiResult.ok("设备信息已更新", deviceService.updateDevice(device));
    }

    /**
     * 删除设备
     * DELETE /api/device/delete/{id}
     */
    @DeleteMapping("/delete/{id}")
    @RoleRequired
    public ApiResult<Void> deleteDevice(@PathVariable Integer id) {
        deviceService.deleteDevice(id);
        return ApiResult.ok("设备已删除", null);
    }

    /**
     * 绑定 / 解绑设备到地块（farmlandId 不传表示解绑）
     * POST /api/device/{id}/bind?farmlandId=1
     */
    @PostMapping("/{id}/bind")
    @RoleRequired
    public ApiResult<Void> bind(@PathVariable Integer id,
                                @RequestParam(required = false) Integer farmlandId,
                                HttpSession session) {
        Integer operatorId = SessionUtil.requireUserId(session);
        deviceService.bind(id, farmlandId, operatorId);
        return ApiResult.ok(farmlandId == null ? "设备已解绑" : "设备绑定成功", null);
    }

    /**
     * 批量绑定（P2）：多台设备一次绑定到同一地块
     * POST /api/device/batch/bind
     * 请求体：{ deviceIds: [1,2,3], farmlandId: 1 }
     */
    @PostMapping("/batch/bind")
    @RoleRequired
    public ApiResult<Map<String, Object>> batchBind(@RequestBody Map<String, Object> params) {
        List<Integer> deviceIds = toIntegerList(params.get("deviceIds"));
        Integer farmlandId = toInteger(params.get("farmlandId"));

        int count = deviceService.batchBind(deviceIds, farmlandId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", count);
        return ApiResult.ok("已批量绑定 " + count + " 台设备", data);
    }

    /**
     * 批量解绑（P2）
     * POST /api/device/batch/unbind
     */
    @PostMapping("/batch/unbind")
    @RoleRequired
    public ApiResult<Map<String, Object>> batchUnbind(@RequestBody Map<String, Object> params) {
        List<Integer> deviceIds = toIntegerList(params.get("deviceIds"));
        int count = deviceService.batchUnbind(deviceIds);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", count);
        return ApiResult.ok("已批量解绑 " + count + " 台设备", data);
    }

    /**
     * 批量修改设备状态（P2），例如批量转入维护、批量恢复在线
     * POST /api/device/batch/status
     * 请求体：{ deviceIds: [1,2], status: "MAINTENANCE" }
     */
    @PostMapping("/batch/status")
    @RoleRequired
    public ApiResult<Map<String, Object>> batchUpdateStatus(@RequestBody Map<String, Object> params) {
        List<Integer> deviceIds = toIntegerList(params.get("deviceIds"));
        String status = params.get("status") == null ? null : String.valueOf(params.get("status"));

        int count = deviceService.batchUpdateStatus(deviceIds, status);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", count);
        data.put("status", status);
        return ApiResult.ok("已更新 " + count + " 台设备的状态", data);
    }

    /**
     * 设备心跳上报
     * POST /api/device/{id}/heartbeat
     */
    @PostMapping("/{id}/heartbeat")
    public ApiResult<Void> heartbeat(@PathVariable Integer id) {
        deviceService.heartbeat(id);
        return ApiResult.ok("心跳已更新", null);
    }

    /**
     * 设备状态统计（在线率、异常数量）
     * GET /api/device/statistics
     */
    @GetMapping("/statistics")
    @RoleRequired
    public ApiResult<Map<String, Object>> statistics() {
        return ApiResult.ok(deviceService.statusStatistics());
    }

    /**
     * 设备类型与状态字典 + 地块下拉数据（前端表单使用）
     * GET /api/device/options
     */
    @GetMapping("/options")
    @RoleRequired
    public ApiResult<Map<String, Object>> options() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("types", deviceService.typeOptions());
        data.put("statuses", deviceService.statusOptions());
        data.put("farmlands", deviceService.farmlandOptions());
        return ApiResult.ok(data);
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
        List<Integer> result = new java.util.ArrayList<>();
        for (Object item : (List<Object>) value) {
            Integer id = toInteger(item);
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }
}
