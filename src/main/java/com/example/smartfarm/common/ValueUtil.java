package com.example.smartfarm.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 数值转换工具。SQL 聚合结果经 MyBatis 返回时类型不固定
 * （BigDecimal / Double / Long 均有可能），统一在此转换。
 */
public final class ValueUtil {

    private ValueUtil() {
    }

    public static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).doubleValue();
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 转 double，空值返回默认值 */
    public static double toDouble(Object value, double defaultValue) {
        Double result = toDouble(value);
        return result == null ? defaultValue : result;
    }

    public static int toInt(Object value, int defaultValue) {
        Double result = toDouble(value);
        return result == null ? defaultValue : result.intValue();
    }

    /** 保留一位小数，用于展示型指标 */
    public static Double round1(Object value) {
        Double result = toDouble(value);
        if (result == null) {
            return null;
        }
        return BigDecimal.valueOf(result).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** 保留两位小数 */
    public static Double round2(Object value) {
        Double result = toDouble(value);
        if (result == null) {
            return null;
        }
        return BigDecimal.valueOf(result).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
