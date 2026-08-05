import http from 'k6/http';
import { check } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';

export const options = {
  scenarios: {
    albums: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 30,
      exec: 'albums',
    },
    photos: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 30,
      exec: 'photos',
      startTime: '35s',
    },
    timeline: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 30,
      exec: 'timeline',
      startTime: '70s',
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
  },
};

export function setup() {
  const response = http.post(
    `${baseUrl}/api/auth/dev/seed?email=benchmark-target@nemo.local`,
  );
  const tokenIssued = check(response, {
    'token issued': (result) => result.status === 200,
  });

  if (!tokenIssued) {
    throw new Error(`Token request failed with status ${response.status}`);
  }

  return {
    headers: {
      Authorization: `Bearer ${response.json('accessToken')}`,
    },
  };
}

function get(path, data, api) {
  const response = http.get(`${baseUrl}${path}`, {
    headers: data.headers,
    tags: { api },
  });
  check(response, {
    [`${api} 200`]: (result) => result.status === 200,
  });
}

export function albums(data) {
  get('/api/albums?ownership=OWNED&page=0&size=10', data, 'albums');
}

export function photos(data) {
  get('/api/photos?page=0&size=20&sort=takenAt,desc', data, 'photos');
}

export function timeline(data) {
  get('/api/timeline?year=2025&month=1', data, 'timeline');
}
