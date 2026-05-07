<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import 'leaflet-draw/dist/leaflet.draw.css'
import 'leaflet-draw'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { fromArrayBuffer } from 'geotiff'
import * as turf from '@turf/turf'

const emit = defineEmits(['go-home'])

const step = ref('select') // select | preview
const apiKey = ref(localStorage.getItem('opentopo_apikey') || '')
const demSource = ref('SRTMGL1')
const drawMode = ref('rectangle') // rectangle | circle | polygon
const maxArea = ref(100)
const isDownloading = ref(false)
const isProcessing = ref(false)
const downloadProgress = ref(0)
const errorMessage = ref('')
const backendOnline = ref(false)
const backendChecking = ref(true)
const perfInfo = ref({ downloadMs: null, parseMs: null, renderMs: null, memMb: null })
const legend = ref({ min: null, max: null, visible: false })
const legendTab = ref('basic')
const legendHoverText = ref('')
const surfaceLayer = ref('elevation')
const layerVisible = reactive({
  contour: true,
  flow: false,
  watershed: false,
  ridge: false,
  valley: false,
  relief: false,
  roughness: false,
  twi: false,
})

const mapContainer = ref(null)
const threeCanvas = ref(null)
const heightScale = ref(2.2)

const selected = ref({
  kind: null,
  bbox: null,
  areaKm2: 0,
  center: null,
  coordsText: '--',
})

let map = null
let drawItems = null
let drawControl = null
let selectedLayer = null
let geoJsonLayer = null
let demPreviewOverlay = null
let backendTimer = null
let scene = null
let camera = null
let renderer = null
let controls = null
let terrainMesh = null
let basePlane = null
let animationId = null
let demCache = null

const surfaceLayers = ['elevation', 'slope', 'aspect']
const legendTabs = [
  { id: 'basic', label: '基础地形要素' },
  { id: 'hydro', label: '水文要素' },
  { id: 'derived', label: '地形衍生要素' },
]
const legendItems = [
  { id: 'elevation', tab: 'basic', label: '高程值', type: 'surface', detail: '显示 DEM 原始高程分布，单位 m。' },
  { id: 'slope', tab: 'basic', label: '坡度', type: 'surface', detail: '由高程梯度推导，表示地形倾斜强弱（°）。' },
  { id: 'aspect', tab: 'basic', label: '坡向', type: 'surface', detail: '由坡面法线推导，表示坡面朝向（0°-360°）。' },
  { id: 'contour', tab: 'basic', label: '等高线', type: 'overlay', detail: '按高程等值间隔绘制，辅助识别地形起伏。' },
  { id: 'flow', tab: 'hydro', label: '汇流路径', type: 'overlay', detail: '基于坡度与低势区估算的地表汇流通道。' },
  { id: 'watershed', tab: 'hydro', label: '流域边界', type: 'overlay', detail: '依据局部地形分异估算的分水界区域。' },
  { id: 'valley', tab: 'hydro', label: '山谷线', type: 'overlay', detail: '依据地形曲率判定的凹地带线状特征。' },
  { id: 'ridge', tab: 'derived', label: '山脊线', type: 'overlay', detail: '依据地形曲率判定的凸地带线状特征。' },
  { id: 'relief', tab: 'derived', label: '地形起伏度', type: 'overlay', detail: '邻域内高差范围，反映地形起伏强度。' },
  { id: 'roughness', tab: 'derived', label: '地表粗糙度', type: 'overlay', detail: '邻域高程离散度，反映表面粗糙程度。' },
  { id: 'twi', tab: 'derived', label: '地形湿度指数', type: 'overlay', detail: '基于坡度与汇流势估算地表积水倾向。' },
]
const visibleLegendItems = computed(() => legendItems.filter((x) => x.tab === legendTab.value))
const slopeClasses = [
  { min: 0, max: 5, label: '0-5°（平缓）', color: '#1d4ed8' },
  { min: 5, max: 15, label: '5-15°（缓坡）', color: '#16a34a' },
  { min: 15, max: 30, label: '15-30°（中坡）', color: '#f59e0b' },
  { min: 30, max: Number.POSITIVE_INFINITY, label: '>30°（陡坡）', color: '#dc2626' },
]
const aspectClasses = [
  { label: '北（337.5-22.5°）', color: '#ef4444' },
  { label: '东北（22.5-67.5°）', color: '#f97316' },
  { label: '东（67.5-112.5°）', color: '#eab308' },
  { label: '东南（112.5-157.5°）', color: '#22c55e' },
  { label: '南（157.5-202.5°）', color: '#14b8a6' },
  { label: '西南（202.5-247.5°）', color: '#3b82f6' },
  { label: '西（247.5-292.5°）', color: '#6366f1' },
  { label: '西北（292.5-337.5°）', color: '#a855f7' },
]
const twiClasses = [
  { min: Number.NEGATIVE_INFINITY, max: 4, label: '<4（干燥）', color: '#f59e0b' },
  { min: 4, max: 6, label: '4-6（偏干）', color: '#84cc16' },
  { min: 6, max: 8, label: '6-8（偏湿）', color: '#38bdf8' },
  { min: 8, max: Number.POSITIVE_INFINITY, label: '>8（湿润）', color: '#1d4ed8' },
]
const thematicLegendSections = computed(() => {
  const sections = []
  if (surfaceLayer.value === 'slope') {
    sections.push({ title: '坡度分级', entries: slopeClasses })
  } else if (surfaceLayer.value === 'aspect') {
    sections.push({ title: '坡向分级', entries: aspectClasses })
  }
  if (layerVisible.twi) {
    sections.push({ title: '地形湿度指数分级', entries: twiClasses })
  }
  return sections
})

const canDownload = computed(() => (
  backendOnline.value
  && !!apiKey.value
  && selected.value.bbox
  && selected.value.center
  && selected.value.areaKm2 > 0
  && selected.value.areaKm2 <= maxArea.value
))

const areaUsagePercent = computed(() => {
  if (!Number.isFinite(selected.value.areaKm2) || !Number.isFinite(maxArea.value) || maxArea.value <= 0) return 0
  return Math.max(0, Math.min(100, (selected.value.areaKm2 / maxArea.value) * 100))
})

const areaExceeded = computed(() => selected.value.areaKm2 > maxArea.value)

watch(apiKey, (v) => localStorage.setItem('opentopo_apikey', v || ''))
watch(heightScale, (val) => {
  if (terrainMesh) terrainMesh.scale.z = val / 2.2
})
watch(surfaceLayer, () => refreshTerrainVisual())
watch(layerVisible, () => refreshTerrainVisual(), { deep: true })

// 兼容修复：leaflet-draw 在部分构建下 readableArea 内部变量 type 未定义导致崩溃
if (L?.GeometryUtil?.readableArea) {
  const originalReadableArea = L.GeometryUtil.readableArea.bind(L.GeometryUtil)
  L.GeometryUtil.readableArea = (area, isMetric, precision) => {
    try {
      return originalReadableArea(area, isMetric, precision)
    } catch (e) {
      const km2 = area / 1e6
      if (!isMetric) {
        const mi2 = area / 2589988.110336
        return `${mi2.toFixed(2)} mi²`
      }
      return km2 >= 1 ? `${km2.toFixed(2)} km²` : `${area.toFixed(2)} m²`
    }
  }
}

const showError = (msg) => {
  errorMessage.value = msg || ''
  if (msg) setTimeout(() => { errorMessage.value = '' }, 5000)
}

const setLegendItem = (item) => {
  if (item.type === 'surface') {
    surfaceLayer.value = item.id
    return
  }
  layerVisible[item.id] = !layerVisible[item.id]
}

const isItemEnabled = (item) => {
  if (item.type === 'surface') return surfaceLayer.value === item.id
  return !!layerVisible[item.id]
}

const showLegendDetail = (item) => {
  legendHoverText.value = `${item.label}: ${item.detail}`
}

const parseHexColor = (hex) => {
  const c = new THREE.Color(hex)
  return [Math.round(c.r * 255), Math.round(c.g * 255), Math.round(c.b * 255)]
}

const classColor = (value, classes) => {
  for (let i = 0; i < classes.length; i++) {
    const c = classes[i]
    if (value >= c.min && value < c.max) return parseHexColor(c.color)
  }
  return parseHexColor(classes[classes.length - 1].color)
}

const aspectClassIndex = (deg) => Math.floor(((((deg % 360) + 360) % 360) + 22.5) / 45) % 8

const updatePerfMem = () => {
  const pm = performance && performance.memory ? performance.memory : null
  if (pm && typeof pm.usedJSHeapSize === 'number') {
    perfInfo.value.memMb = Math.round((pm.usedJSHeapSize / 1024 / 1024) * 10) / 10
  }
}

const initMap = () => {
  map = L.map(mapContainer.value).setView([30, 104], 4)

  const osm = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap contributors',
  })
  const sat = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
    attribution: 'Tiles &copy; Esri',
  })
  osm.addTo(map)
  L.control.layers({ 街道: osm, 卫星: sat }).addTo(map)

  drawItems = new L.FeatureGroup().addTo(map)
  geoJsonLayer = L.geoJSON(null, { style: { color: '#38bdf8', weight: 2, fillOpacity: 0.08 } }).addTo(map)

  drawControl = new L.Control.Draw({
    edit: { featureGroup: drawItems, remove: true },
    draw: {
      polyline: false,
      marker: false,
      circlemarker: false,
      rectangle: {
        showArea: false,
      },
      circle: true,
      polygon: {
        showArea: false,
      },
    },
  })
  map.addControl(drawControl)

  map.on(L.Draw.Event.CREATED, (e) => {
    drawItems.clearLayers()
    selectedLayer = e.layer
    drawItems.addLayer(selectedLayer)
    applySelectionFromLayer(selectedLayer, e.layerType)
  })

  map.on(L.Draw.Event.EDITED, () => {
    if (selectedLayer) applySelectionFromLayer(selectedLayer, selected.value.kind || 'polygon')
  })

  map.on(L.Draw.Event.DELETED, () => {
    selectedLayer = null
    selected.value = { kind: null, bbox: null, areaKm2: 0, center: null, coordsText: '--' }
  })
}

const startDrawing = () => {
  if (!map) return
  let drawer = null
  if (drawMode.value === 'rectangle') drawer = new L.Draw.Rectangle(map, { showArea: false })
  else if (drawMode.value === 'circle') drawer = new L.Draw.Circle(map)
  else drawer = new L.Draw.Polygon(map, { showArea: false })
  drawer.enable()
}

const applySelectionFromLayer = (layer, kind) => {
  let bbox = null
  let area = 0
  let center = null
  if (!layer) return

  if (kind === 'circle' || layer instanceof L.Circle) {
    const c = layer.getLatLng()
    const r = layer.getRadius()
    center = { lat: c.lat, lng: c.lng }
    area = (Math.PI * r * r) / 1e6
    const b = layer.getBounds()
    bbox = { minX: b.getWest(), minY: b.getSouth(), maxX: b.getEast(), maxY: b.getNorth() }
  } else {
    const gj = layer.toGeoJSON()
    area = turf.area(gj) / 1e6
    const b = turf.bbox(gj)
    bbox = { minX: b[0], minY: b[1], maxX: b[2], maxY: b[3] }
    const cb = L.latLngBounds([bbox.minY, bbox.minX], [bbox.maxY, bbox.maxX]).getCenter()
    center = { lat: cb.lat, lng: cb.lng }
  }

  selected.value = {
    kind,
    bbox,
    areaKm2: area,
    center,
    coordsText: bbox
      ? `minX=${bbox.minX.toFixed(6)}, minY=${bbox.minY.toFixed(6)}, maxX=${bbox.maxX.toFixed(6)}, maxY=${bbox.maxY.toFixed(6)}`
      : '--',
  }
}

const handleGeoJsonUpload = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  try {
    const text = await file.text()
    const gj = JSON.parse(text)
    const b = turf.bbox(gj)
    if (!Array.isArray(b) || b.length !== 4 || !Number.isFinite(b[0])) throw new Error('empty')
    geoJsonLayer.clearLayers()
    geoJsonLayer.addData(gj)
    drawItems.clearLayers()
    selectedLayer = L.rectangle([[b[1], b[0]], [b[3], b[2]]], { color: '#ff7800', weight: 2 })
    drawItems.addLayer(selectedLayer)
    applySelectionFromLayer(selectedLayer, 'polygon')
    flyToSelection()
  } catch (_) {
    showError('GeoJSON 文件不合法或为空几何')
  }
}

const flyToSelection = () => {
  if (!map || !selected.value.bbox) return
  const b = selected.value.bbox
  const bounds = L.latLngBounds([b.minY, b.minX], [b.maxY, b.maxX]).pad(0.08)
  map.flyToBounds(bounds, { padding: [22, 22], maxZoom: 16, animate: true, duration: 1.1 })
}

const checkBackendOnce = async () => {
  const controller = new AbortController()
  const t = setTimeout(() => controller.abort(), 1500)
  try {
    const r = await fetch('/api/dem/ping', { signal: controller.signal })
    backendOnline.value = r.ok
  } catch (_) {
    backendOnline.value = false
  } finally {
    clearTimeout(t)
    backendChecking.value = false
  }
}

const startBackendProbe = async () => {
  backendChecking.value = true
  await checkBackendOnce()
  if (!backendOnline.value) {
    showError('后端服务不可用')
    backendTimer = setInterval(checkBackendOnce, 3000)
  }
}

const isTiffArrayBuffer = (arrayBuffer) => {
  const bytes = new Uint8Array(arrayBuffer)
  if (bytes.length < 4) return false
  const leClassic = bytes[0] === 0x49 && bytes[1] === 0x49 && bytes[2] === 0x2a && bytes[3] === 0x00
  const beClassic = bytes[0] === 0x4d && bytes[1] === 0x4d && bytes[2] === 0x00 && bytes[3] === 0x2a
  const leBig = bytes[0] === 0x49 && bytes[1] === 0x49 && bytes[2] === 0x2b && bytes[3] === 0x00
  const beBig = bytes[0] === 0x4d && bytes[1] === 0x4d && bytes[2] === 0x00 && bytes[3] === 0x2b
  return leClassic || beClassic || leBig || beBig
}

const parseGeoTiff = async (arrayBuffer) => {
  const t0 = performance.now()
  const tiff = await fromArrayBuffer(arrayBuffer)
  const image = await tiff.getImage()
  const width = image.getWidth()
  const height = image.getHeight()
  const noDataRaw = image.getGDALNoData?.() ?? image.fileDirectory?.GDAL_NODATA ?? null
  const noData = noDataRaw == null ? null : Number(noDataRaw)
  const samples = typeof image.getSamplesPerPixel === 'function' ? image.getSamplesPerPixel() : (image.fileDirectory?.SamplesPerPixel ?? 1)

  let elev = null
  try {
    elev = await image.readRasters({ interleave: true, samples: [0] })
  } catch (_) {
    const all = await image.readRasters({ interleave: true })
    if (samples > 1) {
      const out = new Float32Array(width * height)
      for (let i = 0; i < width * height; i++) out[i] = all[i * samples]
      elev = out
    } else {
      elev = all
    }
  }
  perfInfo.value.parseMs = Math.round(performance.now() - t0)
  console.info('[DEM] GeoTIFF parsed', { width, height, samplesPerPixel: samples, noData, rasterLength: elev.length })
  return { width, height, elev, noData }
}

const buildGrayCanvas = (elev, width, height, noData) => {
  const c = document.createElement('canvas')
  c.width = width
  c.height = height
  const ctx = c.getContext('2d', { willReadFrequently: true })
  const img = ctx.createImageData(width, height)
  const d = img.data
  let min = Infinity; let max = -Infinity
  const n = width * height
  for (let i = 0; i < n; i++) {
    const v = elev[i]
    const nod = (noData != null && v === noData) || v === -32768 || !Number.isFinite(v)
    if (nod) continue
    if (v < min) min = v
    if (v > max) max = v
  }
  const denom = max === min ? 1 : (max - min)
  for (let i = 0; i < n; i++) {
    let v = elev[i]
    const nod = (noData != null && v === noData) || v === -32768 || !Number.isFinite(v)
    if (nod) v = min
    const g = Math.max(0, Math.min(255, Math.round(((v - min) / denom) * 255)))
    const x = i % width
    const y = Math.floor(i / width)
    const idx = ((height - 1 - y) * width + x) * 4
    d[idx] = g
    d[idx + 1] = g
    d[idx + 2] = g
    d[idx + 3] = 255
  }
  ctx.putImageData(img, 0, 0)
  return c
}

const toColorBySurface = (t, mode, aspectDeg = 0) => {
  if (mode === 'slope') {
    const slopeDeg = t * 90
    return classColor(slopeDeg, slopeClasses)
  }
  if (mode === 'aspect') {
    const idx = aspectClassIndex(aspectDeg)
    return parseHexColor(aspectClasses[idx].color)
  }
  const r = Math.round(25 + t * 220)
  const g = Math.round(90 + t * 140)
  const b = Math.round(210 - t * 150)
  return [r, g, b]
}

const clamp255 = (v) => Math.max(0, Math.min(255, Math.round(v)))

const buildDerivedCache = (elev, width, height, noData) => {
  const slope = new Float32Array(width * height)
  const aspect = new Float32Array(width * height)
  const roughness = new Float32Array(width * height)
  const relief = new Float32Array(width * height)
  const twi = new Float32Array(width * height)
  const ridgeMask = new Uint8Array(width * height)
  const valleyMask = new Uint8Array(width * height)
  const flowMask = new Uint8Array(width * height)
  const watershedMask = new Uint8Array(width * height)

  const idx = (x, y) => y * width + x
  const getV = (x, y) => {
    const xx = Math.max(0, Math.min(width - 1, x))
    const yy = Math.max(0, Math.min(height - 1, y))
    let v = elev[idx(xx, yy)]
    const nod = (noData != null && v === noData) || v === -32768 || !Number.isFinite(v)
    if (nod) v = elev[idx(Math.max(0, Math.min(width - 1, x - 1)), Math.max(0, Math.min(height - 1, y - 1)))] || 0
    return v
  }

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = idx(x, y)
      const c = getV(x, y)
      const zx1 = getV(x + 1, y)
      const zx0 = getV(x - 1, y)
      const zy1 = getV(x, y + 1)
      const zy0 = getV(x, y - 1)
      const dzdx = (zx1 - zx0) * 0.5
      const dzdy = (zy1 - zy0) * 0.5
      const grad = Math.sqrt(dzdx * dzdx + dzdy * dzdy)
      const slopeDeg = Math.atan(grad) * 57.2958
      slope[i] = slopeDeg
      const asp = (Math.atan2(dzdy, -dzdx) * 57.2958 + 360) % 360
      aspect[i] = asp

      let localMin = Infinity
      let localMax = -Infinity
      let sum = 0
      let sum2 = 0
      let n = 0
      for (let oy = -1; oy <= 1; oy++) {
        for (let ox = -1; ox <= 1; ox++) {
          const vv = getV(x + ox, y + oy)
          localMin = Math.min(localMin, vv)
          localMax = Math.max(localMax, vv)
          sum += vv
          sum2 += vv * vv
          n++
        }
      }
      relief[i] = localMax - localMin
      const mean = sum / n
      roughness[i] = Math.sqrt(Math.max(0, sum2 / n - mean * mean))
      twi[i] = Math.log((1 + relief[i]) / (Math.tan(Math.max(0.01, slopeDeg * Math.PI / 180))))

      const lap = (zx1 + zx0 + zy1 + zy0 - 4 * c)
      if (lap < -1.2) ridgeMask[i] = 1
      if (lap > 1.2) valleyMask[i] = 1
      if (slopeDeg > 16 && valleyMask[i]) flowMask[i] = 1
      if (slopeDeg < 8 && Math.abs(lap) > 1.5) watershedMask[i] = 1
    }
  }

  return { slope, aspect, roughness, relief, twi, ridgeMask, valleyMask, flowMask, watershedMask }
}

const statsFromArray = (arr) => {
  let min = Infinity
  let max = -Infinity
  for (let i = 0; i < arr.length; i++) {
    const v = arr[i]
    if (!Number.isFinite(v)) continue
    if (v < min) min = v
    if (v > max) max = v
  }
  if (!Number.isFinite(min) || !Number.isFinite(max)) return { min: 0, max: 1, span: 1 }
  return { min, max, span: Math.max(1e-6, max - min) }
}

const refreshTerrainVisual = () => {
  if (!terrainMesh || !demCache) return
  const { width, height, elevation, aspect, slope, relief, roughness, twi, stats } = demCache
  let source = elevation
  if (surfaceLayer.value === 'slope') source = slope
  if (surfaceLayer.value === 'aspect') source = aspect
  const st = surfaceLayer.value === 'elevation' ? stats.elevation : surfaceLayer.value === 'slope' ? stats.slope : stats.aspect
  legend.value = { min: st.min, max: st.max, visible: true }
  const colorData = new Uint8Array(width * height * 4)

  for (let i = 0; i < width * height; i++) {
    const t = (source[i] - st.min) / st.span
    const [r0, g0, b0] = toColorBySurface(t, surfaceLayer.value, aspect[i])
    let r = r0; let g = g0; let b = b0

    if (layerVisible.relief) {
      const tr = (relief[i] - stats.relief.min) / stats.relief.span
      r = clamp255(r * 0.6 + tr * 200)
      g = clamp255(g * 0.65 + tr * 120)
      b = clamp255(b * 0.7 + tr * 60)
    }
    if (layerVisible.roughness) {
      const trf = (roughness[i] - stats.roughness.min) / stats.roughness.span
      r = clamp255(r + trf * 35)
      g = clamp255(g + trf * 35)
      b = clamp255(b + trf * 35)
    }
    if (layerVisible.twi) {
      const [tr, tg, tb] = classColor(twi[i], twiClasses)
      r = clamp255(r * 0.52 + tr * 0.48)
      g = clamp255(g * 0.52 + tg * 0.48)
      b = clamp255(b * 0.52 + tb * 0.48)
    }
    if (layerVisible.contour) {
      const te = (elevation[i] - stats.elevation.min) / stats.elevation.span
      const frac = (te * 24) % 1
      if (frac < 0.06 || frac > 0.94) {
        r = clamp255(r * 0.25)
        g = clamp255(g * 0.25)
        b = clamp255(b * 0.25)
      }
    }
    if (layerVisible.ridge && demCache.ridgeMask[i]) { r = 244; g = 63; b = 94 }
    if (layerVisible.valley && demCache.valleyMask[i]) { r = 34; g = 211; b = 238 }
    if (layerVisible.flow && demCache.flowMask[i]) { r = 56; g = 189; b = 248 }
    if (layerVisible.watershed && demCache.watershedMask[i]) { r = 251; g = 191; b = 36 }

    const x = i % width
    const y = Math.floor(i / width)
    const idxTex = ((height - 1 - y) * width + x) * 4
    colorData[idxTex] = r
    colorData[idxTex + 1] = g
    colorData[idxTex + 2] = b
    colorData[idxTex + 3] = 255
  }

  const tex = new THREE.DataTexture(colorData, width, height, THREE.RGBAFormat)
  tex.needsUpdate = true
  tex.flipY = false
  const mat = terrainMesh.material
  if (mat.map) mat.map.dispose()
  mat.map = tex
  mat.needsUpdate = true
}

const ensureThree = async () => {
  if (renderer) return
  if (!threeCanvas.value) return
  scene = new THREE.Scene()
  scene.background = new THREE.Color(0x070d18)

  camera = new THREE.PerspectiveCamera(50, 1, 0.1, 12000)
  camera.position.set(0, -320, 220)
  camera.up.set(0, 0, 1)

  renderer = new THREE.WebGLRenderer({ canvas: threeCanvas.value, antialias: true, alpha: true })
  renderer.setPixelRatio(window.devicePixelRatio)
  resizeThree()

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.target.set(0, 0, 20)

  scene.add(new THREE.AmbientLight(0xffffff, 0.55))
  const light = new THREE.DirectionalLight(0xffffff, 0.95)
  light.position.set(220, -180, 260)
  scene.add(light)

  const grid = new THREE.GridHelper(300, 20, 0x4b5563, 0x1f2937)
  grid.rotation.x = Math.PI / 2
  grid.position.z = -0.5
  scene.add(grid)

  basePlane = new THREE.Mesh(
    new THREE.PlaneGeometry(260, 260),
    new THREE.MeshStandardMaterial({ color: 0x101826, roughness: 0.95, metalness: 0.02, side: THREE.DoubleSide }),
  )
  basePlane.position.z = -1
  scene.add(basePlane)

  const loop = () => {
    animationId = requestAnimationFrame(loop)
    controls?.update()
    renderer?.render(scene, camera)
  }
  loop()
}

const resizeThree = () => {
  if (!renderer || !camera || !threeCanvas.value) return
  const w = threeCanvas.value.clientWidth
  const h = threeCanvas.value.clientHeight
  if (!w || !h) return
  renderer.setSize(w, h)
  camera.aspect = w / h
  camera.updateProjectionMatrix()
}

const resampleNearest = (src, sw, sh, tw, th) => {
  const out = new Float32Array(tw * th)
  for (let y = 0; y < th; y++) {
    const sy = Math.min(sh - 1, Math.floor((y / Math.max(1, th - 1)) * (sh - 1)))
    for (let x = 0; x < tw; x++) {
      const sx = Math.min(sw - 1, Math.floor((x / Math.max(1, tw - 1)) * (sw - 1)))
      out[y * tw + x] = src[sy * sw + sx]
    }
  }
  return out
}

const fitThreeCamera = () => {
  if (!terrainMesh || !camera || !controls) return
  const box = new THREE.Box3().setFromObject(terrainMesh)
  const size = box.getSize(new THREE.Vector3())
  const center = box.getCenter(new THREE.Vector3())
  const maxDim = Math.max(size.x, size.y, size.z)
  const fov = THREE.MathUtils.degToRad(camera.fov)
  let dist = (maxDim / 2) / Math.tan(fov / 2)
  dist *= 1.75
  camera.position.set(center.x + dist * 0.72, center.y - dist * 1.2, center.z + dist * 0.9)
  camera.near = Math.max(0.1, dist / 300)
  camera.far = Math.max(3000, dist * 40)
  camera.updateProjectionMatrix()
  controls.target.copy(center)
  controls.update()
}

const showInThree = async ({ elev, width, height, noData }) => {
  await ensureThree()
  if (!scene) return
  const t0 = performance.now()

  if (terrainMesh) {
    scene.remove(terrainMesh)
    terrainMesh.geometry.dispose()
    terrainMesh.material.dispose()
    terrainMesh = null
  }

  let min = Infinity
  let max = -Infinity
  for (let i = 0; i < elev.length; i++) {
    const val = elev[i]
    const nod = (noData != null && val === noData) || val === -32768 || !Number.isFinite(val)
    if (nod) continue
    if (val < min) min = val
    if (val > max) max = val
  }
  if (!Number.isFinite(min) || !Number.isFinite(max)) {
    legend.value = { min: null, max: null, visible: false }
    throw new Error('DEM 无有效高程值')
  }

  let rw = width
  let rh = height
  let re = elev
  const maxSide = 220
  if (rw > maxSide || rh > maxSide) {
    const scale = Math.min(maxSide / rw, maxSide / rh)
    const tw = Math.max(2, Math.floor(rw * scale))
    const th = Math.max(2, Math.floor(rh * scale))
    re = resampleNearest(elev, rw, rh, tw, th)
    rw = tw
    rh = th
  }

  const relief = Math.max(45, Math.min(220, (max - min) * heightScale.value))
  const geom = new THREE.PlaneGeometry(240, 240 * (rh / rw), rw - 1, rh - 1)
  const pos = geom.attributes.position.array
  const denom = Math.max(1, max - min)

  for (let i = 0; i < rw * rh; i++) {
    let v = re[i]
    const nod = (noData != null && v === noData) || v === -32768 || !Number.isFinite(v)
    if (nod) v = min
    const t = (v - min) / denom
    pos[i * 3 + 2] = t * relief
  }
  geom.computeVertexNormals()

  const mat = new THREE.MeshStandardMaterial({
    map: null,
    roughness: 0.9,
    metalness: 0.03,
    side: THREE.DoubleSide,
  })
  terrainMesh = new THREE.Mesh(geom, mat)
  terrainMesh.scale.z = heightScale.value / 2.2
  scene.add(terrainMesh)

  const derived = buildDerivedCache(re, rw, rh, noData)
  demCache = {
    width: rw,
    height: rh,
    noData,
    elevation: re,
    ...derived,
    stats: {
      elevation: statsFromArray(re),
      slope: statsFromArray(derived.slope),
      aspect: { min: 0, max: 360, span: 360 },
      relief: statsFromArray(derived.relief),
      roughness: statsFromArray(derived.roughness),
      twi: statsFromArray(derived.twi),
    },
  }
  refreshTerrainVisual()
  fitThreeCamera()
  perfInfo.value.renderMs = Math.round(performance.now() - t0)
}

const runDownloadFlow = async (url, method, payload) => {
  isDownloading.value = true
  isProcessing.value = true
  downloadProgress.value = 10
  showError('')
  perfInfo.value = { downloadMs: null, parseMs: null, renderMs: null, memMb: perfInfo.value.memMb }
  try {
    const t0 = performance.now()
    const resp = await fetch(url, {
      method,
      headers: payload ? { 'Content-Type': 'application/json' } : undefined,
      body: payload ? JSON.stringify(payload) : undefined,
    })
    if (!resp.ok) {
      const txt = await resp.text().catch(() => '')
      throw new Error(`HTTP ${resp.status}${txt ? `: ${txt.slice(0, 500)}` : ''}`)
    }
    downloadProgress.value = 45
    const buf = await resp.arrayBuffer()
    perfInfo.value.downloadMs = Math.round(performance.now() - t0)
    downloadProgress.value = 65
    if (!isTiffArrayBuffer(buf)) throw new Error('返回内容不是 GeoTIFF')
    const parsed = await parseGeoTiff(buf)
    downloadProgress.value = 80
    step.value = 'preview'
    await nextTick()
    await showInThree(parsed)
    updatePerfMem()
    downloadProgress.value = 100
  } catch (e) {
    console.error(e)
    showError(`处理失败：${e.message}`)
  } finally {
    setTimeout(() => {
      isDownloading.value = false
      isProcessing.value = false
    }, 400)
  }
}

const downloadAndPreview = async () => {
  if (!canDownload.value) {
    showError('请选择有效区域并确认 API Key/面积限制')
    return
  }
  await runDownloadFlow('/api/dem/download', 'POST', {
    demtype: demSource.value,
    south: selected.value.bbox.minY,
    north: selected.value.bbox.maxY,
    west: selected.value.bbox.minX,
    east: selected.value.bbox.maxX,
    apiKey: apiKey.value,
  })
}

const loadMockAndPreview = async () => {
  await runDownloadFlow('/api/dem/mock', 'GET')
}

const backStep = () => {
  if (step.value === 'preview') {
    step.value = 'select'
    nextTick(() => map && map.invalidateSize())
    return
  }
  if (selected.value.bbox || selectedLayer || (drawItems && drawItems.getLayers().length > 0)) {
    selected.value = { kind: null, bbox: null, areaKm2: 0, center: null, coordsText: '--' }
    drawItems?.clearLayers()
    geoJsonLayer?.clearLayers()
    legend.value = { min: null, max: null, visible: false }
    legendHoverText.value = ''
    demCache = null
    showError('已回退到当前页面初始状态')
    return
  }
  showError('已在当前页面起始步骤')
}

const resetAndGoHome = () => {
  step.value = 'select'
  selected.value = { kind: null, bbox: null, areaKm2: 0, center: null, coordsText: '--' }
  drawItems?.clearLayers()
  geoJsonLayer?.clearLayers()
  if (demPreviewOverlay && map) {
    map.removeLayer(demPreviewOverlay)
    demPreviewOverlay = null
  }
  legend.value = { min: null, max: null, visible: false }
  legendHoverText.value = ''
  demCache = null
  emit('go-home')
}

onMounted(async () => {
  initMap()
  await startBackendProbe()
  window.addEventListener('resize', resizeThree)
})

onUnmounted(() => {
  if (backendTimer) clearInterval(backendTimer)
  if (map) map.remove()
  if (animationId) cancelAnimationFrame(animationId)
  if (terrainMesh) {
    terrainMesh.geometry.dispose()
    terrainMesh.material.dispose()
    terrainMesh = null
  }
  if (renderer) {
    renderer.dispose()
    renderer = null
  }
  demCache = null
  window.removeEventListener('resize', resizeThree)
})
</script>

<template>
  <div class="dem-flow">
    <header class="flow-nav">
      <div class="flow-title">地理数据下载与三维预览流程</div>
      <div class="flow-actions">
        <button type="button" class="nav-btn" @click="backStep">返回上一步</button>
        <button type="button" class="nav-btn" @click="resetAndGoHome">返回主页</button>
      </div>
    </header>

    <div class="flow-body">
      <aside class="left-panel">
        <h3>步骤 1：区域选择</h3>
        <label>API Key</label>
        <input v-model="apiKey" type="password" placeholder="请输入 OpenTopography API Key" />

        <label>DEM 数据源</label>
        <select v-model="demSource">
          <option value="SRTMGL1">SRTM（30m）</option>
          <option value="COP30">Copernicus DEM（30m）</option>
          <option value="COP90">Copernicus DEM（90m）</option>
        </select>

        <label>选择模式</label>
        <div class="mode-row">
          <button type="button" :class="{ active: drawMode==='rectangle' }" @click="drawMode='rectangle'">矩形</button>
          <button type="button" :class="{ active: drawMode==='circle' }" @click="drawMode='circle'">圆形</button>
          <button type="button" :class="{ active: drawMode==='polygon' }" @click="drawMode='polygon'">自由形状</button>
        </div>
        <button type="button" @click="startDrawing">开始绘制区域</button>

        <label>GeoJSON 上传</label>
        <input type="file" accept=".geojson,.json" @change="handleGeoJsonUpload" />

        <label>面积限制（km²）</label>
        <input v-model.number="maxArea" type="range" min="1" max="1000" step="1" />
        <div class="limit-scale">
          <span>1</span>
          <span>当前限制：{{ maxArea }} km²</span>
          <span>1000</span>
        </div>
        <div class="progress-track">
          <div class="progress-fill" :style="{ width: `${areaUsagePercent.toFixed(1)}%` }"></div>
        </div>
        <div class="metric">占用比例：{{ areaUsagePercent.toFixed(1) }}%</div>
        <div class="metric">当前面积：{{ selected.areaKm2.toFixed(2) }} km²</div>
        <div class="metric">坐标：{{ selected.coordsText }}</div>
        <div class="metric" v-if="selected.center">
          中心点：{{ selected.center.lng.toFixed(6) }}, {{ selected.center.lat.toFixed(6) }}
        </div>
        <div v-if="areaExceeded" class="error-box">面积超限：当前 {{ selected.areaKm2.toFixed(2) }} km² > 限制 {{ maxArea }} km²</div>

        <h3>步骤 2：下载并 Three.js 预览</h3>
        <button type="button" :disabled="!canDownload || isDownloading" @click="downloadAndPreview">
          {{ isDownloading ? `处理中... ${downloadProgress}%` : '下载并切换至预览' }}
        </button>
        <button type="button" :disabled="isDownloading" @click="loadMockAndPreview">
          加载 Mock TIF（最小测试）
        </button>
        <label>地形缩放</label>
        <input v-model.number="heightScale" type="range" min="0.8" max="6" step="0.1" />

        <div class="metric">下载耗时：{{ perfInfo.downloadMs ?? '--' }} ms</div>
        <div class="metric">解析耗时：{{ perfInfo.parseMs ?? '--' }} ms</div>
        <div class="metric">渲染耗时：{{ perfInfo.renderMs ?? '--' }} ms</div>
        <div class="metric">内存占用：{{ perfInfo.memMb ?? '--' }} MB</div>
        <div v-if="errorMessage" class="error-box">{{ errorMessage }}</div>
      </aside>

      <main class="right-panel">
        <div v-show="step==='select'" ref="mapContainer" class="map-host"></div>
        <canvas v-show="step==='preview'" ref="threeCanvas" class="map-host"></canvas>
        <div v-if="step==='preview' && legend.visible" class="dem-legend-panel">
          <div class="legend-head">
            <span>DEM 图例面板</span>
            <span class="legend-range">{{ legend.min == null ? '--' : legend.min.toFixed(1) }} - {{ legend.max == null ? '--' : legend.max.toFixed(1) }} m</span>
          </div>
          <div class="legend-tabs">
            <button
              v-for="tab in legendTabs"
              :key="tab.id"
              type="button"
              :class="['legend-tab', { active: legendTab === tab.id }]"
              @click="legendTab = tab.id"
            >
              {{ tab.label }}
            </button>
          </div>
          <div class="legend-list">
            <div
              v-for="item in visibleLegendItems"
              :key="item.id"
              class="legend-item"
              @mouseenter="showLegendDetail(item)"
              @mouseleave="legendHoverText=''"
            >
              <div class="legend-swatch" :class="`swatch-${item.id}`"></div>
              <span class="legend-item-name">{{ item.label }}</span>
              <button
                type="button"
                class="legend-toggle"
                :class="{ on: isItemEnabled(item) }"
                @click="setLegendItem(item)"
              >
                {{ isItemEnabled(item) ? '开' : '关' }}
              </button>
            </div>
          </div>
          <div class="legend-gradient-band"></div>
          <div class="legend-scale">
            <span>{{ legend.min == null ? '--' : legend.min.toFixed(1) }}</span>
            <span>{{ legend.max == null ? '--' : legend.max.toFixed(1) }}</span>
          </div>
          <div v-if="thematicLegendSections.length" class="thematic-sections">
            <div v-for="section in thematicLegendSections" :key="section.title" class="thematic-section">
              <div class="thematic-title">{{ section.title }}</div>
              <div class="thematic-rows">
                <div v-for="entry in section.entries" :key="entry.label" class="thematic-row">
                  <span class="thematic-swatch" :style="{ background: entry.color }"></span>
                  <span class="thematic-label">{{ entry.label }}</span>
                </div>
              </div>
            </div>
          </div>
          <div class="legend-detail">
            {{ legendHoverText || '悬停图例项查看要素说明；点击可控制图层显隐或切换主渲染。' }}
          </div>
        </div>
      </main>
    </div>

    <div v-if="isProcessing" class="loading-mask">
      <div class="loading-card">正在下载/解析 DEM 数据，请稍候...</div>
    </div>
  </div>
</template>

<style scoped>
* { box-sizing: border-box; }
.dem-flow {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-height: 0;
  background: #050b18;
  color: rgba(226, 232, 240, 0.92);
}
.flow-nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(12, 16, 30, 0.7);
}
.flow-title { font-size: 14px; font-weight: 700; }
.flow-actions { display: flex; gap: 8px; }
.nav-btn {
  border: 1px solid rgba(255, 255, 255, 0.15);
  background: rgba(0, 0, 0, 0.25);
  color: rgba(226, 232, 240, 0.95);
  border-radius: 8px;
  padding: 6px 10px;
  cursor: pointer;
}
.flow-body {
  flex: 1;
  min-height: 0;
  display: flex;
}
.left-panel {
  width: 320px;
  min-width: 280px;
  flex-shrink: 0;
  padding: 10px 12px;
  overflow: auto;
  border-right: 1px solid rgba(255, 255, 255, 0.12);
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: rgba(12, 16, 30, 0.58);
}
.left-panel h3 {
  margin: 6px 0;
  font-size: 13px;
}
.left-panel label {
  font-size: 12px;
  color: rgba(203, 213, 225, 0.9);
}
.left-panel input, .left-panel select, .left-panel button {
  border: 1px solid rgba(255, 255, 255, 0.15);
  background: rgba(0, 0, 0, 0.2);
  color: rgba(226, 232, 240, 0.95);
  border-radius: 8px;
  padding: 7px 9px;
}
.left-panel button { cursor: pointer; }
.left-panel button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.mode-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.mode-row button.active {
  border-color: rgba(56, 189, 248, 0.55);
  background: rgba(56, 189, 248, 0.16);
}
.metric {
  font-size: 12px;
  color: rgba(203, 213, 225, 0.88);
  line-height: 1.4;
}
.limit-scale {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: rgba(203, 213, 225, 0.85);
}
.progress-track {
  height: 8px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.22);
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #38bdf8 0%, #a3e635 60%, #f59e0b 85%, #ef4444 100%);
}
.error-box {
  border: 1px solid rgba(248, 113, 113, 0.35);
  background: rgba(220, 38, 38, 0.3);
  color: rgba(254, 226, 226, 0.95);
  border-radius: 8px;
  padding: 8px;
  font-size: 12px;
}
.right-panel {
  flex: 1;
  min-width: 0;
  min-height: 0;
  position: relative;
}
.map-host {
  width: 100%;
  height: 100%;
  display: block;
}
.dem-legend-panel {
  position: absolute;
  right: 16px;
  top: 16px;
  width: 320px;
  max-width: calc(100% - 24px);
  max-height: calc(100% - 24px);
  padding: 10px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  background: rgba(12, 16, 30, 0.78);
  color: rgba(226, 232, 240, 0.95);
  z-index: 1800;
  overflow: auto;
}
.legend-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 8px;
}
.legend-range {
  color: rgba(56, 189, 248, 0.95);
  font-size: 11px;
}
.legend-tabs {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}
.legend-tab {
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.24);
  color: rgba(226, 232, 240, 0.92);
  font-size: 11px;
  padding: 6px 8px;
  cursor: pointer;
}
.legend-tab.active {
  border-color: rgba(56, 189, 248, 0.7);
  background: rgba(56, 189, 248, 0.18);
}
.legend-list {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.legend-item {
  display: grid;
  grid-template-columns: 14px 1fr auto;
  align-items: center;
  gap: 8px;
  padding: 6px 7px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(3, 8, 20, 0.4);
}
.legend-swatch {
  width: 14px;
  height: 14px;
  border-radius: 4px;
  border: 1px solid rgba(255, 255, 255, 0.22);
}
.legend-item-name {
  font-size: 12px;
  color: rgba(226, 232, 240, 0.95);
}
.legend-toggle {
  border: 1px solid rgba(255, 255, 255, 0.22);
  background: rgba(0, 0, 0, 0.24);
  color: rgba(226, 232, 240, 0.9);
  border-radius: 999px;
  padding: 3px 10px;
  font-size: 11px;
  cursor: pointer;
}
.legend-toggle.on {
  border-color: rgba(34, 197, 94, 0.7);
  color: rgba(220, 252, 231, 0.95);
  background: rgba(34, 197, 94, 0.2);
}
.legend-gradient-band {
  margin-top: 8px;
  width: 100%;
  height: 10px;
  border-radius: 999px;
  background: linear-gradient(90deg, #1d4ed8 0%, #10b981 40%, #eab308 70%, #ef4444 100%);
}
.legend-scale {
  margin-top: 6px;
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: rgba(203, 213, 225, 0.9);
}
.thematic-sections {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.thematic-section {
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  background: rgba(2, 6, 23, 0.45);
  padding: 6px 7px;
}
.thematic-title {
  font-size: 11px;
  font-weight: 700;
  color: rgba(148, 163, 184, 0.95);
}
.thematic-rows {
  margin-top: 5px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.thematic-row {
  display: grid;
  grid-template-columns: 12px 1fr;
  align-items: center;
  gap: 7px;
}
.thematic-swatch {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  border: 1px solid rgba(255, 255, 255, 0.24);
}
.thematic-label {
  font-size: 11px;
  color: rgba(226, 232, 240, 0.92);
}
.legend-detail {
  margin-top: 8px;
  padding: 7px 8px;
  border-radius: 8px;
  font-size: 11px;
  line-height: 1.35;
  color: rgba(203, 213, 225, 0.92);
  background: rgba(15, 23, 42, 0.65);
}
.swatch-elevation { background: linear-gradient(90deg, #1d4ed8 0%, #10b981 50%, #ef4444 100%); }
.swatch-slope { background: linear-gradient(90deg, #1f2937 0%, #f59e0b 100%); }
.swatch-aspect { background: linear-gradient(90deg, #ef4444 0%, #eab308 25%, #22c55e 50%, #3b82f6 75%, #ef4444 100%); }
.swatch-contour { background: repeating-linear-gradient(45deg, #111827 0 2px, #d1d5db 2px 4px); }
.swatch-flow { background: #38bdf8; }
.swatch-watershed { background: #fbbf24; }
.swatch-valley { background: #22d3ee; }
.swatch-ridge { background: #f43f5e; }
.swatch-relief { background: linear-gradient(90deg, #334155 0%, #f59e0b 100%); }
.swatch-roughness { background: linear-gradient(90deg, #64748b 0%, #f8fafc 100%); }
.swatch-twi { background: linear-gradient(90deg, #0f172a 0%, #0ea5e9 100%); }
.loading-mask {
  position: absolute;
  inset: 0;
  z-index: 4000;
  background: rgba(2, 6, 23, 0.35);
  backdrop-filter: blur(2px);
}
.loading-card {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  background: rgba(12, 16, 30, 0.75);
  padding: 10px 14px;
}

@media (max-width: 1100px) {
  .flow-body { flex-direction: column; }
  .left-panel {
    width: 100%;
    min-width: 0;
    max-height: 45%;
    border-right: none;
    border-bottom: 1px solid rgba(255, 255, 255, 0.12);
  }
  .dem-legend-panel {
    right: 8px;
    top: 8px;
    width: min(360px, calc(100% - 16px));
  }
}
</style>
