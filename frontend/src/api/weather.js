import request from '../utils/request';

export function getWeatherHeatmap(params) {
  return request({
    url: '/weather/heatmap',
    method: 'get',
    params,
  });
}

export function listWeatherRuns(params) {
  return request({
    url: '/weather/runs',
    method: 'get',
    params,
  });
}

export function listWeatherForecastTimes(params) {
  return request({
    url: '/weather/forecast-times',
    method: 'get',
    params,
  });
}
