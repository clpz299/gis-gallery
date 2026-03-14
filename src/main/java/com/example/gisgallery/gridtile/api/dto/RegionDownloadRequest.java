package com.example.gisgallery.gridtile.api.dto;

import lombok.Data;
import lombok.Getter;

import java.util.List;

/**
 * 瓦片下载请求参数
 *
 * @author clpz299
 */
@Data
public class RegionDownloadRequest {
    /**
     * 行政区划代码
     */
    private Long adcode;

    /**
     * 行政区划名称（用于生成文件名）
     */
    private String regionName;

    /**
     * 需要下载的缩放级别列表（最多2级）
     */
    private List<Integer> zoomLevels;

    /**
     * 瓦片服务地址模板
     * 例如：https://tile.openstreetmap.org/{z}/{x}/{y}.png
     */
    private String serviceUrl;

    /**
     * 是否合并为单张图片
     * true: 合并为一张大图
     * false: 保留 xyz 瓦片目录结构
     */
    private boolean merge;

    /**
     * 目标坐标系，默认 EPSG:4490
     */
    private String targetEpsg = "EPSG:4490";

    /**
     * 源坐标系，默认 EPSG:3857 (Web Mercator)
     * 可选: EPSG:3857, EPSG:4326
     */
    private String sourceEpsg = "EPSG:3857";

    /**
     * 输出格式
     * 可选: png, tif
     * 默认: png
     */
    private String format = "png";
}
