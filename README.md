# GIS Gallery

**GIS Gallery** 是一个汇集了各种 GIS（地理信息系统）应用示例的项目。本项目旨在沉淀和展示在实际工作中遇到的 GIS 技术特点，通过一个个具体的 Example（示例），生动地呈现 GIS 的能力。

## 📖 项目简介

这是一个基于 **Spring Boot** 后端框架构建的综合性 GIS 演示平台，深度整合了 **Vue 3** 现代前端框架。本项目采用前后端一体化的构建方式，既保证了开发时的灵活性，又实现了部署时的便捷性（单 JAR 包启动）。

## 🛠 技术组成

本项目采用了当前主流且高效的技术栈：

- **后端**：Spring Boot 3.5.0 (Java 17+) - 提供稳健的 RESTful API 服务与静态资源托管。
- **前端**：Vue.js 3 + Vite - 构建响应式、高性能的用户界面。
- **构建工具**：Maven - 统一管理依赖，并通过 `frontend-maven-plugin` 插件自动集成前端构建流程。
- **数据库**：PostgreSQL + PostGIS - 存储气象点/栅格化查询所需的数据与空间能力。

## 🎯 应用场景

本项目主要服务于以下目标和场景：

1. **初学者了解 GIS 入门的方式**：为刚刚接触 GIS 的开发者提供代码参考和直观的运行效果，降低学习门槛。
2. **探索 GIS 可以做哪些事情**：通过丰富多样的示例，展示地理信息技术在可视化、数据分析等方面的无限可能。
3. **一些有趣的应用**：不仅仅是枯燥的功能堆叠，更包含了一些具有创意和趣味性的地理应用场景（如实时天气地图等）。

## 🚀 快速开始

### 环境要求

- JDK 17 或更高版本
- PostgreSQL（建议安装 PostGIS 扩展）

### PostgreSQL 配置说明

本项目依赖 PostgreSQL 进行数据入库与查询（并使用 PostGIS 扩展提供空间类型/函数）。

1. **创建数据库并启用 PostGIS**
   - 数据库初始化脚本默认会尝试执行 `create extension if not exists postgis;`
   - 若数据库用户无权限创建 extension，请先由 DBA 在目标库中启用 PostGIS
2. **配置连接信息**
   - 在 `src/main/resources/application.yml` 中配置：
     - `spring.datasource.url`
     - `spring.datasource.username`
     - `spring.datasource.password`
3. **数据初始化**
   - 默认使用 `spring.sql.init.schema-locations: classpath:db/schema.sql` 初始化表结构

### 方式一：一体化启动（推荐）

无需安装 Node.js 环境，Maven 会自动处理一切。

1. **编译打包**
   ```bash
   ./mvnw clean package -DskipTests
   ```
   *该命令会自动下载 Node/NPM，编译 Vue 前端，并将生成的静态资源打包进 JAR 文件中。*
2. **启动服务**
   ```bash
   java -jar target/gis-gallery-0.0.1-SNAPSHOT.jar
   ```
3. **访问**
   打开浏览器访问 <http://localhost:8080>

### 方式二：前后端分离开发

适合开发阶段，支持前端热更新。

1. **启动后端**
   在 IDE 中运行 `GisGalleryApplication.java`。
2. **启动前端**
   ```bash
   cd frontend
   npm install
   npm run dev
   ```
   访问前端开发服务器（通常是 <http://localhost:5173）。>

## 📂 示例列表

目前已包含的示例：

- **天气地图 (Weather Map)**：基于 OpenLayers 的气象要素渲染示例，支持起报时间/预报时效切换、热力图与栅格瓦片两种渲染模式、分层设色与图例展示，并通过服务端栅格瓦片查询与插值提升缩放/拖拽时的可视化一致性。详见 [气象栅格瓦片渲染与插值设计](doc/column/气象栅格瓦片渲染与插值设计.md)。
- **瓦片下载与区域管理 (Tile Download & Region)**：支持按行政区（省/市/区）选择范围，选择 XYZ/WMTS 瓦片源与缩放级别（最多两级），输出 PNG 或 GeoTIFF，并可选择合并输出或保留 z/x/y 瓦片目录结构。详见 [区域瓦片下载.md](doc/gridtile/区域瓦片下载.md)。

***

*更多有趣的 GIS 示例正在持续添加中...*
