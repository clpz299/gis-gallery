package com.example.gisgallery.gridtile.api.controller;

import com.example.gisgallery.common.response.RestResult;
import com.example.gisgallery.gridtile.api.dto.RegionDownloadRequest;
import com.example.gisgallery.gridtile.api.dto.RegionDownloadResult;
import com.example.gisgallery.gridtile.application.service.TileDownloadService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * 瓦片下载控制器
 *
 * @author clpz299
 */
@RestController
@RequestMapping("/api/gridtile")
public class RegionController {

    @Autowired
    TileDownloadService tileDownloadService;

    /**
     * 获取行政区划列表
     *
     * @return 完整的行政区划树（来自 all_region.json）
     */
    @GetMapping("/regions")
    public RestResult<JsonNode> getRegions() throws IOException {
        ClassPathResource resource = new ClassPathResource("static/basic-data/all_region.json");
        ObjectMapper objectMapper = new JsonMapper();
        JsonNode jsonNode = objectMapper.readTree(resource.getInputStream());
        return RestResult.success(jsonNode);
    }

    /**
     * 提交瓦片下载任务
     *
     * @param request 下载请求
     * @return 任务信息
     */
    @PostMapping("/download")
    public RestResult<RegionDownloadResult> downloadTiles(@RequestBody RegionDownloadRequest request) {
        // 简单校验
        if (request.getAdcode() == null) {
            return RestResult.failed("行政区划代码不能为空");
        }
        if (request.getZoomLevels() == null || request.getZoomLevels().isEmpty()) {
            return RestResult.failed("缩放级别不能为空");
        }
        if (request.getServiceUrl() == null || request.getServiceUrl().isBlank()) {
            return RestResult.failed("服务地址不能为空");
        }

        RegionDownloadResult result = tileDownloadService.startDownload(request);
        return RestResult.success(result);
    }
}
