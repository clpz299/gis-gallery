package com.example.gisgallery.weather.application.service;

import com.example.gisgallery.weather.api.dto.WeatherHeatmapPointDto;
import com.example.gisgallery.weather.api.dto.WeatherHeatmapResponseDto;
import org.springframework.stereotype.Service;
import ucar.ma2.Array;
import ucar.ma2.Index;
import ucar.nc2.NetcdfFile;
import ucar.nc2.NetcdfFiles;
import ucar.nc2.VariableSimpleIF;
import ucar.nc2.Variable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * @author clpz299
 */
@Service
public class GfsNetcdfService {

    public List<WeatherHeatmapPointDto> samplePoints(String localPath,
                                                     String variableName,
                                                     Integer timeIndex,
                                                     Integer levelIndex,
                                                     Integer stride,
                                                     Double minLon,
                                                     Double minLat,
                                                     Double maxLon,
                                                     Double maxLat) throws IOException {
        int step = stride == null ? 6 : Math.max(1, Math.min(stride, 50));

        try (NetcdfFile nc = NetcdfFiles.open(localPath)) {
            Variable lonVar = firstVarOrUnits(nc, "degrees_east", "lon", "longitude", "x");
            Variable latVar = firstVarOrUnits(nc, "degrees_north", "lat", "latitude", "y");
            Variable dataVar = resolveDataVar(nc, variableName);

            Array lonArr = lonVar.read();
            Array latArr = latVar.read();

            int lonLen = lonArr.getShape()[0];
            int latLen = latArr.getShape()[0];

            double bboxMinLon = minLon == null ? minOfLon(lonArr) : minLon;
            double bboxMaxLon = maxLon == null ? maxOfLon(lonArr) : maxLon;
            double bboxMinLat = minLat == null ? minOfLat(latArr) : minLat;
            double bboxMaxLat = maxLat == null ? maxOfLat(latArr) : maxLat;

            int tIndex = timeIndex == null ? 0 : Math.max(0, timeIndex);
            int zIndex = levelIndex == null ? 0 : Math.max(0, levelIndex);

            Array data = dataVar.read();
            DimPlan plan = DimPlan.from(data.getShape());

            List<WeatherHeatmapPointDto> points = new ArrayList<>();
            for (int yi = 0; yi < latLen; yi += step) {
                double lat = toDouble(latArr, yi);
                if (lat < bboxMinLat || lat > bboxMaxLat) {
                    continue;
                }
                for (int xi = 0; xi < lonLen; xi += step) {
                    double lon = toDouble(lonArr, xi);
                    if (lon < bboxMinLon || lon > bboxMaxLon) {
                        continue;
                    }
                    double v = plan.read(data, tIndex, zIndex, yi, xi);
                    if (Double.isFinite(v)) {
                        points.add(new WeatherHeatmapPointDto(lon, lat, v));
                    }
                }
            }
            return points;
        }
    }

    public WeatherHeatmapResponseDto sampleToHeatmap(String localPath,
                                                     String element,
                                                     String variableName,
                                                     Integer timeIndex,
                                                     Integer levelIndex,
                                                     Integer stride,
                                                     Double minLon,
                                                     Double minLat,
                                                     Double maxLon,
                                                     Double maxLat) throws IOException {
        String normalizedElement = element == null ? "tmp_2m" : element.trim().toLowerCase(Locale.ROOT);
        List<WeatherHeatmapPointDto> points = samplePoints(localPath, variableName, timeIndex, levelIndex, stride, minLon, minLat, maxLon, maxLat);
        return new WeatherHeatmapResponseDto(normalizedElement, "", points);
    }

    private Variable firstVar(NetcdfFile nc, String... names) {
        for (String n : names) {
            Variable v = nc.findVariable(n);
            if (v != null) {
                return v;
            }
        }
        throw new IllegalArgumentException("variable not found: " + String.join("/", names));
    }

    private Variable firstVarOrUnits(NetcdfFile nc, String expectedUnits, String... names) {
        for (String n : names) {
            Variable v = nc.findVariable(n);
            if (v != null) {
                return v;
            }
            Variable vUpper = nc.findVariable(n.toUpperCase(Locale.ROOT));
            if (vUpper != null) {
                return vUpper;
            }
        }

        for (Variable v : nc.getVariables()) {
            var units = v.findAttributeIgnoreCase("units");
            if (units != null && units.isString() && expectedUnits.equalsIgnoreCase(units.getStringValue())) {
                return v;
            }
        }
        throw new IllegalArgumentException("variable not found: " + String.join("/", names));
    }

    private Variable resolveDataVar(NetcdfFile nc, String variableName) {
        if (variableName != null && !variableName.isBlank()) {
            Variable v = nc.findVariable(variableName);
            if (v != null) {
                return v;
            }
            Variable vUpper = nc.findVariable(variableName.toUpperCase(Locale.ROOT));
            if (vUpper != null) {
                return vUpper;
            }

            String abbrev = mapNcVarToGribAbbrev(variableName);
            if (abbrev != null) {
                Variable byAttr = findByGribParamAbbrev(nc, abbrev);
                if (byAttr != null) {
                    return byAttr;
                }
                Variable byName = findByShortNameContains(nc, abbrev);
                if (byName != null) {
                    return byName;
                }
            }
        }

        Variable fallback = findFirstNumericGridVar(nc);
        if (fallback != null) {
            return fallback;
        }

        String available = nc.getVariables().stream()
                .limit(60)
                .map(VariableSimpleIF::getShortName)
                .toList()
                .toString();
        throw new IllegalArgumentException("variable not found: " + String.join("/", "tmp_2m", "t2m", "u10", "v10", "prmsl", "TMP", "UGRD", "VGRD", "PRMSL") + " available=" + available);
    }

    private String mapNcVarToGribAbbrev(String ncVar) {
        String v = ncVar.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "t2m" -> "TMP";
            case "u10" -> "UGRD";
            case "v10" -> "VGRD";
            case "prmsl" -> "PRMSL";
            default -> null;
        };
    }

    private Variable findByShortNameContains(NetcdfFile nc, String token) {
        String t = token.toLowerCase(Locale.ROOT);
        for (Variable v : nc.getVariables()) {
            String name = v.getShortName();
            if (name != null && name.toLowerCase(Locale.ROOT).contains(t)) {
                return v;
            }
        }
        return null;
    }

    private Variable findByGribParamAbbrev(NetcdfFile nc, String abbrev) {
        String want = abbrev.toLowerCase(Locale.ROOT);
        String[] keys = new String[]{
                "GRIB_param_abbrev",
                "GRIB2_parameter_abbrev",
                "GRIB2_param_abbrev",
                "GRIB_shortName",
                "grib_param_abbrev",
                "grib2_parameter_abbrev",
                "grib_shortName"
        };
        for (Variable v : nc.getVariables()) {
            for (String k : keys) {
                var a = v.findAttributeIgnoreCase(k);
                if (a != null && a.isString() && a.getStringValue() != null) {
                    if (a.getStringValue().toLowerCase(Locale.ROOT).equals(want)) {
                        return v;
                    }
                }
            }
        }
        return null;
    }

    private Variable findFirstNumericGridVar(NetcdfFile nc) {
        for (Variable v : nc.getVariables()) {
            if (v.getDataType() == null || !v.getDataType().isNumeric()) {
                continue;
            }
            if (v.getRank() < 2) {
                continue;
            }
            String name = v.getShortName();
            if (name == null) {
                continue;
            }
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("lat") || n.contains("lon") || n.contains("time")) {
                continue;
            }
            return v;
        }
        return null;
    }

    private double toDouble(Array arr, int idx) {
        return arr.getDouble(arr.getIndex().set(idx));
    }

    private double minOfLon(Array arr) {
        double m = Double.POSITIVE_INFINITY;
        for (int i = 0; i < arr.getShape()[0]; i++) {
            m = Math.min(m, toDouble(arr, i));
        }
        return m;
    }

    private double maxOfLon(Array arr) {
        double m = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < arr.getShape()[0]; i++) {
            m = Math.max(m, toDouble(arr, i));
        }
        return m;
    }

    private double minOfLat(Array arr) {
        double m = Double.POSITIVE_INFINITY;
        for (int i = 0; i < arr.getShape()[0]; i++) {
            m = Math.min(m, toDouble(arr, i));
        }
        return m;
    }

    private double maxOfLat(Array arr) {
        double m = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < arr.getShape()[0]; i++) {
            m = Math.max(m, toDouble(arr, i));
        }
        return m;
    }

    private record DimPlan(int rank, int tDim, int zDim, int yDim, int xDim) {
        static DimPlan from(int[] shape) {
            int rank = shape.length;
            if (rank == 2) {
                return new DimPlan(rank, -1, -1, 0, 1);
            }
            if (rank == 3) {
                return new DimPlan(rank, 0, -1, 1, 2);
            }
            if (rank >= 4) {
                return new DimPlan(rank, 0, 1, rank - 2, rank - 1);
            }
            throw new IllegalArgumentException("unsupported rank: " + rank);
        }

        double read(Array arr, int tIndex, int zIndex, int yIndex, int xIndex) {
            Index idx = arr.getIndex();
            if (rank == 2) {
                return arr.getDouble(idx.set(yIndex, xIndex));
            }
            if (rank == 3) {
                return arr.getDouble(idx.set(tIndex, yIndex, xIndex));
            }

            int[] dims = new int[rank];
            dims[tDim] = tIndex;
            dims[zDim] = zIndex;
            dims[yDim] = yIndex;
            dims[xDim] = xIndex;
            return arr.getDouble(idx.set(dims));
        }
    }
}
