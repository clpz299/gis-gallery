import request from '../utils/request';

export function getRegions() {
  return request({
    url: '/gridtile/regions',
    method: 'get',
  });
}

export function downloadTiles(data) {
  return request({
    url: '/gridtile/download',
    method: 'post',
    data,
  });
}
