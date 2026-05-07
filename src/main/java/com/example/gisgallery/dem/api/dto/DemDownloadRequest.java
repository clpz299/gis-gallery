package com.example.gisgallery.dem.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @author clpz299
 */
@Schema(description = "OpenTopography DEM Download Request")
public class DemDownloadRequest {

    @Schema(description = "DEM Type (e.g., SRTMGL1, COP30, COP90)", required = true)
    private String demtype;

    @Schema(description = "South latitude", required = true)
    private Double south;

    @Schema(description = "North latitude", required = true)
    private Double north;

    @Schema(description = "West longitude", required = true)
    private Double west;

    @Schema(description = "East longitude", required = true)
    private Double east;

    @Schema(description = "OpenTopography API Key", required = true)
    @JsonProperty("apiKey")
    private String apiKey;

    public String getDemtype() {
        return demtype;
    }

    public void setDemtype(String demtype) {
        this.demtype = demtype;
    }

    public Double getSouth() {
        return south;
    }

    public void setSouth(Double south) {
        this.south = south;
    }

    public Double getNorth() {
        return north;
    }

    public void setNorth(Double north) {
        this.north = north;
    }

    public Double getWest() {
        return west;
    }

    public void setWest(Double west) {
        this.west = west;
    }

    public Double getEast() {
        return east;
    }

    public void setEast(Double east) {
        this.east = east;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
