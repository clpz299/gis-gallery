create extension if not exists postgis;

create table if not exists weather_model_run (
  id              bigserial primary key,
  model           varchar(32) not null,
  run_time_utc    timestamptz not null,
  created_at      timestamptz not null default now(),
  unique(model, run_time_utc)
);

create table if not exists weather_forecast_time (
  id              bigserial primary key,
  run_id          bigint not null references weather_model_run(id) on delete cascade,
  lead_hours      int not null,
  valid_time_utc  timestamptz not null,
  unique(run_id, lead_hours)
);
create index if not exists idx_weather_forecast_time_valid on weather_forecast_time(valid_time_utc);

create table if not exists weather_element (
  code        varchar(64) primary key,
  name        varchar(128) not null,
  unit        varchar(32) not null default '',
  description text not null default ''
);

create table if not exists weather_level (
  code        varchar(64) primary key,
  level_type  varchar(32) not null,
  level_value double precision,
  unit        varchar(16) not null default ''
);

create table if not exists weather_grid_point (
  id      bigserial primary key,
  lon     double precision not null,
  lat     double precision not null,
  geom    geometry(Point, 4326) not null,
  unique(lon, lat)
);
create index if not exists idx_weather_grid_point_geom on weather_grid_point using gist(geom);

create table if not exists weather_forecast_value (
  forecast_time_id bigint not null references weather_forecast_time(id) on delete cascade,
  element_code     varchar(64) not null references weather_element(code),
  level_code       varchar(64) not null references weather_level(code),
  point_id         bigint not null references weather_grid_point(id),
  value            double precision not null,
  primary key (forecast_time_id, element_code, level_code, point_id)
);
create index if not exists idx_weather_value_element_time on weather_forecast_value(element_code, forecast_time_id);
