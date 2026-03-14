package com.example.gisgallery.common.util.geotiff;

import com.example.gisgallery.common.util.TileUtils;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.Envelope2D;
import org.geotools.referencing.CRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * GeoTIFF 写出工具（基于 GeoTools）。
 *
 * @author clpz299
 */
public final class GeoTiffTileWriter {

    private GeoTiffTileWriter() {
    }

    public static TileUtils.TileImageWriter writer() {
        return GeoTiffTileWriter::write;
    }

    public static void write(TileUtils.TileImage tileImage, OutputStream out) throws IOException {
        if (tileImage == null) {
            throw new IllegalArgumentException("tileImage 不能为空");
        }
        BufferedImage image = tileImage.image();
        TileUtils.BBox bbox = tileImage.bbox();
        if (image == null) {
            throw new IllegalArgumentException("image 不能为空");
        }
        if (bbox == null) {
            throw new IllegalArgumentException("bbox 不能为空");
        }
        if (out == null) {
            throw new IllegalArgumentException("out 不能为空");
        }

        File tmp = File.createTempFile("tiles_", ".tif");
        GeoTiffWriter writer = null;
        try {
            CoordinateReferenceSystem crs = CRS.decode("EPSG:4326", true);
            Envelope2D env = new Envelope2D(
                    crs,
                    bbox.minLon(),
                    bbox.minLat(),
                    bbox.maxLon() - bbox.minLon(),
                    bbox.maxLat() - bbox.minLat()
            );
            GridCoverageFactory factory = new GridCoverageFactory();
            GridCoverage2D coverage = factory.create("tiles", image, env);

            writer = new GeoTiffWriter(tmp);
            writer.write(coverage, null);

            try (InputStream in = new FileInputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
        } catch (Exception e) {
            throw new IOException("写出 GeoTIFF 失败", e);
        } finally {
            if (writer != null) {
                try {
                    writer.dispose();
                } catch (Exception ignored) {
                }
            }
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }
}
