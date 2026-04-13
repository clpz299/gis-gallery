<script setup>
import { ref } from 'vue'
import MapComponent from './components/MapComponent.vue'
import TileDownloader from './components/TileDownloader.vue'

const currentTab = ref('map')
</script>

<template>
  <header class="app-header">
    <div class="header-brand">
      <h1>GIS Gallery</h1>
    </div>
    
    <nav class="app-nav">
      <button :class="{ active: currentTab === 'map' }" @click="currentTab = 'map'">Weather Map</button>
      <button :class="{ active: currentTab === 'download' }" @click="currentTab = 'download'">Tile Downloader</button>
    </nav>

    <div class="header-search">
      <input type="text" placeholder="搜索资源..." class="search-input" />
      <button class="search-btn">
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
      </button>
    </div>
  </header>

  <div class="app-container">
    <main class="app-main">
      <div v-if="currentTab === 'map'" class="tab-content">
        <MapComponent />
      </div>
      <div v-if="currentTab === 'download'" class="tab-content">
        <TileDownloader />
      </div>
    </main>
  </div>

  <footer class="app-footer">
    <div class="footer-content">
      <span>&copy; 2024 GIS Gallery. All rights reserved.</span>
      <div class="footer-links">
        <a href="#">关于</a>
        <a href="#">隐私政策</a>
        <a href="#">服务条款</a>
      </div>
    </div>
  </footer>
</template>

<style scoped>
.tab-content {
  width: 100%;
  height: 100%;
  overflow: hidden;
}
</style>
