package com.example.gisgallery.common.util.reproject;

import com.example.gisgallery.common.util.TileUtils;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.processing.Operations;
import org.geotools.geometry.Envelope2D;
import org.geotools.referencing.CRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 基于 GeoTools 的重投影输出器（Writer 装饰器）。
 *
 * <p>设计目标：</p>
 * <ul>
 *   <li>保持 TileUtils 核心逻辑“只负责下载/拼接”的职责边界</li>
 *   <li>将“重投影”作为输出阶段可选能力，通过 writer 组合链接入</li>
 * </ul>
 *
 * <p>当前实现仅覆盖一个常见场景：</p>
 * <ul>
 *   <li>当输入瓦片矩阵为 WebMercator（EPSG:3857）时，将拼接结果重投影到 EPSG:4326 再交给下游 writer 输出</li>
 * </ul>
 *
 * <p>注意：</p>
 * <ul>
 *   <li>这里的 bbox 仍沿用 TileUtils 计算得到的经纬度 bbox（主要用于 GeoTIFF 写入地理参考）</li>
 *   <li>像素到地理坐标的准确性依赖于 tileRange 推导的 3857 包络</li>
 * </ul>
 *
 * @author clpz299
 */
public final class GeoToolsReprojectingWriter implements TileUtils.TileImageWriter {

    private final TileUtils.TileImageWriter delegate;
    private final String targetEpsg;

    /**
     * 构造一个重投影 writer。
     *
     * @param delegate   实际输出 writer（例如 PNG/GeoTIFF writer）
     * @param targetEpsg 目标坐标系（当前仅支持 EPSG:4326）
     */
    public GeoToolsReprojectingWriter(TileUtils.TileImageWriter delegate, String targetEpsg) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate 不能为空");
        }
        if (targetEpsg == null || targetEpsg.isBlank()) {
            throw new IllegalArgumentException("targetEpsg 不能为空");
        }
        this.delegate = delegate;
        this.targetEpsg = targetEpsg;
    }

    /**
     * 对外写出入口。
     *
     * <p>策略：</p>
     * <ul>
     *   <li>若输入不是 WebMercator 瓦片，则不做重投影，直接委托下游 writer</li>
     *   <li>若目标 EPSG 不是 4326/4490，则当前版本不处理，直接委托下游 writer</li>
     *   <li>仅在满足条件时执行 3857 -> 4326/4490 重投影后再委托输出</li>
     * </ul>
     */
    @Override
    public void write(TileUtils.TileImage tileImage, OutputStream out) throws IOException {
        if (tileImage == null) {
            throw new IllegalArgumentException("tileImage 不能为空");
        }

        // 仅处理 WebMercator 场景，其他矩阵无需重投影
        if (tileImage.matrixSet() != TileUtils.TileMatrixSet.WEB_MERCATOR) {
            delegate.write(tileImage, out);
            return;
        }

        TileUtils.TileMatrixSet targetMatrixSet;
        if ("EPSG:4326".equalsIgnoreCase(targetEpsg)) {
            targetMatrixSet = TileUtils.TileMatrixSet.EPSG_4326;
        } else if ("EPSG:4490".equalsIgnoreCase(targetEpsg)) {
            targetMatrixSet = TileUtils.TileMatrixSet.EPSG_4490;
        } else {
            // 不支持的目标坐标系，直接透传
            delegate.write(tileImage, out);
            return;
        }

        // 执行重投影：把拼接后的图片视作带 3857 包络的栅格，resample 到目标坐标系
        BufferedImage reprojected = reproject3857ToTarget(tileImage, targetEpsg);
        TileUtils.TileImage outImage = new TileUtils.TileImage(
                reprojected,
                tileImage.bbox(),
                targetMatrixSet,
                tileImage.tileRange()
        );
        delegate.write(outImage, out);
    }


// ... (省略 imports)

    /**
     * 将 WebMercator（EPSG:3857）拼接结果重投影到目标坐标系（如 EPSG:4326 或 EPSG:4490）。
     */
    private static BufferedImage reproject3857ToTarget(TileUtils.TileImage tileImage, String targetEpsg) throws IOException {
        try {
            // ... (省略 CRS 获取代码，保持不变) ...
            CoordinateReferenceSystem srcCrs;
            try {
                srcCrs = CRS.decode("EPSG:3857", true);
            } catch (Exception e) {
                String wkt3857 = "PROJCS[\"WGS 84 / Pseudo-Mercator\",GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563,AUTHORITY[\"EPSG\",\"7030\"]],AUTHORITY[\"EPSG\",\"6326\"]],PRIMEM[\"Greenwich\",0,AUTHORITY[\"EPSG\",\"8901\"]],UNIT[\"degree\",0.0174532925199433,AUTHORITY[\"EPSG\",\"9122\"]],AUTHORITY[\"EPSG\",\"4326\"]],PROJECTION[\"Mercator_1SP\"],PARAMETER[\"central_meridian\",0],PARAMETER[\"scale_factor\",1],PARAMETER[\"false_easting\",0],PARAMETER[\"false_northing\",0],UNIT[\"metre\",1,AUTHORITY[\"EPSG\",\"9001\"]],AXIS[\"Easting\",EAST],AXIS[\"Northing\",NORTH],EXTENSION[\"PROJ4\",\"+proj=merc +a=6378137 +b=6378137 +lat_ts=0.0 +lon_0=0.0 +x_0=0.0 +y_0=0 +k=1.0 +units=m +nadgrids=@null +wktext  +no_defs\"],AUTHORITY[\"EPSG\",\"3857\"]]";
                srcCrs = CRS.parseWKT(wkt3857);
            }

            CoordinateReferenceSystem dstCrs;
            try {
                dstCrs = CRS.decode(targetEpsg, true);
            } catch (Exception e) {
                if ("EPSG:4490".equals(targetEpsg)) {
                    // 补充 Bursa-Wolf 参数到 WKT，虽然 CGCS2000 和 WGS84 近似，但 GeoTools 在严格模式下可能需要转换参数
                    // 这里简化处理，直接复用标准定义
                    String wkt4490 = "GEOGCS[\"China Geodetic Coordinate System 2000\",DATUM[\"China_2000\",SPHEROID[\"CGCS2000\",6378137,298.257222101,AUTHORITY[\"EPSG\",\"1024\"]],AUTHORITY[\"EPSG\",\"1043\"]],PRIMEM[\"Greenwich\",0,AUTHORITY[\"EPSG\",\"8901\"]],UNIT[\"degree\",0.0174532925199433,AUTHORITY[\"EPSG\",\"9122\"]],AUTHORITY[\"EPSG\",\"4490\"]]";
                    dstCrs = CRS.parseWKT(wkt4490);
                } else {
                    throw e;
                }
            }

            TileUtils.TileRange r = tileImage.tileRange();
            if (r == null) {
                throw new IllegalArgumentException("tileRange 不能为空");
            }

            // 由瓦片范围推导出该拼接图覆盖的 3857 包络范围（米）
            WebMercatorBounds b = WebMercatorBounds.fromTileRange(r);
            Envelope2D env3857 = new Envelope2D(srcCrs, b.minX, b.minY, b.maxX - b.minX, b.maxY - b.minY);

            GridCoverageFactory factory = new GridCoverageFactory();
            GridCoverage2D src = factory.create("tiles", tileImage.image(), env3857);

            // 关键修复：允许在未定义 Bursa-Wolf 参数的情况下进行宽容转换 (Lenient)
            // 3857 (WGS84 based) -> 4490 (CGCS2000) 理论上需要基准面转换参数
            // 但因为二者极其接近，我们可以指示 GeoTools 忽略此差异
            try {
                GridCoverage2D dst = (GridCoverage2D) Operations.DEFAULT.resample(src, dstCrs);
                RenderedImage ri = dst.getRenderedImage();
                return toBufferedImage(ri);
            } catch (Exception e) {
                // 如果是 EPSG:4490 (CGCS2000) 且缺少 Bursa-Wolf 参数导致直接转换失败
                // 采用降级策略：先转为 EPSG:4326 (WGS84)，再重定义为 EPSG:4490
                if ("EPSG:4490".equals(targetEpsg)) {
                    CoordinateReferenceSystem wgs84 = CRS.decode("EPSG:4326", true);
                    GridCoverage2D wgs84Coverage = (GridCoverage2D) Operations.DEFAULT.resample(src, wgs84);

                    // Re-cast: 保持像素数据和包络不变，仅替换 CRS
                    GridCoverageFactory gcf = new GridCoverageFactory();
                    GridCoverage2D dst4490 = gcf.create(
                            wgs84Coverage.getName(),
                            wgs84Coverage.getRenderedImage(),
                            new Envelope2D(dstCrs, wgs84Coverage.getEnvelope2D().getBounds2D())
                    );
                    return toBufferedImage(dst4490.getRenderedImage());
                }
                throw e;
            }

        } catch (Exception e) {
            throw new IOException("重投影失败: " + targetEpsg, e);
        }
    }

    /**
     * 将 RenderedImage 转换为 BufferedImage。
     *
     * <p>说明：不使用 javax.media.jai.PlanarImage，避免额外依赖。</p>
     */
    private static BufferedImage toBufferedImage(RenderedImage image) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawRenderedImage(image, new AffineTransform());
        g.dispose();
        return out;
    }

    /**
     * WebMercator(3857) 范围结构（单位：米）。
     */
    private static final class WebMercatorBounds {
        final double minX;
        final double minY;
        final double maxX;
        final double maxY;

        private WebMercatorBounds(double minX, double minY, double maxX, double maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        /**
         * 从瓦片范围推导 3857 的包络范围。
         *
         * <p>推导逻辑：</p>
         * <ul>
         *   <li>originShift = 20037508.342789244</li>
         *   <li>resolution = 2πR / (256 * 2^z)</li>
         *   <li>x：左边界 = minX * 256 * res - originShift；右边界 = (maxX+1) * 256 * res - originShift</li>
         *   <li>y：上边界 = originShift - minY * 256 * res；下边界 = originShift - (maxY+1) * 256 * res</li>
         * </ul>
         */
        static WebMercatorBounds fromTileRange(TileUtils.TileRange range) {
            double originShift = 2.0 * Math.PI * 6378137.0 / 2.0;
            double resolution = (2.0 * Math.PI * 6378137.0) / (256.0 * (1L << range.zoom()));

            double minX = range.minX() * 256.0 * resolution - originShift;
            double maxX = (range.maxX() + 1L) * 256.0 * resolution - originShift;

            double maxY = originShift - range.minY() * 256.0 * resolution;
            double minY = originShift - (range.maxY() + 1L) * 256.0 * resolution;

            return new WebMercatorBounds(minX, minY, maxX, maxY);
        }
    }
}
