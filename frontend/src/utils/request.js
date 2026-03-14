import axios from 'axios';

// 创建 axios 实例
const service = axios.create({
  // 注意：vite.config.js 中配置了代理
  // 如果后端接口是 /api/gridtile/regions
  // 这里 baseURL 设为 /api，请求 url 为 /gridtile/regions
  // 最终拼接为 /api/gridtile/regions，被代理转发到 localhost:8080/api/gridtile/regions
  baseURL: '/api', 
  timeout: 60000, 
});

// 请求拦截器
service.interceptors.request.use(
  (config) => {
    return config;
  },
  (error) => {
    console.error('Request Error:', error);
    return Promise.reject(error);
  }
);

// 响应拦截器
service.interceptors.response.use(
  (response) => {
    const res = response.data;
    // 假设后端的统一响应格式为 { code: 200, data: ..., message: ... }
    // 注意：部分后端可能成功时不返回 code 字段或 code=0，需根据实际情况调整
    if (res.code !== undefined && res.code !== 200) {
      console.error('API Error:', res.message || 'Unknown Error');
      return Promise.reject(new Error(res.message || 'Error'));
    } else {
      // 兼容后端直接返回 data 或包装体
      return res.data !== undefined ? res.data : res;
    }
  },
  (error) => {
    console.error('Response Error:', error);
    return Promise.reject(error);
  }
);

export default service;
