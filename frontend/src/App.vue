<script setup>
import { ref } from 'vue'
import MapComponent from './components/MapComponent.vue'
import TileDownloader from './components/TileDownloader.vue'

const currentTab = ref('map')
</script>

<template>
  <div class="container">
    <header>
      <h1>GIS Gallery</h1>
      <nav>
        <button :class="{ active: currentTab === 'map' }" @click="currentTab = 'map'">Weather Map</button>
        <button :class="{ active: currentTab === 'download' }" @click="currentTab = 'download'">Tile Downloader</button>
      </nav>
    </header>

    <main>
      <div v-if="currentTab === 'map'" class="tab-content">
        <MapComponent />
      </div>
      <div v-if="currentTab === 'download'" class="tab-content">
        <TileDownloader />
      </div>
    </main>
  </div>
</template>

<style scoped>
.container {
  width: 100%;
  height: 100vh;
  display: flex;
  flex-direction: column;
}

header {
  padding: 1rem;
  background: #333;
  color: white;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

nav button {
  background: transparent;
  border: none;
  color: #aaa;
  font-size: 1.1rem;
  margin-left: 1rem;
  cursor: pointer;
  padding: 0.5rem 1rem;
}

nav button.active {
  color: white;
  border-bottom: 2px solid white;
}

main {
  flex: 1;
  position: relative;
  overflow: hidden;
}

.tab-content {
  width: 100%;
  height: 100%;
  overflow: auto;
}
</style>
