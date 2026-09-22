package com.example.smartfarm.controller;

import com.example.smartfarm.common.ApiResult;
import com.example.smartfarm.common.BusinessException;
import com.example.smartfarm.common.RoleRequired;
import com.example.smartfarm.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统计报表导出接口（管理端）。
 * 导出 CSV，含 UTF-8 BOM，Excel 双击打开不乱码。
 */
@RestController
@RequestMapping("/api/report")
@RoleRequired
public class ReportController {

    @Autowired
    private ReportService reportService;

    /**
     * 导出报表
     * GET /api/report/export?type=summary&days=30&farmlandId=
     *
     * type 取值：alarm 告警记录 / irrigation 灌溉记录 / sensor 监测数据 / summary 地块汇总统计
     */
    @GetMapping("/export")
    public ResponseEntity<Resource> export(@RequestParam(required = false, defaultValue = "summary") String type,
                                           @RequestParam(required = false, defaultValue = "30") Integer days,
                                           @RequestParam(required = false) Integer farmlandId) {

        byte[] content = reportService.export(type, days, farmlandId);
        String fileName = reportService.buildFileName(type, days);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8));
        headers.setContentDispositionFormData("attachment", encodeFileName(fileName));
        headers.setContentLength(content.length);

        return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(content));
    }

    /**
     * 报表类型字典，供前端导出下拉框使用
     * GET /api/report/types
     */
    @GetMapping("/types")
    public ApiResult<Map<String, String>> types() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("summary", "地块汇总统计");
        types.put("alarm", "告警记录");
        types.put("irrigation", "灌溉记录");
        types.put("sensor", "监测数据");
        return ApiResult.ok(types);
    }

    /** 中文文件名需要 URL 编码后再放入 Content-Disposition，否则浏览器会乱码 */
    private String encodeFileName(String fileName) {
        try {
            return URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new BusinessException("文件名编码失败", e);
        }
    }
}
