<template>
  <div class="tile-downloader-page">
    <div class="left-panel">
      <div class="tile-downloader">
        <h2>瓦片下载器</h2>
        
        <div class="form-group">
          <label>选择区域：</label>
          <div class="region-selects">
            <select v-model="selectedProvince" @change="onProvinceChange">
              <option value="">请选择省份</option>
              <option v-for="p in provinces" :key="p.adcode" :value="p">{{ p.name }}</option>
            </select>
            
            <select v-model="selectedCity" @change="onCityChange" :disabled="!selectedProvince">
              <option value="">请选择城市</option>
              <option v-for="c in cities" :key="c.adcode" :value="c">{{ c.name }}</option>
            </select>
            
            <select v-model="selectedDistrict" :disabled="!selectedCity">
              <option value="">请选择区县</option>
              <option v-for="d in districts" :key="d.adcode" :value="d">{{ d.name }}</option>
            </select>
          </div>
        </div>

        <div class="form-group">
          <label>缩放级别 (Zoom)：</label>
          <input type="text" v-model="zoomLevelsInput" placeholder="例如: 10,11 (逗号分隔)" />
        </div>

        <div class="form-group">
          <label>瓦片服务：</label>
          <select v-model="selectedServiceUrl">
            <optgroup label="EPSG:3857 (Web Mercator)">
              <option value="https://tile.openstreetmap.org/{z}/{x}/{y}.png">OpenStreetMap (XYZ)</option>
              <option value="https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}">ArcGIS World Imagery - 影像 (XYZ)</option>
              <option value="https://tile.opentopomap.org/{z}/{x}/{y}.png">OpenTopoMap (XYZ)</option>
              <option value="https://cartodb-basemaps-a.global.ssl.fastly.net/light_all/{z}/{x}/{y}.png">CARTO Light (XYZ)</option>
            </optgroup>
            <optgroup label="EPSG:4326 (WGS84)">
              <option value="http://t0.tianditu.gov.cn/img_c/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=img&STYLE=default&TILEMATRIXSET=c&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=">天地图影像 (经纬度投影)</option>
            </optgroup>
            <optgroup label="其他">
              <option value="http://t0.tianditu.gov.cn/img_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=img&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=">天地图影像 (Web Mercator)</option>
            </optgroup>
          </select>
        </div>

        <div class="form-group">
          <label>源坐标系：</label>
          <div class="radio-group">
            <label class="radio-label">
              <input type="radio" v-model="sourceEpsg" value="EPSG:3857" /> EPSG:3857 (Web Mercator)
            </label>
            <label class="radio-label">
              <input type="radio" v-model="sourceEpsg" value="EPSG:4326" /> EPSG:4326 (WGS84)
            </label>
          </div>
          <small class="tip">通常 OSM/Google/ArcGIS 等互联网地图为 3857，天地图(经纬度)为 4326</small>
        </div>

        <div class="form-group" v-if="needsKey">
          <label>API Key:</label>
          <input type="text" v-model="apiKey" placeholder="输入您的 API Key" />
        </div>

        <div class="form-group">
          <label>输出格式：</label>
          <div class="radio-group">
            <label class="radio-label">
              <input type="radio" v-model="outputFormat" value="png" /> PNG (图片)
            </label>
            <label class="radio-label">
              <input type="radio" v-model="outputFormat" value="tif" /> GeoTIFF (带地理坐标)
            </label>
          </div>
        </div>

        <div class="form-group">
          <label>选项：</label>
          <div class="checkbox-group">
            <label class="checkbox-label">
              <input type="checkbox" v-model="mergeTiles" /> 合并为单张大图
            </label>
            <label class="checkbox-label">
              <input type="checkbox" v-model="reprojectTo4490" /> 转换为 CGCS2000 (EPSG:4490)
            </label>
          </div>
        </div>

        <button @click="startDownload" :disabled="!canDownload">开始下载</button>

        <div v-if="result" class="result-box">
          <h3>下载任务已提交</h3>
          <p>任务ID: {{ result.taskId }}</p>
          <p>输出路径: {{ result.outputPath }}</p>
          <p>状态: {{ result.status }}</p>

          <div class="form-group result-actions">
            <button class="secondary" @click="refreshTaskFiles">刷新输出文件</button>
          </div>

          <div v-if="taskFiles.length > 0" class="form-group">
            <label>输出文件：</label>
            <select v-model="selectedTaskFile">
              <option value="">请选择文件</option>
              <option v-for="f in taskFiles" :key="f.name" :value="f">{{ f.name }}</option>
            </select>
            <small class="tip">选择 .tif 文件可在右侧预览</small>
          </div>

          <div v-if="selectedTaskFile && selectedTaskFile.type === 'tif'" class="form-group">
            <button class="secondary" @click="previewSelectedTif">在右侧预览该 TIF</button>
          </div>
        </div>

        <div v-if="error" class="error-box">
          {{ error }}
        </div>
      </div>
    </div>

    <div class="right-panel">
      <div class="tif-preview">
        <div class="preview-header">
          <h2>TIF 预览</h2>
          <div class="preview-meta" v-if="previewFileName">
            {{ previewFileName }}
          </div>
        </div>
        <div ref="previewMapTarget" class="preview-map"></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue';
import 'ol/ol.css';
import Map from 'ol/Map';
import View from 'ol/View';
import TileLayer from 'ol/layer/Tile';
import WebGLTileLayer from 'ol/layer/WebGLTile';
import XYZ from 'ol/source/XYZ';
import GeoTIFF from 'ol/source/GeoTIFF';
import { getRegions, downloadTiles, listTaskFiles } from '../api/gridtile';

const allRegions = ref([]);
const provinces = ref([]);
const cities = ref([]);
const districts = ref([]);

const selectedProvince = ref('');
const selectedCity = ref('');
const selectedDistrict = ref('');

const zoomLevelsInput = ref('10');
const selectedServiceUrl = ref('https://tile.openstreetmap.org/{z}/{x}/{y}.png');
const apiKey = ref('');
const mergeTiles = ref(false);
const reprojectTo4490 = ref(true);
const sourceEpsg = ref('EPSG:3857');
const outputFormat = ref('png');

const result = ref(null);
const error = ref(null);
const taskFiles = ref([]);
const selectedTaskFile = ref('');
const previewMapTarget = ref(null);
const previewFileName = ref('');

let mapInstance = null;
let geotiffLayer = null;

// Watch service URL change to auto-detect EPSG
watch(selectedServiceUrl, (newVal) => {
  if (newVal.includes('img_c') || newVal.includes('TILEMATRIXSET=c')) {
    sourceEpsg.value = 'EPSG:4326';
  } else {
    sourceEpsg.value = 'EPSG:3857';
  }
});

const needsKey = computed(() => {
  return selectedServiceUrl.value.includes('tk=') || 
         selectedServiceUrl.value.includes('key=') || 
         selectedServiceUrl.value.includes('apikey=');
});

const canDownload = computed(() => {
  if (!zoomLevelsInput.value) return false;
  // Case 1: District selected
  if (selectedDistrict.value) return true;
  // Case 2: City selected and it has no districts (e.g. direct-controlled county or leaf node)
  if (selectedCity.value && districts.value.length === 0 && selectedCity.value.adcode !== 'dummy') return true;
  return false;
});

onMounted(async () => {
  try {
    const data = await getRegions();
    if (data) {
      allRegions.value = data;
      // 提取省份 (level = province)
      provinces.value = allRegions.value.filter(r => r.level === 'province');
    }
  } catch (e) {
    console.error('Failed to load regions', e);
  }

  mapInstance = new Map({
    target: previewMapTarget.value,
    layers: [
      new TileLayer({
        source: new XYZ({
          url: 'https://{a-c}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
          crossOrigin: 'anonymous'
        })
      })
    ],
    view: new View({
      center: [0, 0],
      zoom: 2
    })
  });

  await nextTick();
  mapInstance.updateSize();
});

onBeforeUnmount(() => {
  if (mapInstance) {
    mapInstance.setTarget(undefined);
    mapInstance = null;
  }
});

const onProvinceChange = () => {
  selectedCity.value = '';
  selectedDistrict.value = '';
  districts.value = [];
  
  if (selectedProvince.value) {
    // Check if children are districts (Direct-controlled municipality case)
    const children = allRegions.value.filter(r => r.parent === selectedProvince.value.adcode);
    
    // Simple heuristic: if the first child is 'district', treat as municipality
    // This handles Beijing, Tianjin, Shanghai, Chongqing
    if (children.length > 0 && children[0].level === 'district') {
       cities.value = [{ name: '市辖区', adcode: 'dummy', level: 'dummy_city' }];
       // Auto-select dummy city to reveal districts immediately? 
       // Better to let user select it to be consistent, or just auto-select.
       // Let's auto-select for better UX.
       selectedCity.value = cities.value[0];
       onCityChange();
    } else {
       cities.value = children;
    }
  } else {
    cities.value = [];
  }
};

const onCityChange = () => {
  selectedDistrict.value = '';
  districts.value = [];
  
  if (selectedCity.value) {
    if (selectedCity.value.adcode === 'dummy') {
      // It's a municipality, load children of the province
      districts.value = allRegions.value.filter(r => r.parent === selectedProvince.value.adcode);
    } else {
      // Normal city
      districts.value = allRegions.value.filter(r => r.parent === selectedCity.value.adcode);
    }
  }
};

const startDownload = async () => {
  result.value = null;
  error.value = null;
  taskFiles.value = [];
  selectedTaskFile.value = '';
  previewFileName.value = '';

  try {
    // 处理 zoom levels
    const zooms = zoomLevelsInput.value.split(',')
      .map(z => parseInt(z.trim()))
      .filter(z => !isNaN(z));
    
    if (zooms.length === 0) {
      error.value = "请输入有效的缩放级别";
      return;
    }
    if (zooms.length > 2) {
      error.value = "最多指定两级 Zoom";
      return;
    }

    // 处理 Service URL
    let finalUrl = selectedServiceUrl.value;
    if (needsKey.value && apiKey.value) {
      if (finalUrl.endsWith('=')) {
        finalUrl += apiKey.value;
      }
    }

    // Determine target region
    const targetRegion = selectedDistrict.value || selectedCity.value;
    if (!targetRegion || targetRegion.adcode === 'dummy') {
      error.value = "请选择有效的行政区";
      return;
    }

    const payload = {
      adcode: targetRegion.adcode,
      regionName: targetRegion.name,
      zoomLevels: zooms,
      serviceUrl: finalUrl,
      merge: mergeTiles.value,
      targetEpsg: reprojectTo4490.value ? "EPSG:4490" : "EPSG:4326",
      sourceEpsg: sourceEpsg.value,
      format: outputFormat.value
    };

    const data = await downloadTiles(payload);
    if (data) {
      result.value = data;
      await refreshTaskFiles();
    } else {
      error.value = "下载请求失败";
    }
  } catch (e) {
    error.value = "请求异常: " + e.message;
  }
};

const refreshTaskFiles = async () => {
  if (!result.value || !result.value.taskId) return;
  try {
    const files = await listTaskFiles(result.value.taskId);
    taskFiles.value = Array.isArray(files) ? files : [];
    if (!selectedTaskFile.value) {
      const firstTif = taskFiles.value.find(f => f.type === 'tif');
      if (firstTif) {
        selectedTaskFile.value = firstTif;
      }
    }
  } catch (e) {
    error.value = "读取输出文件失败: " + e.message;
  }
};

const previewSelectedTif = async () => {
  if (!selectedTaskFile.value || selectedTaskFile.value.type !== 'tif') return;
  const url = selectedTaskFile.value.url;
  previewFileName.value = selectedTaskFile.value.name;
  await loadGeoTiff(url);
};

const loadGeoTiff = async (url) => {
  if (!mapInstance) return;

  if (geotiffLayer) {
    mapInstance.removeLayer(geotiffLayer);
    geotiffLayer = null;
  }

  const source = new GeoTIFF({
    sources: [{ url }]
  });

  geotiffLayer = new WebGLTileLayer({ source });
  mapInstance.addLayer(geotiffLayer);

  try {
    const viewOptions = await source.getView();
    mapInstance.setView(new View(viewOptions));
  } catch (e) {
    error.value = "TIF 预览失败: " + e.message;
  }

  await nextTick();
  mapInstance.updateSize();
};
</script>

<style scoped>
.tile-downloader-page {
  width: 100%;
  height: 100%;
  min-height: 0;
  display: flex;
  gap: 16px;
  align-items: stretch;
  overflow: hidden;
}

.left-panel {
  flex: 0 0 520px;
  max-width: 520px;
  height: 100%;
  min-height: 0;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.02);
  box-sizing: border-box;
}

.right-panel {
  flex: 1;
  min-width: 520px;
  height: 100%;
  min-height: 0;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.02);
  box-sizing: border-box;
}

.tile-downloader {
  padding: 30px;
  background: rgba(25, 27, 48, 0.7); /* Deep blue/black transparent */
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-radius: 16px;
  margin: 0;
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.37);
  color: #e0e0e0;
  height: 100%;
  box-sizing: border-box;
  overflow: auto;
}

h2 {
  color: #fff;
  margin-bottom: 25px;
  text-align: center;
  font-weight: 300;
  letter-spacing: 1px;
}

.form-group {
  margin-bottom: 20px;
}

label {
  display: block;
  margin-bottom: 8px;
  color: #a0a0b0;
  font-size: 0.9rem;
  font-weight: 500;
}

/* Make inputs look modern */
select, input[type="text"] {
  width: 100%;
  padding: 12px 15px;
  background: rgba(0, 0, 0, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  color: #fff;
  font-size: 1rem;
  transition: all 0.3s ease;
  box-sizing: border-box;
}

select:focus, input[type="text"]:focus {
  outline: none;
  border-color: #646cff;
  background: rgba(0, 0, 0, 0.4);
  box-shadow: 0 0 0 2px rgba(100, 108, 255, 0.2);
}

/* Horizontal layout for province/city/district selects */
.region-selects {
  display: flex;
  gap: 10px;
}

.region-selects select {
  flex: 1;
}

/* Checkboxes */
.checkbox-group {
  display: flex;
  gap: 20px;
  align-items: center;
}
.checkbox-label {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: #ccc;
  width: auto;
  margin: 0;
}

/* Radio Buttons */
.radio-group {
  display: flex;
  gap: 20px;
  align-items: center;
}
.radio-label {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: #ccc;
  width: auto;
  margin: 0;
}
.tip {
  display: block;
  margin-top: 5px;
  color: #888;
  font-size: 0.8rem;
}

/* Button */
button {
  width: 100%;
  padding: 14px;
  background: linear-gradient(135deg, #646cff 0%, #9f55ff 100%);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 1.1rem;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s;
  margin-top: 10px;
}

button.secondary {
  background: rgba(0, 0, 0, 0.25);
  border: 1px solid rgba(255, 255, 255, 0.14);
}

button:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 15px rgba(100, 108, 255, 0.4);
}

button:disabled {
  background: #444;
  color: #888;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}

/* Result Box */
.result-box {
  margin-top: 25px;
  padding: 20px;
  background: rgba(46, 204, 113, 0.1);
  border: 1px solid rgba(46, 204, 113, 0.3);
  border-radius: 8px;
  color: #2ecc71;
}

.error-box {
  margin-top: 25px;
  padding: 20px;
  background: rgba(231, 76, 60, 0.1);
  border: 1px solid rgba(231, 76, 60, 0.3);
  border-radius: 8px;
  color: #e74c3c;
}

.tif-preview {
  padding: 20px;
  background: rgba(25, 27, 48, 0.7);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.37);
  height: 100%;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  overflow: hidden;
}

.preview-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.preview-header h2 {
  margin: 0;
  text-align: left;
}

.preview-meta {
  color: #a0a0b0;
  font-size: 0.9rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-map {
  flex: 1;
  min-height: 0;
  border-radius: 12px;
  overflow: hidden;
}

/* Scrollbar for selects */
::-webkit-scrollbar {
  width: 8px;
}
::-webkit-scrollbar-track {
  background: #2c2c2c; 
}
::-webkit-scrollbar-thumb {
  background: #555; 
  border-radius: 4px;
}
</style>
