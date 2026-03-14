package com.example.gisgallery.common.util.cache;

import com.example.gisgallery.common.util.TileUtils;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * 具备磁盘缓存能力的 TileSource 装饰器。
 *
 * @author clpz299
 */
public final class CachingTileSource implements TileUtils.TileSource {

    private final TileUtils.TileSource delegate;
    private final FileSystemTileCache cache;
    private final String namespace;
    private final String extension;

    /**
     * 构造一个带磁盘缓存能力的 TileSource。
     *
     * @param delegate  实际的瓦片加载来源（例如 HTTP 下载）
     * @param cache     文件系统缓存实现
     * @param namespace 缓存命名空间，用于区分不同数据源/图层
     */
    public CachingTileSource(TileUtils.TileSource delegate, FileSystemTileCache cache, String namespace) {
        this(delegate, cache, namespace, "png");
    }

    public CachingTileSource(TileUtils.TileSource delegate, FileSystemTileCache cache, String namespace, String extension) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate 不能为空");
        }
        if (cache == null) {
            throw new IllegalArgumentException("cache 不能为空");
        }
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace 不能为空");
        }
        this.delegate = delegate;
        this.cache = cache;
        this.namespace = namespace;
        this.extension = extension;
    }

    /**
     * 加载瓦片（优先缓存，缓存未命中则委托真实来源加载并写入缓存）。
     *
     * @param coord 瓦片坐标
     * @return 瓦片图片（BufferedImage）
     */
    @Override
    public BufferedImage load(TileUtils.TileCoord coord) throws IOException {
        BufferedImage cached = cache.readImage(namespace, coord, extension);
        if (cached != null) {
            return cached;
        }
        BufferedImage img = delegate.load(coord);
        try {
            cache.writeImage(namespace, coord, img, extension);
        } catch (IOException ignored) {
        }
        return img;
    }
}
