import http from 'k6/http';
import { Counter, check } from 'k6/metrics';
const coldFailures = new Counter('cold_failures');
const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';
export const options = {
  scenarios: { cold_burst: { executor: 'per-vu-iterations', vus: 24, iterations: 1, maxDuration: '10s' } },
  thresholds: { cold_failures: ['count>0'] },
};
export default function () {
  const response = http.get(baseUrl + '/api/products/1');
  if (!response || response.status === 503) coldFailures.add(1);
  check(response || { status: 0 }, { 'returns success or expected 503': value => value.status === 200 || value.status === 503 });
}
