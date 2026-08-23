import http from 'k6/http';
import { check } from 'k6';
const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';
export const options = {
  scenarios: { warm_burst: { executor: 'per-vu-iterations', vus: 24, iterations: 1, maxDuration: '10s' } },
  thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<500'] },
};
export default function () {
  const response = http.get(baseUrl + '/api/products/1');
  check(response || { status: 0 }, { 'warm run succeeds': value => value.status === 200 });
}
