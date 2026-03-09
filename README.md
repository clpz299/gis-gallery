# GIS Gallery

**GIS Gallery** 是一个汇集了各种 GIS（地理信息系统）应用示例的项目。本项目旨在沉淀和展示在实际工作中遇到的 GIS 技术特点，通过一个个具体的 Example（示例），生动地呈现 GIS 的能力。

## 📖 项目简介

这是一个基于 **Spring Boot** 后端框架构建的综合性 GIS 演示平台，深度整合了 **Vue 3** 现代前端框架与 **OpenLayers** 地图引擎。本项目采用前后端一体化的构建方式，既保证了开发时的灵活性，又实现了部署时的便捷性（单 JAR 包启动）。

## 🛠 技术组成

本项目采用了当前主流且高效的技术栈：

*   **后端**：Spring Boot 3.5.0 (Java 17+) - 提供稳健的 RESTful API 服务与静态资源托管。
*   **前端**：Vue.js 3 + Vite - 构建响应式、高性能的用户界面。
*   **地图引擎**：OpenLayers 9+ - 强大的开源 Web GIS 库，支持各种地图源和复杂的地理操作。
*   **构建工具**：Maven - 统一管理依赖，并通过 `frontend-maven-plugin` 插件自动集成前端构建流程。

## 🎯 应用场景

本项目主要服务于以下目标和场景：

1.  **初学者了解 GIS 入门的方式**：为刚刚接触 GIS 的开发者提供代码参考和直观的运行效果，降低学习门槛。
2.  **探索 GIS 可以做哪些事情**：通过丰富多样的示例，展示地理信息技术在可视化、数据分析等方面的无限可能。
3.  **一些有趣的应用**：不仅仅是枯燥的功能堆叠，更包含了一些具有创意和趣味性的地理应用场景（如实时天气地图等）。

## 🚀 快速开始

### 环境要求
*   JDK 17 或更高版本

### 方式一：一体化启动（推荐）
无需安装 Node.js 环境，Maven 会自动处理一切。

1.  **编译打包**
    ```bash
    ./mvnw clean package -DskipTests
    ```
    *该命令会自动下载 Node/NPM，编译 Vue 前端，并将生成的静态资源打包进 JAR 文件中。*

2.  **启动服务**
    ```bash
    java -jar target/gis-gallery-0.0.1-SNAPSHOT.jar
    ```

3.  **访问**
    打开浏览器访问 [http://localhost:8080](http://localhost:8080)

### 方式二：前后端分离开发
适合开发阶段，支持前端热更新。

1.  **启动后端**
    在 IDE 中运行 `GisGalleryApplication.java`。

2.  **启动前端**
    ```bash
    cd frontend
    npm install
    npm run dev
    ```
    访问前端开发服务器（通常是 http://localhost:5173）。

## 📂 示例列表

目前已包含的示例：
*   **天气地图 (Weather Map)**：基于 OpenLayers 实现的模拟气象站点可视化，展示了点要素（Point）、样式自定义（Style）及弹窗交互（Overlay）的综合应用。

---
*更多有趣的 GIS 示例正在持续添加中...*
