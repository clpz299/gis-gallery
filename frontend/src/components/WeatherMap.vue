<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
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
import { fromLonLat, transformExtent } from 'ol/proj';
import { Style, Circle, Fill, Stroke, Text } from 'ol/style';
import Overlay from 'ol/Overlay';
import { getWeatherHeatmap, listWeatherForecastTimes, listWeatherRuns } from '../api/weather';
import { getRegions } from '../api/gridtile';

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
const selectedLeadIndex = ref(0);
const timelineTouched = ref(false);
const requestPoints = ref(1600);
const bboxMinLon = ref(70);
const bboxMinLat = ref(15);
const bboxMaxLon = ref(140);
const bboxMaxLat = ref(55);
const heatmapOpacity = ref(0.9);
const layerMode = ref('heatmap');
const gridTileSize = ref(32);
const gridOpacity = ref(0.85);
const gridBlur = ref(0.8);
const gridAutoDetail = ref(true);
const gridColorMode = ref('smooth');
const provinceMarkerSize = ref(6);
const showProvinceLabels = ref(true);
const latestPointCount = ref(0);
const latestAvgValue = ref(null);
const latestMinValue = ref(null);
const latestMaxValue = ref(null);
const latestUnit = ref('');

let map = null;
let heatmapSource = null;
let heatmapLayer = null;
let gridSource = null;
let gridLayer = null;
let gridKey = 0;
let gridRefreshTimer = null;
let gridAutoDetailTimer = null;
let provinceSource = null;
let provinceLayer = null;
let pulseTimer = null;
const provinceAnchors = ref([]);
const elementLabelMap = {
  temp: '气温',
  wind_u: 'U风分量',
  wind_v: 'V风分量',
  pressure: '海平面气压',
};
const elementUnitMap = {
  temp: '°C',
  wind_u: 'm/s',
  wind_v: 'm/s',
  pressure: 'hPa',
};

const selectedElementLabel = computed(() => elementLabelMap[selectedElement.value] || selectedElement.value);
const selectedElementUnit = computed(() => elementUnitMap[selectedElement.value] || latestUnit.value || '');
const forecastLeadText = computed(() => (selectedLeadHours.value == null ? 2 : selectedLeadHours.value));
const formattedRunTime = computed(() => {
  if (!selectedRunTimeUtc.value) return '--';
  return selectedRunTimeUtc.value.replace('T', ' ').replace('Z', ' UTC');
});
const latestValueText = computed(() => {
  if (latestAvgValue.value == null || !Number.isFinite(latestAvgValue.value)) return '--';
  const unit = selectedElementUnit.value;
  return `${latestAvgValue.value.toFixed(2)}${unit ? ` ${unit}` : ''}`;
});
const svgTitleMain = computed(() => `气象要素预报时间 ${formattedRunTime.value}`);
const svgTitleSub = computed(() => {
  const lead = selectedLeadHours.value;
  const isCurrent = !timelineTouched.value || lead == null || lead === 0;
  if (isCurrent) {
    return `当前平均${selectedElementLabel.value}：${latestValueText.value}`;
  }
  return `未来平均${lead}小时${selectedElementLabel.value}：${latestValueText.value}`;
});
const selectedForecastTimeText = computed(() => {
  if (!forecastTimes.value.length) return '--';
  const idx = Math.max(0, Math.min(selectedLeadIndex.value, forecastTimes.value.length - 1));
  return forecastTimes.value[idx]?.validTimeUtc || '--';
});

const gridRampAnchors = [
  [8, 29, 88],
  [12, 53, 124],
  [18, 84, 160],
  [34, 121, 194],
  [55, 162, 214],
  [95, 197, 226],
  [163, 227, 255],
  [255, 224, 178],
  [251, 140, 60],
  [231, 76, 60],
];

const gridRampSteps = ref(20);

const hexToRgb = (hex) => {
  const h = String(hex || '').replace('#', '').trim();
  if (h.length !== 6) return [0, 0, 0];
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return [r, g, b].map((v) => (Number.isFinite(v) ? v : 0));
};

const tempStops = [
  { t: -30, c: hexToRgb('#5e3c99') },
  { t: -20, c: hexToRgb('#3b4cc0') },
  { t: -10, c: hexToRgb('#2c7bb6') },
  { t: 0, c: hexToRgb('#00a6ca') },
  { t: 10, c: hexToRgb('#00ccbc') },
  { t: 20, c: hexToRgb('#90eb9d') },
  { t: 30, c: hexToRgb('#f9d057') },
  { t: 35, c: hexToRgb('#f29e2e') },
  { t: 40, c: hexToRgb('#e76818') },
  { t: 50, c: hexToRgb('#d7191c') },
];

const buildRamp = (anchors, steps) => {
  const n = Math.max(2, Math.floor(steps || 2));
  const a = Array.isArray(anchors) ? anchors : [];
  if (a.length < 2) return a;
  const out = [];
  for (let i = 0; i < n; i++) {
    const t = n === 1 ? 0 : i / (n - 1);
    const p = t * (a.length - 1);
    const i0 = Math.floor(p);
    const i1 = Math.min(a.length - 1, i0 + 1);
    const f = p - i0;
    const c0 = a[i0];
    const c1 = a[i1];
    const r = Math.round(c0[0] + (c1[0] - c0[0]) * f);
    const g = Math.round(c0[1] + (c1[1] - c0[1]) * f);
    const b = Math.round(c0[2] + (c1[2] - c0[2]) * f);
    out.push([r, g, b]);
  }
  return out;
};

const gridRamp = computed(() => buildRamp(gridRampAnchors, gridRampSteps.value));

const tempColorSteps = (value) => {
  if (!Number.isFinite(value)) return null;
  if (value <= -30) return tempStops[0].c;
  if (value <= -20) return tempStops[1].c;
  if (value <= -10) return tempStops[2].c;
  if (value <= 0) return tempStops[3].c;
  if (value <= 10) return tempStops[4].c;
  if (value <= 20) return tempStops[5].c;
  if (value <= 30) return tempStops[6].c;
  if (value <= 35) return tempStops[7].c;
  if (value <= 40) return tempStops[8].c;
  return tempStops[9].c;
};

const tempColorSmooth = (value) => {
  if (!Number.isFinite(value)) return null;
  if (value <= tempStops[0].t) return tempStops[0].c;
  if (value >= tempStops[tempStops.length - 2].t) return tempStops[tempStops.length - 1].c;
  for (let i = 0; i < tempStops.length - 2; i++) {
    const a = tempStops[i];
    const b = tempStops[i + 1];
    if (value >= a.t && value <= b.t) {
      const f = (value - a.t) / (b.t - a.t);
      const r = Math.round(a.c[0] + (b.c[0] - a.c[0]) * f);
      const g = Math.round(a.c[1] + (b.c[1] - a.c[1]) * f);
      const bb = Math.round(a.c[2] + (b.c[2] - a.c[2]) * f);
      return [r, g, bb];
    }
  }
  return tempStops[tempStops.length - 1].c;
};

const windStops = [
  { s: 0, c: hexToRgb('#d0f0ff') },
  { s: 2, c: hexToRgb('#74add1') },
  { s: 5, c: hexToRgb('#4daf4a') },
  { s: 10, c: hexToRgb('#a6d96a') },
  { s: 15, c: hexToRgb('#fdae61') },
  { s: 20, c: hexToRgb('#f46d43') },
  { s: 25, c: hexToRgb('#d73027') },
  { s: 30, c: hexToRgb('#a50026') },
];

const windColorSteps = (value) => {
  if (!Number.isFinite(value)) return null;
  const s = Math.abs(value);
  if (s <= 2) return windStops[0].c;
  if (s <= 5) return windStops[1].c;
  if (s <= 10) return windStops[2].c;
  if (s <= 15) return windStops[3].c;
  if (s <= 20) return windStops[4].c;
  if (s <= 25) return windStops[5].c;
  if (s <= 30) return windStops[6].c;
  return windStops[7].c;
};

const windColorSmooth = (value) => {
  if (!Number.isFinite(value)) return null;
  const s = Math.abs(value);
  if (s <= windStops[0].s) return windStops[0].c;
  if (s >= windStops[windStops.length - 1].s) return windStops[windStops.length - 1].c;
  for (let i = 0; i < windStops.length - 1; i++) {
    const a = windStops[i];
    const b = windStops[i + 1];
    if (s >= a.s && s <= b.s) {
      const f = (s - a.s) / (b.s - a.s);
      const r = Math.round(a.c[0] + (b.c[0] - a.c[0]) * f);
      const g = Math.round(a.c[1] + (b.c[1] - a.c[1]) * f);
      const bb = Math.round(a.c[2] + (b.c[2] - a.c[2]) * f);
      return [r, g, bb];
    }
  }
  return windStops[windStops.length - 1].c;
};

const pressureStops = [
  { p: 980, c: hexToRgb('#5e4fa2') },
  { p: 990, c: hexToRgb('#3288bd') },
  { p: 1000, c: hexToRgb('#66c2a5') },
  { p: 1010, c: hexToRgb('#abdda4') },
  { p: 1020, c: hexToRgb('#e6f598') },
  { p: 1030, c: hexToRgb('#fdae61') },
  { p: 1040, c: hexToRgb('#f46d43') },
  { p: 1100, c: hexToRgb('#d53e4f') },
];

const pressureColorSteps = (value) => {
  if (!Number.isFinite(value)) return null;
  const p = value;
  if (p <= 980) return pressureStops[0].c;
  if (p <= 990) return pressureStops[1].c;
  if (p <= 1000) return pressureStops[2].c;
  if (p <= 1010) return pressureStops[3].c;
  if (p <= 1020) return pressureStops[4].c;
  if (p <= 1030) return pressureStops[5].c;
  if (p <= 1040) return pressureStops[6].c;
  return pressureStops[7].c;
};

const pressureColorSmooth = (value) => {
  if (!Number.isFinite(value)) return null;
  const p = value;
  if (p <= pressureStops[0].p) return pressureStops[0].c;
  if (p >= pressureStops[pressureStops.length - 2].p) return pressureStops[pressureStops.length - 1].c;
  for (let i = 0; i < pressureStops.length - 2; i++) {
    const a = pressureStops[i];
    const b = pressureStops[i + 1];
    if (p >= a.p && p <= b.p) {
      const f = (p - a.p) / (b.p - a.p);
      const r = Math.round(a.c[0] + (b.c[0] - a.c[0]) * f);
      const g = Math.round(a.c[1] + (b.c[1] - a.c[1]) * f);
      const bb = Math.round(a.c[2] + (b.c[2] - a.c[2]) * f);
      return [r, g, bb];
    }
  }
  return pressureStops[pressureStops.length - 1].c;
};

const updateGridExtent = () => {
  if (!gridLayer) return;
  const minLon = Number(bboxMinLon.value);
  const minLat = Number(bboxMinLat.value);
  const maxLon = Number(bboxMaxLon.value);
  const maxLat = Number(bboxMaxLat.value);
  if (![minLon, minLat, maxLon, maxLat].every((v) => Number.isFinite(v))) return;
  const lonLatExtent = [minLon, minLat, maxLon, maxLat];
  const mercatorExtent = transformExtent(lonLatExtent, 'EPSG:4326', 'EPSG:3857');
  gridLayer.setExtent(mercatorExtent);
};

const applyGridAutoDetail = () => {
  if (!map || !gridAutoDetail.value) return;
  const z = map.getView()?.getZoom?.();
  const zoom = typeof z === 'number' && Number.isFinite(z) ? z : 4;
  const zoomInt = Math.floor(zoom + 1e-6);
  const target = zoomInt >= 8 ? 64 : zoomInt >= 6 ? 32 : 16;
  if (gridTileSize.value !== target) {
    gridTileSize.value = target;
  }
};

const scheduleGridRefresh = () => {
  if (!gridSource) return;
  gridKey += 1;
  if (gridRefreshTimer) {
    window.clearTimeout(gridRefreshTimer);
  }
  gridRefreshTimer = window.setTimeout(() => {
    if (!gridSource) return;
    gridSource.refresh();
  }, 120);
};

const scheduleGridAutoDetail = () => {
  if (!map || layerMode.value !== 'grid') return;
  if (gridAutoDetailTimer) {
    window.clearTimeout(gridAutoDetailTimer);
  }
  gridAutoDetailTimer = window.setTimeout(() => {
    applyGridAutoDetail();
  }, 120);
};

const legendLevels = computed(() => {
  if (selectedElement.value === 'temp') {
    const unit = selectedElementUnit.value ? ` ${selectedElementUnit.value}` : '';
    return [
      { color: '#d7191c', label: `≥ 40${unit}` },
      { color: '#e76818', label: `35 ~ 40${unit}` },
      { color: '#f29e2e', label: `30 ~ 35${unit}` },
      { color: '#f9d057', label: `20 ~ 30${unit}` },
      { color: '#90eb9d', label: `10 ~ 20${unit}` },
      { color: '#00ccbc', label: `0 ~ 10${unit}` },
      { color: '#00a6ca', label: `-10 ~ 0${unit}` },
      { color: '#2c7bb6', label: `-20 ~ -10${unit}` },
      { color: '#3b4cc0', label: `-30 ~ -20${unit}` },
      { color: '#5e3c99', label: `≤ -30${unit}` },
    ].map((it) => ({ ...it, color: it.color.startsWith('#') ? it.color : `#${it.color}` }));
  }
  if (selectedElement.value === 'wind_u' || selectedElement.value === 'wind_v') {
    const unit = selectedElementUnit.value ? ` ${selectedElementUnit.value}` : '';
    return [
      { color: '#a50026', label: `≥ 30${unit}` },
      { color: '#d73027', label: `25 ~ 30${unit}` },
      { color: '#f46d43', label: `20 ~ 25${unit}` },
      { color: '#fdae61', label: `15 ~ 20${unit}` },
      { color: '#a6d96a', label: `10 ~ 15${unit}` },
      { color: '#4daf4a', label: `5 ~ 10${unit}` },
      { color: '#74add1', label: `2 ~ 5${unit}` },
      { color: '#d0f0ff', label: `0 ~ 2${unit}` },
    ];
  }
  if (selectedElement.value === 'pressure') {
    const unit = selectedElementUnit.value ? ` ${selectedElementUnit.value}` : '';
    return [
      { color: '#d53e4f', label: `≥ 1040${unit}` },
      { color: '#f46d43', label: `1030 ~ 1040${unit}` },
      { color: '#fdae61', label: `1020 ~ 1030${unit}` },
      { color: '#e6f598', label: `1010 ~ 1020${unit}` },
      { color: '#abdda4', label: `1000 ~ 1010${unit}` },
      { color: '#66c2a5', label: `990 ~ 1000${unit}` },
      { color: '#3288bd', label: `980 ~ 990${unit}` },
      { color: '#5e4fa2', label: `≤ 980${unit}` },
    ];
  }
  const min = Number.isFinite(latestMinValue.value) ? latestMinValue.value : null;
  const max = Number.isFinite(latestMaxValue.value) ? latestMaxValue.value : null;
  if (min == null || max == null || !Number.isFinite(min) || !Number.isFinite(max) || min === max) return [];
  const ramp = gridRamp.value;
  const k = ramp.length;
  const step = (max - min) / k;
  const unit = selectedElementUnit.value ? ` ${selectedElementUnit.value}` : '';
  const out = [];
  for (let i = 0; i < k; i++) {
    const lo = min + step * i;
    const hi = i === k - 1 ? max : min + step * (i + 1);
    const c = ramp[i];
    out.push({
      color: `rgb(${c[0]}, ${c[1]}, ${c[2]})`,
      label: `${lo.toFixed(1)} ~ ${hi.toFixed(1)}${unit}`,
    });
  }
  return out.reverse();
});

onMounted(() => {
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
    opacity: heatmapOpacity.value,
    weight: (feature) => {
      const w = feature.get('weight');
      return typeof w === 'number' ? w : 0;
    },
  });

  gridSource = new XYZ({
    tileSize: 256,
    transition: 0,
    wrapX: true,
    tileUrlFunction: (tileCoord) => {
      if (!tileCoord) return '';
      const [z, x, y] = tileCoord;
      const yXyz = -y - 1;
      const n = 1 << z;
      const xXyz = ((x % n) + n) % n;
      const params = new URLSearchParams();
      params.set('z', String(z));
      params.set('x', String(xXyz));
      params.set('y', String(yXyz));
      params.set('size', String(gridTileSize.value));
      params.set('model', model.value);
      params.set('element', selectedElement.value);
      params.set('level', selectedLevel.value);
      if (selectedRunTimeUtc.value) params.set('runTimeUtc', selectedRunTimeUtc.value);
      if (selectedLeadHours.value != null) params.set('leadHours', String(selectedLeadHours.value));
      params.set('v', String(gridKey));
      return `/api/weather/grid-tile?${params.toString()}`;
    },
    tileLoadFunction: async (tile, src) => {
      const image = tile.getImage();
      try {
        if (image.__gridAbortController) {
          image.__gridAbortController.abort();
        }
        const ctrl = new AbortController();
        image.__gridAbortController = ctrl;
        image.__gridSrc = src;

        const resp = await fetch(src, { cache: 'no-store', signal: ctrl.signal });
        if (!resp.ok) {
          throw new Error(`grid-tile http ${resp.status}`);
        }
        const json = await resp.json();
        const payload = json && typeof json === 'object' && 'data' in json ? json.data : json;
        const dataUrl = renderGridTileDataUrl(payload);
        if (image.__gridSrc !== src) {
          return;
        }
        image.src = dataUrl;
      } catch (e) {
        if (e && e.name === 'AbortError') {
          return;
        }
        image.src = transparentTileDataUrl();
      }
    },
  });

  gridLayer = new TileLayer({
    source: gridSource,
    opacity: gridOpacity.value,
    visible: layerMode.value === 'grid',
  });
  updateGridExtent();

  provinceSource = new VectorSource();
  provinceLayer = new VectorLayer({
    source: provinceSource,
  });

  // OpenLayers 样式函数返回单个 Style 或 Style[]，这里返回呼吸底光 + 实心点
  provinceLayer.setStyle((feature) => {
    const weight = feature.get('weight');
    const color = colorByWeight(weight);
    const valueText = feature.get('valueText') || '--';
    const name = feature.get('name') || '';
    const labelText = showProvinceLabels.value ? `${name} ${valueText}` : '';
    const now = Date.now();
    const pulse = ((now % 1800) / 1800) * Math.PI * 2;
    const pulseScale = 0.5 + 0.5 * Math.sin(pulse);
    const pulseRadius = provinceMarkerSize.value + 4 + pulseScale * 7;
    const pulseAlpha = 0.08 + 0.18 * (1 - pulseScale);

    return [
      new Style({
        image: new Circle({
          radius: pulseRadius,
          fill: new Fill({ color: `rgba(56, 189, 248, ${pulseAlpha.toFixed(3)})` }),
        }),
        zIndex: 1,
      }),
      new Style({
        image: new Circle({
          radius: provinceMarkerSize.value,
          fill: new Fill({ color }),
          stroke: new Stroke({ color: '#ffffff', width: 1.2 }),
        }),
        text: new Text({
          text: labelText,
          offsetY: -14,
          fill: new Fill({ color: '#f8fafc' }),
          stroke: new Stroke({ color: 'rgba(2,6,23,0.9)', width: 2.5 }),
        }),
        zIndex: 2,
      }),
    ];
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
      gridLayer,
      provinceLayer
    ],
    view: new View({
      center: fromLonLat([116.4074, 39.9042]), // Center on Beijing
      zoom: 4
    }),
    overlays: [overlay]
  });

  map.getView().on('change:resolution', () => {
    if (layerMode.value !== 'grid') return;
    scheduleGridAutoDetail();
  });
  scheduleGridAutoDetail();

  map.on('moveend', () => {
    if (layerMode.value !== 'grid') return;
    scheduleGridRefresh();
  });

  pulseTimer = window.setInterval(() => {
    if (provinceSource) {
      provinceSource.changed();
    }
  }, 120);

  // Click handler
  map.on('singleclick', function (evt) {
    const feature = map.forEachFeatureAtPixel(evt.pixel, function (feature) {
      return feature;
    });

    if (feature) {
      const coordinate = evt.coordinate;
      const props = feature.getProperties();
      if (props.kind === 'province-gfs') {
        popupContent.value.innerHTML = `
          <p><strong>${props.name}</strong></p>
          <p>要素值: ${props.valueText || '--'}</p>
          <p>来源点: [${props.srcLon?.toFixed(2)}, ${props.srcLat?.toFixed(2)}]</p>
        `;
      } else {
        popupContent.value.innerHTML = `<p>无可展示属性</p>`;
      }
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

  loadProvinceAnchors();
  loadRuns();
});

onBeforeUnmount(() => {
  if (gridRefreshTimer) {
    window.clearTimeout(gridRefreshTimer);
    gridRefreshTimer = null;
  }
  if (gridAutoDetailTimer) {
    window.clearTimeout(gridAutoDetailTimer);
    gridAutoDetailTimer = null;
  }
  if (pulseTimer) {
    window.clearInterval(pulseTimer);
    pulseTimer = null;
  }
});

watch([selectedElement, selectedLevel, selectedRunTimeUtc, selectedLeadHours, heatmapRadius, heatmapBlur, requestPoints, bboxMinLon, bboxMinLat, bboxMaxLon, bboxMaxLat], () => {
  if (!map || !heatmapLayer) return;
  heatmapLayer.setRadius(heatmapRadius.value);
  heatmapLayer.setBlur(heatmapBlur.value);
  refreshHeatmap();
});

watch([bboxMinLon, bboxMinLat, bboxMaxLon, bboxMaxLat], () => {
  updateGridExtent();
  scheduleGridRefresh();
});

watch(selectedRunTimeUtc, () => {
  loadForecastTimes();
});

watch(selectedLeadIndex, () => {
  if (!forecastTimes.value.length) {
    selectedLeadHours.value = null;
    return;
  }
  const idx = Math.max(0, Math.min(selectedLeadIndex.value, forecastTimes.value.length - 1));
  selectedLeadHours.value = forecastTimes.value[idx].leadHours;
});

watch(heatmapOpacity, () => {
  if (heatmapLayer) {
    heatmapLayer.setOpacity(heatmapOpacity.value);
  }
});

watch(gridOpacity, () => {
  if (gridLayer) {
    gridLayer.setOpacity(gridOpacity.value);
  }
});

watch(layerMode, () => {
  if (heatmapLayer) {
    heatmapLayer.setVisible(layerMode.value === 'heatmap');
  }
  if (gridLayer) {
    gridLayer.setVisible(layerMode.value === 'grid');
  }
  scheduleGridRefresh();
  scheduleGridAutoDetail();
  refreshHeatmap();
});

watch([gridTileSize, selectedElement, selectedLevel, selectedRunTimeUtc, selectedLeadHours], () => {
  scheduleGridRefresh();
});

watch([showProvinceLabels, provinceMarkerSize], () => {
  if (provinceSource) {
    provinceSource.changed();
  }
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
    selectedLeadIndex.value = 0;
    timelineTouched.value = false;
    return;
  }
  try {
    const data = await listWeatherForecastTimes({ model: model.value, runTimeUtc: selectedRunTimeUtc.value });
    forecastTimes.value = Array.isArray(data) ? data : [];
    if (forecastTimes.value.length > 0) {
      const foundIndex = selectedLeadHours.value == null
        ? 0
        : forecastTimes.value.findIndex((t) => t.leadHours === selectedLeadHours.value);
      selectedLeadIndex.value = foundIndex >= 0 ? foundIndex : 0;
      selectedLeadHours.value = forecastTimes.value[selectedLeadIndex.value].leadHours;
      timelineTouched.value = false;
    } else {
      selectedLeadHours.value = null;
      selectedLeadIndex.value = 0;
      timelineTouched.value = false;
    }
  } catch (e) {
    forecastTimes.value = [];
    selectedLeadHours.value = null;
    selectedLeadIndex.value = 0;
    timelineTouched.value = false;
  }
};

const loadProvinceAnchors = async () => {
  try {
    const data = await getRegions();
    const all = Array.isArray(data) ? data : [];
    provinceAnchors.value = all
      .filter((r) => r.level === 'province' && Number.isFinite(r.lng) && Number.isFinite(r.lat))
      .map((r) => ({
        adcode: r.adcode,
        name: r.name,
        lon: Number(r.lng),
        lat: Number(r.lat),
      }));
  } catch (e) {
    provinceAnchors.value = [];
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
      minLon: bboxMinLon.value,
      minLat: bboxMinLat.value,
      maxLon: bboxMaxLon.value,
      maxLat: bboxMaxLat.value,
      points: requestPoints.value,
    });

    const rawPts = Array.isArray(data?.points) ? data.points : [];
    const srcUnit = data?.unit || '';
    const unit = selectedElementUnit.value || srcUnit;
    const pts = rawPts.map((p) => ({
      ...p,
      value: normalizeValueByElement(p.value, selectedElement.value, srcUnit),
    }));
    latestPointCount.value = pts.length;
    latestUnit.value = unit;
    let min = Number.POSITIVE_INFINITY;
    let max = Number.NEGATIVE_INFINITY;
    let sum = 0;
    let count = 0;
    for (const p of pts) {
      if (typeof p.value === 'number' && Number.isFinite(p.value)) {
        min = Math.min(min, p.value);
        max = Math.max(max, p.value);
        sum += p.value;
        count += 1;
      }
    }
    latestAvgValue.value = count > 0 ? sum / count : null;
    latestMinValue.value = count > 0 ? min : null;
    latestMaxValue.value = count > 0 ? max : null;
    if (!Number.isFinite(min) || !Number.isFinite(max) || min === max) {
      min = 0;
      max = 1;
    }

    heatmapSource.clear(true);
    if (layerMode.value === 'heatmap') {
      const features = pts.map((p) => {
        const f = new Feature({
          geometry: new Point(fromLonLat([p.lon, p.lat])),
        });
        const w = (p.value - min) / (max - min);
        f.set('weight', Math.max(0, Math.min(1, w)));
        return f;
      });
      heatmapSource.addFeatures(features);
    }

    renderProvinceNearestPoints(pts, min, max, unit);
  } catch (e) {
    heatmapSource.clear(true);
    latestPointCount.value = 0;
    latestAvgValue.value = null;
    latestMinValue.value = null;
    latestMaxValue.value = null;
    if (provinceSource) {
      provinceSource.clear(true);
    }
  }
};

const renderProvinceNearestPoints = (pts, min, max, unit) => {
  if (!provinceSource) return;
  provinceSource.clear(true);
  if (!Array.isArray(pts) || pts.length === 0 || !Array.isArray(provinceAnchors.value) || provinceAnchors.value.length === 0) {
    return;
  }

  const provinceFeatures = provinceAnchors.value.map((p) => {
    let nearest = null;
    let nearestD2 = Number.POSITIVE_INFINITY;
    for (const v of pts) {
      const dx = v.lon - p.lon;
      const dy = v.lat - p.lat;
      const d2 = dx * dx + dy * dy;
      if (d2 < nearestD2) {
        nearestD2 = d2;
        nearest = v;
      }
    }
    if (!nearest) return null;

    const weight = (nearest.value - min) / (max - min);
    const valueText = `${nearest.value.toFixed(2)}${unit ? ` ${unit}` : ''}`;
    const f = new Feature({
      geometry: new Point(fromLonLat([p.lon, p.lat])),
      kind: 'province-gfs',
      name: p.name,
      value: nearest.value,
      valueText,
      weight: Math.max(0, Math.min(1, weight)),
      srcLon: nearest.lon,
      srcLat: nearest.lat,
    });
    return f;
  }).filter(Boolean);

  provinceSource.addFeatures(provinceFeatures);
};

const colorByWeight = (weight) => {
  const w = Number.isFinite(weight) ? Math.max(0, Math.min(1, weight)) : 0;
  const r = Math.round(34 + (244 - 34) * w);
  const g = Math.round(197 - 130 * w);
  const b = Math.round(94 + (46 - 94) * w);
  return `rgba(${r}, ${g}, ${b}, 0.95)`;
};

const transparentTileDataUrl = (() => {
  let cached = null;
  return () => {
    if (cached) return cached;
    const c = document.createElement('canvas');
    c.width = 1;
    c.height = 1;
    cached = c.toDataURL('image/png');
    return cached;
  };
})();

const renderGridTileDataUrl = (tileJson) => {
  const size = Number(tileJson?.size) || 32;
  const values = Array.isArray(tileJson?.values) ? tileJson.values : [];
  const tileMin = typeof tileJson?.min === 'number' ? tileJson.min : null;
  const tileMax = typeof tileJson?.max === 'number' ? tileJson.max : null;
  const unitHint = String(tileJson?.unit || '');
  const normTileMin = tileMin == null ? null : normalizeValueByElement(tileMin, selectedElement.value, unitHint);
  const normTileMax = tileMax == null ? null : normalizeValueByElement(tileMax, selectedElement.value, unitHint);
  const useTileScale = gridColorMode.value === 'steps-tile' || gridColorMode.value === 'smooth-tile';
  const gMin = useTileScale ? normTileMin : (Number.isFinite(latestMinValue.value) ? latestMinValue.value : normTileMin);
  const gMax = useTileScale ? normTileMax : (Number.isFinite(latestMaxValue.value) ? latestMaxValue.value : normTileMax);
  const min = typeof gMin === 'number' && Number.isFinite(gMin) ? gMin : 0;
  const max = typeof gMax === 'number' && Number.isFinite(gMax) ? gMax : 1;

  const c = document.createElement('canvas');
  c.width = 256;
  c.height = 256;
  const ctx = c.getContext('2d', { willReadFrequently: true });
  const img = ctx.createImageData(256, 256);
  const data = img.data;

  for (let py = 0; py < 256; py++) {
    const gy = ((py + 0.5) / 256) * (size - 1);
    const y0 = Math.floor(gy);
    const y1 = Math.min(size - 1, y0 + 1);
    const ty = gy - y0;
    for (let px = 0; px < 256; px++) {
      const gx = ((px + 0.5) / 256) * (size - 1);
      const x0 = Math.floor(gx);
      const x1 = Math.min(size - 1, x0 + 1);
      const tx = gx - x0;

      const v00 = values[y0 * size + x0];
      const v10 = values[y0 * size + x1];
      const v01 = values[y1 * size + x0];
      const v11 = values[y1 * size + x1];

      const idx = (py * 256 + px) * 4;
      if (![v00, v10, v01, v11].every((v) => typeof v === 'number' && Number.isFinite(v))) {
        data[idx + 3] = 0;
        continue;
      }

      const a = v00 * (1 - tx) + v10 * tx;
      const b = v01 * (1 - tx) + v11 * tx;
      const raw = a * (1 - ty) + b * ty;
      const v = normalizeValueByElement(raw, selectedElement.value, unitHint);

      const stepped = gridColorMode.value === 'steps' || gridColorMode.value === 'steps-tile';
      const color = stepped ? colorForValueSteps(v, min, max) : colorForValueSmooth(v, min, max);
      data[idx] = color[0];
      data[idx + 1] = color[1];
      data[idx + 2] = color[2];
      data[idx + 3] = 255;
    }
  }

  ctx.putImageData(img, 0, 0);
  const blur = Math.max(0, Math.min(2, Number(gridBlur.value) || 0));
  if (blur > 0.01) {
    const pad = Math.max(4, Math.ceil(blur * 4));
    const w = 256 + pad * 2;
    const h = 256 + pad * 2;

    const src = document.createElement('canvas');
    src.width = w;
    src.height = h;
    const sctx = src.getContext('2d');
    sctx.drawImage(c, pad, pad);
    sctx.drawImage(c, 0, 0, 1, 256, 0, pad, pad, 256);
    sctx.drawImage(c, 255, 0, 1, 256, pad + 256, pad, pad, 256);
    sctx.drawImage(c, 0, 0, 256, 1, pad, 0, 256, pad);
    sctx.drawImage(c, 0, 255, 256, 1, pad, pad + 256, 256, pad);
    sctx.drawImage(c, 0, 0, 1, 1, 0, 0, pad, pad);
    sctx.drawImage(c, 255, 0, 1, 1, pad + 256, 0, pad, pad);
    sctx.drawImage(c, 0, 255, 1, 1, 0, pad + 256, pad, pad);
    sctx.drawImage(c, 255, 255, 1, 1, pad + 256, pad + 256, pad, pad);

    const blurred = document.createElement('canvas');
    blurred.width = w;
    blurred.height = h;
    const bctx = blurred.getContext('2d');
    bctx.filter = `blur(${blur}px)`;
    bctx.drawImage(src, 0, 0);

    const out = document.createElement('canvas');
    out.width = 256;
    out.height = 256;
    const octx = out.getContext('2d');
    octx.drawImage(blurred, pad, pad, 256, 256, 0, 0, 256, 256);
    return out.toDataURL('image/png');
  }
  return c.toDataURL('image/png');
};

const colorForValueSteps = (v, min, max) => {
  if (selectedElement.value === 'temp') {
    return tempColorSteps(v) || [0, 0, 0];
  }
  if (selectedElement.value === 'wind_u' || selectedElement.value === 'wind_v') {
    return windColorSteps(v) || [0, 0, 0];
  }
  if (selectedElement.value === 'pressure') {
    return pressureColorSteps(v) || [0, 0, 0];
  }
  const ramp = gridRamp.value;
  const t = max === min ? 0 : (v - min) / (max - min);
  const w = Math.max(0, Math.min(0.999999, t));
  const idx = Math.floor(w * ramp.length);
  return ramp[idx];
};

const colorForValueSmooth = (v, min, max) => {
  if (selectedElement.value === 'temp') {
    return tempColorSmooth(v) || [0, 0, 0];
  }
  if (selectedElement.value === 'wind_u' || selectedElement.value === 'wind_v') {
    return windColorSmooth(v) || [0, 0, 0];
  }
  if (selectedElement.value === 'pressure') {
    return pressureColorSmooth(v) || [0, 0, 0];
  }
  const ramp = gridRamp.value;
  const t = max === min ? 0 : (v - min) / (max - min);
  const w = Math.max(0, Math.min(1, t));
  const p = w * (ramp.length - 1);
  const i0 = Math.floor(p);
  const i1 = Math.min(ramp.length - 1, i0 + 1);
  const f = p - i0;
  const c0 = ramp[i0];
  const c1 = ramp[i1];
  const r = Math.round(c0[0] + (c1[0] - c0[0]) * f);
  const g = Math.round(c0[1] + (c1[1] - c0[1]) * f);
  const b = Math.round(c0[2] + (c1[2] - c0[2]) * f);
  return [r, g, b];
};

const toCelsius = (value, unitHint) => {
  if (!Number.isFinite(value)) return value;
  const u = String(unitHint || '').toLowerCase();
  // 优先根据单位提示做转换；无提示时按数值范围启发式判断
  if (u.includes('k')) return value - 273.15; // Kelvin -> Celsius
  if (u.includes('f')) return (value - 32) * 5 / 9; // Fahrenheit -> Celsius
  if (value > 170) return value - 273.15; // 常见气象温度原始值(K)
  if (value > 60) return (value - 32) * 5 / 9; // 极可能是华氏
  return value;
};

const normalizeValueByElement = (value, element, unitHint) => {
  if (!Number.isFinite(value)) return value;
  if (element === 'temp') return toCelsius(value, unitHint);
  if (element === 'pressure') {
    const u = String(unitHint || '').toLowerCase();
    if (u.includes('pa') || value > 2000) {
      return value / 100.0;
    }
    return value;
  }
  return value;
};
</script>

<template>
  <div class="map-wrapper">
    <div class="map-svg-title">
      <svg viewBox="0 0 760 100" preserveAspectRatio="none" aria-label="气象预报标题">
        <defs>
          <linearGradient id="titleGrad" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stop-color="rgba(15,23,42,0.82)" />
            <stop offset="65%" stop-color="rgba(30,41,59,0.72)" />
            <stop offset="100%" stop-color="rgba(15,23,42,0.2)" />
          </linearGradient>
        </defs>
        <rect x="0" y="0" width="760" height="100" rx="16" ry="16" fill="url(#titleGrad)" />
        <text x="38" y="38" fill="#e2e8f0" font-size="20" font-weight="600">{{ svgTitleMain }}</text>
        <text x="38" y="74" fill="#93c5fd" font-size="22" font-weight="700">{{ svgTitleSub }}</text>
        <text x="730" y="74" text-anchor="end" fill="#cbd5e1" font-size="16">当前数据量 {{ latestPointCount }} 点</text>
      </svg>
    </div>

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
          <div class="timeline-wrap">
            <input
              class="timeline-range"
              type="range"
              min="0"
              :max="Math.max(forecastTimes.length - 1, 0)"
              step="1"
              v-model.number="selectedLeadIndex"
              @input="timelineTouched = true"
              :disabled="forecastTimes.length === 0"
            />
            <div class="timeline-info">
              <span>+{{ selectedLeadHours == null ? '--' : selectedLeadHours }}h</span>
              <span>{{ selectedForecastTimeText }}</span>
            </div>
          </div>
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
          <span class="label">图层</span>
          <div class="layer-toggle">
            <button type="button" class="toggle-btn" :class="{ active: layerMode === 'heatmap' }" @click="layerMode = 'heatmap'">热力图</button>
            <button type="button" class="toggle-btn" :class="{ active: layerMode === 'grid' }" @click="layerMode = 'grid'">栅格分层</button>
          </div>
        </div>
        <div class="control-item" v-if="layerMode === 'grid'">
          <span class="label">栅格透明度</span>
          <input type="range" min="0.2" max="1" step="0.05" v-model.number="gridOpacity" />
        </div>
        <div class="control-item" v-if="layerMode === 'grid'">
          <span class="label">平滑</span>
          <input type="range" min="0" max="2" step="0.1" v-model.number="gridBlur" />
        </div>
        <div class="control-item" v-if="layerMode === 'grid'">
          <span class="label">色带</span>
          <div class="layer-toggle">
            <button type="button" class="toggle-btn" :class="{ active: gridColorMode === 'smooth' }" @click="gridColorMode = 'smooth'">渐变</button>
            <button type="button" class="toggle-btn" :class="{ active: gridColorMode === 'steps' }" @click="gridColorMode = 'steps'">分层</button>
          </div>
        </div>
        <div class="control-item" v-if="layerMode === 'grid'">
          <span class="label">随zoom细化</span>
          <input type="checkbox" v-model="gridAutoDetail" />
        </div>
        <div class="control-item">
          <span class="label">请求点数</span>
          <input class="control-input" type="number" min="100" max="5000" step="100" v-model.number="requestPoints" />
        </div>
        <div class="control-item control-grid">
          <span class="label">区域 bbox</span>
          <div class="bbox-grid">
            <input class="control-input" type="number" step="0.1" v-model.number="bboxMinLon" placeholder="minLon" />
            <input class="control-input" type="number" step="0.1" v-model.number="bboxMinLat" placeholder="minLat" />
            <input class="control-input" type="number" step="0.1" v-model.number="bboxMaxLon" placeholder="maxLon" />
            <input class="control-input" type="number" step="0.1" v-model.number="bboxMaxLat" placeholder="maxLat" />
          </div>
        </div>
        <div class="control-item">
          <span class="label">省份标注</span>
          <input type="checkbox" v-model="showProvinceLabels" />
          <span class="label">点径</span>
          <input type="range" min="4" max="12" step="1" v-model.number="provinceMarkerSize" />
        </div>
        <div class="stats-card">
          <div>当前数据量：{{ latestPointCount }} 点</div>
          <div>平均值：{{ latestAvgValue == null ? '--' : latestAvgValue.toFixed(2) }}{{ latestUnit ? ` ${latestUnit}` : '' }}</div>
          <div>最小值：{{ latestMinValue == null ? '--' : latestMinValue.toFixed(2) }}{{ latestUnit ? ` ${latestUnit}` : '' }}</div>
          <div>最大值：{{ latestMaxValue == null ? '--' : latestMaxValue.toFixed(2) }}{{ latestUnit ? ` ${latestUnit}` : '' }}</div>
        </div>
      </div>
    </div>
    <div class="map-legend" v-if="layerMode === 'grid' && legendLevels.length">
      <div class="legend-title">{{ selectedElementLabel }} {{ selectedElementUnit ? `(${selectedElementUnit})` : '' }}</div>
      <div class="legend-items">
        <div class="legend-item" v-for="(it, idx) in legendLevels" :key="idx">
          <span class="legend-swatch" :style="{ background: it.color }"></span>
          <span class="legend-text">{{ it.label }}</span>
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
.map-svg-title {
  position: absolute;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 1150;
  width: min(860px, calc(100% - 380px));
  min-width: 420px;
  pointer-events: none;
}
.map-svg-title svg {
  width: 100%;
  height: 100px;
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
.map-legend {
  position: absolute;
  left: 14px;
  bottom: 14px;
  z-index: 1200;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(12, 16, 30, 0.58);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  box-shadow: 0 10px 20px rgba(0, 0, 0, 0.35);
  padding: 10px 12px;
  color: rgba(226, 232, 240, 0.9);
  min-width: 180px;
}
.legend-title {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 8px;
  color: rgba(226, 232, 240, 0.92);
}
.legend-items {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 240px;
  overflow: auto;
}
.legend-item {
  display: grid;
  grid-template-columns: 16px 1fr;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: rgba(203, 213, 225, 0.9);
}
.legend-swatch {
  width: 16px;
  height: 10px;
  border-radius: 4px;
  border: 1px solid rgba(255, 255, 255, 0.18);
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
.control-item input[type="checkbox"] {
  accent-color: #38bdf8;
}
.timeline-wrap {
  width: 220px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.timeline-range {
  width: 100%;
}
.timeline-info {
  display: flex;
  justify-content: space-between;
  color: rgba(203, 213, 225, 0.88);
  font-size: 11px;
  gap: 8px;
}
.layer-toggle {
  display: flex;
  gap: 8px;
}
.toggle-btn {
  appearance: none;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(0, 0, 0, 0.22);
  color: rgba(255, 255, 255, 0.88);
  padding: 6px 10px;
  border-radius: 10px;
  cursor: pointer;
  font-size: 12px;
  line-height: 1;
}
.toggle-btn.active {
  border-color: rgba(56, 189, 248, 0.55);
  background: rgba(56, 189, 248, 0.18);
  color: rgba(226, 232, 240, 0.95);
}
.control-input {
  width: 96px;
  appearance: none;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(0, 0, 0, 0.22);
  color: rgba(255, 255, 255, 0.9);
  padding: 6px 8px;
  border-radius: 8px;
  outline: none;
}
.control-grid {
  align-items: flex-start;
}
.bbox-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 6px;
}
.stats-card {
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(2, 6, 23, 0.35);
  border-radius: 10px;
  padding: 8px 10px;
  color: rgba(226, 232, 240, 0.85);
  font-size: 12px;
  line-height: 1.6;
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
