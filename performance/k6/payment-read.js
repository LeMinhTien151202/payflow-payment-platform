import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    payment_read: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: 10,
      maxVUs: 100,
      stages: [
        { target: Number(__ENV.TARGET_RPS || 10), duration: '30s' },
        { target: Number(__ENV.TARGET_RPS || 10), duration: __ENV.DURATION || '2m' },
        { target: 0, duration: '15s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

const baseUrl = __ENV.BASE_URL || 'http://localhost:8084';
const paymentId = __ENV.PAYMENT_ID;
const token = __ENV.ACCESS_TOKEN;

export function setup() {
  if (!paymentId || !token) throw new Error('PAYMENT_ID and ACCESS_TOKEN are required');
}

export default function () {
  const response = http.get(`${baseUrl}/api/v1/payments/${paymentId}`, {
    headers: { Authorization: `Bearer ${token}`, 'X-Correlation-Id': `k6-${__VU}-${__ITER}` },
  });
  check(response, { 'payment read is 200': (r) => r.status === 200 });
}
