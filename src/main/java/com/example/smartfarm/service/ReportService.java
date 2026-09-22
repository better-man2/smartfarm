package com.example.smartfarm.service;

import com.example.smartfarm.common.BusinessException;
import com.example.smartfarm.common.Constants;
import com.example.smartfarm.common.ValueUtil;
import com.example.smartfarm.entity.Alarm;
import com.example.smartfarm.entity.Farmland;
import com.example.smartfarm.entity.IrrigationRecord;
import com.example.smartfarm.entity.SensorData;
import com.example.smartfarm.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 统计报表导出服务（管理端）。
 *
 * 导出为 CSV：带 UTF-8 BOM，Excel 直接双击不会乱码；
 * 字段中包含逗号或引号时按 RFC 4180 规则转义。
 */
@Service
public class ReportService {

    /** UTF-8 BOM，保证 Excel 正确识别编码 */
    private static final String UTF8_BOM = "\uFEFF";

    private static final String TYPE_ALARM = "alarm";
    private static final String TYPE_IRRIGATION = "irrigation";
    private static final String TYPE_SENSOR = "sensor";
    private static final String TYPE_SUMMARY = "summary";

    @Autowired
    private AlarmMapper alarmMapper;
    @Autowired
    private IrrigationRecordMapper recordMapper;
    @Autowired
    private SensorDataMapper sensorDataMapper;
    @Autowired
    private FarmlandMapper farmlandMapper;
    @Autowired
    private StatsService statsService;

    /**
     * 生成报表字节内容。
     *
     * @param type       alarm 告警记录 / irrigation 灌溉记录 / sensor 监测数据 / summary 地块汇总统计
     * @param days       统计天数
     * @param farmlandId 限定地块，可为空
     */
    public byte[] export(String type, Integer days, Integer farmlandId) {
        int d = days == null || days <= 0 ? 30 : days;
        String normalized = type == null ? TYPE_SUMMARY : type.trim().toLowerCase();

        switch (normalized) {
            case TYPE_ALARM:
                return buildCsv(alarmHeaders(), alarmRows(d, farmlandId));
            case TYPE_IRRIGATION:
                return buildCsv(irrigationHeaders(), irrigationRows(d, farmlandId));
            case TYPE_SENSOR:
                return buildCsv(sensorHeaders(), sensorRows(d, farmlandId));
            case TYPE_SUMMARY:
                return buildCsv(summaryHeaders(d), summaryRows(d));
            default:
                throw new BusinessException("不支持的报表类型：" + type
                        + "（可选 alarm / irrigation / sensor / summary）");
        }
    }

    /** 报表文件名（含时间戳，避免重复导出互相覆盖） */
    public String buildFileName(String type, int days) {
        String normalized = type == null ? TYPE_SUMMARY : type.trim().toLowerCase();
        String label;
        switch (normalized) {
            case TYPE_ALARM:
                label = "告警记录";
                break;
            case TYPE_IRRIGATION:
                label = "灌溉记录";
                break;
            case TYPE_SENSOR:
                label = "监测数据";
                break;
            default:
                label = "地块汇总统计";
                break;
        }
        Calendar cal = Calendar.getInstance();
        String stamp = String.format("%04d%02d%02d%02d%02d%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND));
        return label + "_近" + days + "天_" + stamp + ".csv";
    }

    // ==================================================================
    // 各类型报表的列定义与数据行
    // ==================================================================

    private List<String> alarmHeaders() {
        List<String> headers = new ArrayList<>();
        headers.add("告警ID");
        headers.add("告警时间");
        headers.add("地块");
        headers.add("设备");
        headers.add("告警类型");
        headers.add("告警等级");
        headers.add("标题");
        headers.add("详细描述");
        headers.add("指标值");
        headers.add("是否推送通知");
        headers.add("处理状态");
        headers.add("处理人");
        headers.add("处理时间");
        headers.add("处理备注");
        return headers;
    }

    private List<List<String>> alarmRows(int days, Integer farmlandId) {
        List<Alarm> alarms = alarmMapper.findAll(null, null, farmlandId, null, 5000);
        Date since = daysAgo(days);
        List<List<String>> rows = new ArrayList<>();

        for (Alarm alarm : alarms) {
            if (alarm.getCreatedAt() != null && alarm.getCreatedAt().before(since)) {
                continue;
            }
            List<String> row = new ArrayList<>();
            row.add(str(alarm.getId()));
            row.add(formatDateTime(alarm.getCreatedAt()));
            row.add(nullToDash(alarm.getFarmlandName()));
            row.add(nullToDash(alarm.getDeviceName()));
            row.add(alarmTypeText(alarm.getAlarmType()));
            row.add(alarmLevelText(alarm.getAlarmLevel()));
            row.add(nullToDash(alarm.getTitle()));
            row.add(nullToDash(alarm.getContent()));
            row.add(alarm.getMetricValue() == null ? "-" : str(alarm.getMetricValue()));
            row.add(alarm.getPushStatus() != null && alarm.getPushStatus() == 1 ? "已推送" : "仅记录");
            row.add(alarmStatusText(alarm.getStatus()));
            row.add(nullToDash(alarm.getHandledByName()));
            row.add(alarm.getHandledTime() == null ? "-" : formatDateTime(alarm.getHandledTime()));
            row.add(nullToDash(alarm.getHandleRemark()));
            rows.add(row);
        }
        return rows;
    }

    private List<String> irrigationHeaders() {
        List<String> headers = new ArrayList<>();
        headers.add("记录ID");
        headers.add("时间");
        headers.add("地块");
        headers.add("触发方式");
        headers.add("灌溉水量(m³)");
        headers.add("时长(分钟)");
        headers.add("水肥方案");
        headers.add("执行状态");
        headers.add("操作人");
        headers.add("备注");
        return headers;
    }

    private List<List<String>> irrigationRows(int days, Integer farmlandId) {
        List<IrrigationRecord> records = recordMapper.findAll(farmlandId, null, null, 5000);
        Date since = daysAgo(days);
        List<List<String>> rows = new ArrayList<>();

        for (IrrigationRecord record : records) {
            if (record.getCreatedAt() != null && record.getCreatedAt().before(since)) {
                continue;
            }
            List<String> row = new ArrayList<>();
            row.add(str(record.getId()));
            row.add(formatDateTime(record.getCreatedAt()));
            row.add(nullToDash(record.getFarmlandName()));
            row.add(Constants.TRIGGER_AUTO.equals(record.getTriggerType()) ? "自动" : "手动");
            row.add(record.getWaterAmount() == null ? "0" : str(record.getWaterAmount()));
            row.add(record.getDurationMinutes() == null ? "-" : str(record.getDurationMinutes()));
            row.add(nullToDash(record.getFertilizerName()));
            row.add(irrigationStatusText(record.getStatus()));
            row.add(nullToDash(record.getOperatorName()));
            row.add(nullToDash(record.getRemark()));
            rows.add(row);
        }
        return rows;
    }

    private List<String> sensorHeaders() {
        List<String> headers = new ArrayList<>();
        headers.add("数据ID");
        headers.add("采集时间");
        headers.add("地块");
        headers.add("土壤湿度(%)");
        headers.add("温度(℃)");
        headers.add("光照强度(lux)");
        return headers;
    }

    private List<List<String>> sensorRows(int days, Integer farmlandId) {
        Date since = daysAgo(days);
        List<Integer> farmlandIds = new ArrayList<>();
        if (farmlandId != null) {
            farmlandIds.add(farmlandId);
        } else {
            for (Farmland farmland : farmlandMapper.findAllWithStats()) {
                farmlandIds.add(farmland.getId());
            }
        }

        List<List<String>> rows = new ArrayList<>();
        for (Integer id : farmlandIds) {
            Farmland farmland = farmlandMapper.findById(id);
            String farmlandName = farmland == null ? ("地块" + id) : farmland.getName();
            for (SensorData data : sensorDataMapper.getDataSince(id, since)) {
                List<String> row = new ArrayList<>();
                row.add(str(data.getId()));
                row.add(formatDateTime(data.getCollectTime()));
                row.add(farmlandName);
                row.add(data.getSoilHumidity() == null ? "-" : str(data.getSoilHumidity()));
                row.add(data.getTemperature() == null ? "-" : str(data.getTemperature()));
                row.add(data.getLightIntensity() == null ? "-" : str(data.getLightIntensity()));
                rows.add(row);
            }
        }
        return rows;
    }

    private List<String> summaryHeaders(int days) {
        List<String> headers = new ArrayList<>();
        headers.add("地块ID");
        headers.add("地块名称");
        headers.add("所属农户");
        headers.add("位置");
        headers.add("面积(亩)");
        headers.add("作物类型");
        headers.add("灌溉模式");
        headers.add("设备总数");
        headers.add("在线设备");
        headers.add("待处理告警");
        headers.add("当前土壤湿度(%)");
        headers.add("当前温度(℃)");
        headers.add("近" + days + "天灌溉次数");
        headers.add("近" + days + "天用水量(m³)");
        headers.add("近" + days + "天估算节水率(%)");
        return headers;
    }

    private List<List<String>> summaryRows(int days) {
        List<Farmland> farmlands = farmlandMapper.findAllWithStats();
        List<List<String>> rows = new ArrayList<>();

        for (Farmland farmland : farmlands) {
            Map<String, Object> summary = statsService.farmlandSummary(farmland);
            Map<String, Object> recordStats = statsService.recordStats(farmland.getId(), null, days);

            List<String> row = new ArrayList<>();
            row.add(str(farmland.getId()));
            row.add(nullToDash(farmland.getName()));
            row.add(nullToDash(farmland.getUsername()));
            row.add(nullToDash(farmland.getLocation()));
            row.add(farmland.getArea() == null ? "-" : str(farmland.getArea()));
            row.add(nullToDash(farmland.getCropType()));
            row.add(farmland.getAutoIrrigation() != null && farmland.getAutoIrrigation() == 1 ? "自动" : "手动");
            row.add(str(summary.get("deviceCount")));
            row.add(str(summary.get("onlineDeviceCount")));
            row.add(str(summary.get("pendingAlarmCount")));
            row.add(valueOrDash(summary.get("soilHumidity")));
            row.add(valueOrDash(summary.get("temperature")));
            row.add(str(recordStats.get("successCount")));
            row.add(valueOrDash(recordStats.get("totalWater")));
            row.add(valueOrDash(recordStats.get("savingRate")));
            rows.add(row);
        }
        return rows;
    }

    // ==================================================================
    // CSV 组装与格式化
    // ==================================================================

    private byte[] buildCsv(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers);
        for (List<String> row : rows) {
            appendRow(sb, row);
        }
        return (UTF8_BOM + sb).getBytes(StandardCharsets.UTF_8);
    }

    private void appendRow(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeCsv(cells.get(i)));
        }
        sb.append("\r\n");
    }

    /** RFC 4180：含逗号、引号、换行的字段用双引号包裹，内部引号翻倍 */
    private String escapeCsv(String value) {
        String text = value == null ? "" : value;
        boolean needQuote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        if (!needQuote) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private String str(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double) {
            return String.valueOf(ValueUtil.round1(value));
        }
        return String.valueOf(value);
    }

    private String valueOrDash(Object value) {
        if (value == null) {
            return "-";
        }
        Double d = ValueUtil.toDouble(value);
        return d == null ? String.valueOf(value) : String.valueOf(ValueUtil.round1(d));
    }

    private String nullToDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private String formatDateTime(Date date) {
        if (date == null) {
            return "-";
        }
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        return String.format("%04d-%02d-%02d %02d:%02d:%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND));
    }

    private String alarmLevelText(String level) {
        if (Constants.ALARM_HIGH.equals(level)) {
            return "高";
        }
        if (Constants.ALARM_MEDIUM.equals(level)) {
            return "中";
        }
        if (Constants.ALARM_LOW.equals(level)) {
            return "低";
        }
        return nullToDash(level);
    }

    private String alarmTypeText(String type) {
        if (type == null) {
            return "-";
        }
        switch (type) {
            case Constants.ALARM_TYPE_DEVICE_OFFLINE:
                return "设备离线";
            case Constants.ALARM_TYPE_SENSOR_ABNORMAL:
                return "传感器异常";
            case Constants.ALARM_TYPE_SOIL_DRY:
                return "土壤干旱";
            case Constants.ALARM_TYPE_TEMP_HIGH:
                return "温度过高";
            case Constants.ALARM_TYPE_LIGHT_ABNORMAL:
                return "光照异常";
            case Constants.ALARM_TYPE_IRRIGATION_FAIL:
                return "灌溉失败";
            default:
                return type;
        }
    }

    private String alarmStatusText(String status) {
        if (Constants.ALARM_PENDING.equals(status)) {
            return "待处理";
        }
        if (Constants.ALARM_PROCESSING.equals(status)) {
            return "处理中";
        }
        if (Constants.ALARM_RESOLVED.equals(status)) {
            return "已解决";
        }
        return nullToDash(status);
    }

    private String irrigationStatusText(String status) {
        if (Constants.IRRIGATION_SUCCESS.equals(status)) {
            return "已完成";
        }
        if (Constants.IRRIGATION_RUNNING.equals(status)) {
            return "执行中";
        }
        if (Constants.IRRIGATION_FAILED.equals(status)) {
            return "执行失败";
        }
        if (Constants.IRRIGATION_CANCELLED.equals(status)) {
            return "已取消";
        }
        if (Constants.IRRIGATION_PENDING.equals(status)) {
            return "待执行";
        }
        return nullToDash(status);
    }

    private Date daysAgo(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -days);
        return cal.getTime();
    }
}
