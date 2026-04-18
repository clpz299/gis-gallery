<script setup>
import { onMounted, ref, watch } from 'vue';
import 'ol/ol.css';
import Map from 'ol/Map';
import View from 'ol/View';
import TileLayer from 'ol/layer/Tile';
import HeatmapLayer from 'ol/layer/Heatmap';
import XYZ from 'ol/source/XYZ';
import VectorLayer from 'ol/layer/Vector';
import VectorSource from 'ol/source/Vector';
import Feature from 'ol/Feature';
import Point from 'ol/geom/Point';
import { fromLonLat } from 'ol/proj';
import { Style, Circle, Fill, Stroke, Text } from 'ol/style';
import Overlay from 'ol/Overlay';
import { getWeatherHeatmap, listWeatherForecastTimes, listWeatherRuns } from '../api/weather';

const mapContainer = ref(null);
const popupContainer = ref(null);
const popupContent = ref(null);
const popupCloser = ref(null);
const model = ref('gfs_0p25');
const selectedElement = ref('temp');
const selectedLevel = ref('surface');
const heatmapRadius = ref(18);
const heatmapBlur = ref(26);
const controlsOpen = ref(true);
const runs = ref([]);
const forecastTimes = ref([]);
const selectedRunTimeUtc = ref('');
const selectedLeadHours = ref(null);

let map = null;
let heatmapSource = null;
let heatmapLayer = null;

onMounted(() => {
  // Create simulated weather data
  const weatherData = [
    { city: 'Beijing', coords: [116.4074, 39.9042], temp: 25, condition: 'Sunny' },
    { city: 'Shanghai', coords: [121.4737, 31.2304], temp: 28, condition: 'Cloudy' },
    { city: 'Guangzhou', coords: [113.2644, 23.1291], temp: 32, condition: 'Rainy' },
    { city: 'Chengdu', coords: [104.0668, 30.5728], temp: 22, condition: 'Overcast' },
    { city: 'New York', coords: [-74.0060, 40.7128], temp: 18, condition: 'Clear' },
    { city: 'London', coords: [-0.1278, 51.5074], temp: 15, condition: 'Drizzle' }
  ];

  const features = weatherData.map(data => {
    const feature = new Feature({
      geometry: new Point(fromLonLat(data.coords)),
      ...data
    });
    return feature;
  });

  const vectorSource = new VectorSource({
    features: features
  });

  const vectorLayer = new VectorLayer({
    source: vectorSource,
    style: (feature) => {
      const temp = feature.get('temp');
      let color = '#3399CC'; // Default cool
      if (temp > 30) color = '#FF4500'; // Hot
      else if (temp > 25) color = '#FF8C00'; // Warm
      else if (temp < 10) color = '#0000FF'; // Cold

      return new Style({
        image: new Circle({
          radius: 10,
          fill: new Fill({ color: color }),
          stroke: new Stroke({ color: '#fff', width: 2 })
        }),
        text: new Text({
          text: `${temp}°C`,
          offsetY: -15,
          fill: new Fill({ color: '#000' }),
          stroke: new Stroke({ color: '#fff', width: 2 })
        })
      });
    }
  });

  // Popup overlay
  const overlay = new Overlay({
    element: popupContainer.value,
    autoPan: {
      animation: {
        duration: 250,
      },
    },
  });

  heatmapSource = new VectorSource();
  heatmapLayer = new HeatmapLayer({
    source: heatmapSource,
    blur: heatmapBlur.value,
    radius: heatmapRadius.value,
    weight: (feature) => {
      const w = feature.get('weight');
      return typeof w === 'number' ? w : 0;
    },
  });

  map = new Map({
    target: mapContainer.value,
    layers: [
      new TileLayer({
        source: new XYZ({
          url: 'https://{a-c}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
          crossOrigin: 'anonymous'
        })
      }),
      heatmapLayer,
      vectorLayer
    ],
    view: new View({
      center: fromLonLat([116.4074, 39.9042]), // Center on Beijing
      zoom: 4
    }),
    overlays: [overlay]
  });

  // Click handler
  map.on('singleclick', function (evt) {
    const feature = map.forEachFeatureAtPixel(evt.pixel, function (feature) {
      return feature;
    });

    if (feature) {
      const coordinate = evt.coordinate;
      const props = feature.getProperties();
      popupContent.value.innerHTML = `
        <p><strong>${props.city}</strong></p>
        <p>Temp: ${props.temp}°C</p>
        <p>Condition: ${props.condition}</p>
      `;
      overlay.setPosition(coordinate);
    } else {
      overlay.setPosition(undefined);
    }
  });

  // Close popup
  popupCloser.value.onclick = function () {
    overlay.setPosition(undefined);
    popupCloser.value.blur();
    return false;
  };

  refreshHeatmap();

  loadRuns();
});

watch([selectedElement, selectedLevel, selectedRunTimeUtc, selectedLeadHours, heatmapRadius, heatmapBlur], () => {
  if (!map || !heatmapLayer) return;
  heatmapLayer.setRadius(heatmapRadius.value);
  heatmapLayer.setBlur(heatmapBlur.value);
  refreshHeatmap();
});

watch(selectedRunTimeUtc, () => {
  loadForecastTimes();
});

const loadRuns = async () => {
  try {
    const data = await listWeatherRuns({ model: model.value, limit: 24 });
    runs.value = Array.isArray(data) ? data : [];
    if (!selectedRunTimeUtc.value && runs.value.length > 0) {
      selectedRunTimeUtc.value = runs.value[0].runTimeUtc;
    }
  } catch (e) {
    runs.value = [];
  } finally {
    loadForecastTimes();
  }
};

const loadForecastTimes = async () => {
  if (!selectedRunTimeUtc.value) {
    forecastTimes.value = [];
    selectedLeadHours.value = null;
    return;
  }
  try {
    const data = await listWeatherForecastTimes({ model: model.value, runTimeUtc: selectedRunTimeUtc.value });
    forecastTimes.value = Array.isArray(data) ? data : [];
    if (forecastTimes.value.length > 0) {
      const first = forecastTimes.value[0];
      if (selectedLeadHours.value == null) {
        selectedLeadHours.value = first.leadHours;
      }
    } else {
      selectedLeadHours.value = null;
    }
  } catch (e) {
    forecastTimes.value = [];
    selectedLeadHours.value = null;
  }
};

const refreshHeatmap = async () => {
  if (!heatmapSource) return;
  try {
    const data = await getWeatherHeatmap({
      model: model.value,
      element: selectedElement.value,
      level: selectedLevel.value,
      runTimeUtc: selectedRunTimeUtc.value || undefined,
      leadHours: selectedLeadHours.value == null ? undefined : selectedLeadHours.value,
      minLon: 70,
      minLat: 15,
      maxLon: 140,
      maxLat: 55,
      points: 1600,
    });

    const pts = Array.isArray(data?.points) ? data.points : [];
    let min = Number.POSITIVE_INFINITY;
    let max = Number.NEGATIVE_INFINITY;
    for (const p of pts) {
      if (typeof p.value === 'number' && Number.isFinite(p.value)) {
        min = Math.min(min, p.value);
        max = Math.max(max, p.value);
      }
    }
    if (!Number.isFinite(min) || !Number.isFinite(max) || min === max) {
      min = 0;
      max = 1;
    }

    heatmapSource.clear(true);
    const features = pts.map((p) => {
      const f = new Feature({
        geometry: new Point(fromLonLat([p.lon, p.lat])),
      });
      const w = (p.value - min) / (max - min);
      f.set('weight', Math.max(0, Math.min(1, w)));
      return f;
    });
    heatmapSource.addFeatures(features);
  } catch (e) {
    heatmapSource.clear(true);
  }
};
</script>

<template>
  <div class="map-wrapper">
    <div class="map-controls" :class="{ open: controlsOpen }">
      <button class="controls-toggle" type="button" @click="controlsOpen = !controlsOpen">
        <span class="toggle-title">气象要素</span>
        <span class="toggle-value">
          {{
            selectedElement === 'temp'
              ? '温度'
              : selectedElement === 'wind_u'
                ? 'U风分量'
                : selectedElement === 'wind_v'
                  ? 'V风分量'
                  : selectedElement === 'pressure'
                    ? '海平面气压'
                    : selectedElement
          }}
        </span>
        <span class="toggle-icon" aria-hidden="true"></span>
      </button>
      <div class="controls-body" v-show="controlsOpen">
        <div class="control-item">
          <span class="label">起报</span>
          <select v-model="selectedRunTimeUtc" :disabled="runs.length === 0">
            <option v-for="r in runs" :key="r.runTimeUtc" :value="r.runTimeUtc">
              {{ r.runTimeUtc }}
            </option>
          </select>
        </div>
        <div class="control-item">
          <span class="label">时效</span>
          <select v-model.number="selectedLeadHours" :disabled="forecastTimes.length === 0">
            <option v-for="t in forecastTimes" :key="t.leadHours" :value="t.leadHours">
              +{{ t.leadHours }}h
            </option>
          </select>
        </div>
        <div class="control-item">
          <span class="label">要素</span>
          <select v-model="selectedElement">
            <option value="temp">温度</option>
            <option value="wind_u">U风分量</option>
            <option value="wind_v">V风分量</option>
            <option value="pressure">海平面气压</option>
          </select>
        </div>
        <div class="control-item">
          <span class="label">半径</span>
          <input type="range" min="8" max="40" step="1" v-model.number="heatmapRadius" />
        </div>
        <div class="control-item">
          <span class="label">模糊</span>
          <input type="range" min="10" max="60" step="1" v-model.number="heatmapBlur" />
        </div>
      </div>
    </div>
    <div ref="mapContainer" class="map"></div>
    <div ref="popupContainer" class="ol-popup">
      <a href="#" ref="popupCloser" class="ol-popup-closer">✖</a>
      <div ref="popupContent"></div>
    </div>
  </div>
</template>

<style scoped>
.map-wrapper {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 0;
}
.map {
  width: 100%;
  height: 100%;
}
.map-controls {
  position: absolute;
  top: 14px;
  right: 14px;
  z-index: 1200;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(12, 16, 30, 0.58);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  box-shadow: 0 10px 20px rgba(0, 0, 0, 0.35);
}

.controls-toggle {
  appearance: none;
  border: none;
  background: transparent;
  color: rgba(255, 255, 255, 0.88);
  padding: 6px 4px;
  cursor: pointer;
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 10px;
  align-items: center;
}

.toggle-title {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.7);
  white-space: nowrap;
}

.toggle-value {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.92);
  text-align: right;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 180px;
}

.toggle-icon {
  width: 10px;
  height: 10px;
  border-right: 2px solid rgba(255, 255, 255, 0.65);
  border-bottom: 2px solid rgba(255, 255, 255, 0.65);
  transform: rotate(45deg);
  transition: transform 0.2s ease;
}

.map-controls.open .toggle-icon {
  transform: rotate(-135deg);
}

.controls-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 2px;
}

.control-item {
  display: flex;
  gap: 10px;
  align-items: center;
}
.label {
  color: rgba(255, 255, 255, 0.7);
  font-size: 12px;
  white-space: nowrap;
}
.control-item select {
  appearance: none;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(0, 0, 0, 0.22);
  color: rgba(255, 255, 255, 0.9);
  padding: 6px 10px;
  border-radius: 10px;
  outline: none;
}
.control-item input[type="range"] {
  width: 180px;
}
.ol-popup {
  position: absolute;
  background-color: white;
  box-shadow: 0 1px 4px rgba(0,0,0,0.2);
  padding: 15px;
  border-radius: 10px;
  border: 1px solid #cccccc;
  bottom: 12px;
  left: -50px;
  min-width: 200px;
  color: #333;
  z-index: 1000; /* Ensure popup is above map controls */
}
.ol-popup:after, .ol-popup:before {
  top: 100%;
  border: solid transparent;
  content: " ";
  height: 0;
  width: 0;
  position: absolute;
  pointer-events: none;
}
.ol-popup:after {
  border-top-color: white;
  border-width: 10px;
  left: 48px;
  margin-left: -10px;
}
.ol-popup:before {
  border-top-color: #cccccc;
  border-width: 11px;
  left: 48px;
  margin-left: -11px;
}
.ol-popup-closer {
  text-decoration: none;
  position: absolute;
  top: 2px;
  right: 8px;
  color: #333;
}
.ol-popup-closer:after {
  content: "";
}
</style>
