// 与洞府后端通信。写操作统一带 X-Xiuxian 头（后端的禁制要求），错误统一成中文信息。

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

async function request(method, path, body) {
  const opts = { method, headers: {} };
  if (method !== 'GET') {
    opts.headers['X-Xiuxian'] = '1';
    opts.headers['Content-Type'] = 'application/json';
    if (body !== undefined) opts.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch('/api' + path, opts);
  } catch {
    throw new ApiError(0, '灵脉受阻：连不上洞府，电脑是否开着？');
  }
  if (res.status === 204) return null;
  const text = await res.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (!res.ok) {
    throw new ApiError(res.status, (data && data.error) || '灵脉紊乱（' + res.status + '）');
  }
  return data;
}

export const api = {
  get: (path) => request('GET', path),
  post: (path, body) => request('POST', path, body ?? {}),
  put: (path, body) => request('PUT', path, body),
  del: (path) => request('DELETE', path),
};
