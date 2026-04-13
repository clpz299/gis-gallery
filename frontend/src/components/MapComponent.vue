<script setup>
import { onMounted, ref } from 'vue';
import 'ol/ol.css';
import Map from 'ol/Map';
import View from 'ol/View';
import TileLayer from 'ol/layer/Tile';
import XYZ from 'ol/source/XYZ';
import VectorLayer from 'ol/layer/Vector';
import VectorSource from 'ol/source/Vector';
import Feature from 'ol/Feature';
import Point from 'ol/geom/Point';
import { fromLonLat } from 'ol/proj';
import { Style, Circle, Fill, Stroke, Text } from 'ol/style';
import Overlay from 'ol/Overlay';

const mapContainer = ref(null);
const popupContainer = ref(null);
const popupContent = ref(null);
const popupCloser = ref(null);

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

  const map = new Map({
    target: mapContainer.value,
    layers: [
      new TileLayer({
        source: new XYZ({
          url: 'https://{a-c}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
          crossOrigin: 'anonymous'
        })
      }),
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
});
</script>

<template>
  <div class="map-wrapper">
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
