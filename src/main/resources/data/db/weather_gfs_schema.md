## GFS 0.25° 入库表结构（PostgreSQL + PostGIS）

目标：支撑 Spring Boot 接口按“要素 + 预报时效/有效时间 + 区域 bbox”查询并返回 OpenLayers 热力图点集（GeoJSON/点列表）。

### 1. 启用扩展

```sql
create extension if not exists postgis;
```

### 2. 基础维表

#### 2.1 模型运行（同一次起报/分析时刻）

```sql
create table if not exists weather_model_run (
  id              bigserial primary key,
  model           varchar(32) not null,        -- gfs_0p25
  run_time_utc    timestamptz not null,        -- 起报时间（UTC）
  created_at      timestamptz not null default now(),
  unique(model, run_time_utc)
);
```

#### 2.2 预报时间（有效时间/预报时效）

```sql
create table if not exists weather_forecast_time (
  id              bigserial primary key,
  run_id          bigint not null references weather_model_run(id) on delete cascade,
  lead_hours      int not null,                -- 预报时效（小时）
  valid_time_utc  timestamptz not null,        -- 有效时间（UTC）
  unique(run_id, lead_hours)
);
create index if not exists idx_weather_forecast_time_valid on weather_forecast_time(valid_time_utc);
```

#### 2.3 要素字典

```sql
create table if not exists weather_element (
  code        varchar(64) primary key,         -- tmp_2m / rh_2m / prate / wind_10m ...
  name        varchar(128) not null,
  unit        varchar(32) not null default '',
  description text not null default ''
);
```

#### 2.4 垂直层（地面/等压面/等高面等）

```sql
create table if not exists weather_level (
  code        varchar(64) primary key,         -- surface / isobaric_850 / isobaric_500 ...
  level_type  varchar(32) not null,            -- surface/isobaric/height/...
  level_value double precision,                -- 850 / 500 / 2 (m) 等，按类型解释
  unit        varchar(16) not null default ''  -- hPa/m/...
);
```

### 3. 空间与数值事实表

#### 3.1 栅格采样点（便于热力图/区域查询）

说明：GFS 全球栅格点量很大，建议按业务区域（例如中国/某 bbox）与采样步长（stride）落库；点与值分表避免重复存经纬度。

```sql
create table if not exists weather_grid_point (
  id      bigserial primary key,
  lon     double precision not null,
  lat     double precision not null,
  geom    geometry(Point, 4326) not null,
  unique(lon, lat)
);
create index if not exists idx_weather_grid_point_geom on weather_grid_point using gist(geom);
```

#### 3.2 预报值（按要素/层/时间/点）

```sql
create table if not exists weather_forecast_value (
  forecast_time_id bigint not null references weather_forecast_time(id) on delete cascade,
  element_code     varchar(64) not null references weather_element(code),
  level_code       varchar(64) not null references weather_level(code),
  point_id         bigint not null references weather_grid_point(id),
  value            double precision not null,
  primary key (forecast_time_id, element_code, level_code, point_id)
);

create index if not exists idx_weather_value_element_time
  on weather_forecast_value(element_code, forecast_time_id);
```

### 4. 查询示例（热力图点集）

```sql
select p.lon, p.lat, v.value
from weather_forecast_value v
join weather_grid_point p on p.id = v.point_id
where v.element_code = :element
  and v.level_code = :level
  and v.forecast_time_id = :forecastTimeId
  and p.geom && st_makeenvelope(:minLon, :minLat, :maxLon, :maxLat, 4326);
```

### 5. 建议

- 数据量控制：落库时按 bbox + stride 下采样（例如 stride=4/6/8），避免“全全球全分辨率逐点入库”。
- 清理策略：按 `weather_model_run.run_time_utc` 保留窗口删除（级联删除 forecast_time/value）。

