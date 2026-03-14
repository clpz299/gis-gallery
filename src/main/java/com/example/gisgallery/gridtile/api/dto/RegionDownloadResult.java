package com.example.gisgallery.gridtile.api.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 瓦片下载结果
 *
 * @author clpz299
 */
@Builder
@Data
public class RegionDownloadResult {
    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 任务状态（例如：SUCCESS, FAILED, RUNNING）
     */
    private String status;

    /**
     * 输出路径（文件或目录）
     */
    private String outputPath;

    /**
     * 错误信息（如果有）
     */
    private String error;
}
