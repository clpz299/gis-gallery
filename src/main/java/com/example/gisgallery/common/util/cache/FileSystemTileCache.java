package com.example.gisgallery.common.util.cache;

import com.example.gisgallery.common.util.TileUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 文件系统瓦片缓存。
 *
 * @author clpz299
 */
public final class FileSystemTileCache {

    private final Path root;
    private final String defaultExtension;

    /**
     * 构造缓存实例，默认图片缓存后缀为 png。
     *
     * @param root 缓存根目录
     */
    public FileSystemTileCache(Path root) {
        this(root, "png");
    }

    /**
     * 构造缓存实例，并指定默认文件后缀。
     *
     * <p>默认后缀仅用于 {@link #read(String, TileUtils.TileCoord)} / {@link #write(String, TileUtils.TileCoord, BufferedImage)}
     * 这两个“快捷方法”。如需缓存其他类型文件，可使用 readBytes/writeBytes 或 readImage/writeImage 传入 extension。</p>
     *
     * @param root             缓存根目录
     * @param defaultExtension 默认后缀（例如 png / jpg / xlsx / geojson）
     */
    public FileSystemTileCache(Path root, String defaultExtension) {
        if (root == null) {
            throw new IllegalArgumentException("root 不能为空");
        }
        this.root = root;
        this.defaultExtension = normalizeExtension(defaultExtension);
    }

    /**
     * 读取默认类型的图片缓存（默认后缀由构造器指定，默认 png）。
     *
     * @param namespace 缓存命名空间
     * @param coord     瓦片坐标
     * @return BufferedImage；若不存在或文件为空则返回 null
     */
    public BufferedImage read(String namespace, TileUtils.TileCoord coord) throws IOException {
        return readImage(namespace, coord, defaultExtension);
    }

    /**
     * 写入默认类型的图片缓存（默认后缀由构造器指定，默认 png）。
     *
     * @param namespace 缓存命名空间
     * @param coord     瓦片坐标
     * @param image     图片内容（null 直接忽略）
     */
    public void write(String namespace, TileUtils.TileCoord coord, BufferedImage image) throws IOException {
        writeImage(namespace, coord, image, defaultExtension);
    }

    /**
     * 读取图片缓存。
     *
     * <p>该方法使用 ImageIO 解码，因此仅适用于 ImageIO 支持的图片格式。</p>
     *
     * @param namespace 缓存命名空间
     * @param coord     瓦片坐标
     * @param extension 文件后缀（不含点，例如 png/jpg/webp）
     * @return BufferedImage；若不存在或文件为空则返回 null
     */
    public BufferedImage readImage(String namespace, TileUtils.TileCoord coord, String extension) throws IOException {
        Path p = filePath(namespace, coord, extension);
        if (!Files.exists(p) || Files.size(p) <= 0) {
            return null;
        }
        try (InputStream in = Files.newInputStream(p)) {
            return ImageIO.read(in);
        }
    }

    /**
     * 写入图片缓存。
     *
     * <p>内部会先写入临时文件再原子替换，避免并发/异常导致缓存文件损坏。</p>
     *
     * @param namespace 缓存命名空间
     * @param coord     瓦片坐标
     * @param image     图片内容（null 直接忽略）
     * @param extension 文件后缀（不含点，例如 png/jpg/webp）
     */
    public void writeImage(String namespace, TileUtils.TileCoord coord, BufferedImage image, String extension) throws IOException {
        if (image == null) {
            return;
        }
        Path p = filePath(namespace, coord, extension);
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            boolean ok = ImageIO.write(image, normalizeExtension(extension), out);
            if (!ok) {
                throw new IOException("不支持的图片格式: " + extension);
            }
        }
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * 读取任意二进制缓存（适用于 Excel/PDF/ZIP/GeoJSON 等）。
     *
     * @param namespace 缓存命名空间
     * @param coord     业务键（沿用 TileCoord 作为分级目录 key）
     * @param extension 文件后缀（不含点，例如 xlsx/pdf/zip/geojson）
     * @return 文件字节数组；若不存在或文件为空则返回 null
     */
    public byte[] readBytes(String namespace, TileUtils.TileCoord coord, String extension) throws IOException {
        Path p = filePath(namespace, coord, extension);
        if (!Files.exists(p) || Files.size(p) <= 0) {
            return null;
        }
        return Files.readAllBytes(p);
    }

    /**
     * 写入任意二进制缓存（适用于 Excel/PDF/ZIP/GeoJSON 等）。
     *
     * <p>内部会先写入临时文件再原子替换，避免并发/异常导致缓存文件损坏。</p>
     *
     * @param namespace 缓存命名空间
     * @param coord     业务键（沿用 TileCoord 作为分级目录 key）
     * @param data      文件字节数组（null/空数组会被忽略）
     * @param extension 文件后缀（不含点，例如 xlsx/pdf/zip/geojson）
     */
    public void writeBytes(String namespace, TileUtils.TileCoord coord, byte[] data, String extension) throws IOException {
        if (data == null || data.length == 0) {
            return;
        }
        Path p = filePath(namespace, coord, extension);
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            out.write(data);
        }
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * 生成缓存文件路径：{root}/{namespace}/{z}/{x}/{y}.{ext}
     */
    private Path filePath(String namespace, TileUtils.TileCoord coord, String extension) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace 不能为空");
        }
        if (coord == null) {
            throw new IllegalArgumentException("coord 不能为空");
        }
        String ext = normalizeExtension(extension);
        return root
                .resolve(namespace)
                .resolve(Integer.toString(coord.z()))
                .resolve(Integer.toString(coord.x()))
                .resolve(coord.y() + "." + ext);
    }

    /**
     * 规范化后缀名：
     * <ul>
     *   <li>去掉前导 '.'</li>
     *   <li>trim 并转小写</li>
     *   <li>仅允许字母数字，避免路径穿越等风险</li>
     * </ul>
     */
    private static String normalizeExtension(String extension) {
        if (extension == null) {
            throw new IllegalArgumentException("extension 不能为空");
        }
        String ext = extension.trim();
        if (ext.isEmpty()) {
            throw new IllegalArgumentException("extension 不能为空");
        }
        if (ext.startsWith(".")) {
            ext = ext.substring(1);
        }
        ext = ext.trim().toLowerCase();
        if (ext.isEmpty()) {
            throw new IllegalArgumentException("extension 不能为空");
        }
        if (!ext.matches("[a-z0-9]+")) {
            throw new IllegalArgumentException("extension 不合法: " + extension);
        }
        return ext;
    }
}
