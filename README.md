# JVM Cold-Path / HikariCP 재현 랩

카카오페이의 배포 직후 HikariCP timeout 사례를 Spring MVC/JPA/MySQL로 분해한 실험 프로젝트다.

~~~text
DB 조회로 커넥션 획득
→ 첫 사용 JVM 작업이 커넥션을 잡은 채 오래 실행
→ 커넥션 반환 지연
→ pool 전체 고갈
→ 뒤 요청의 Hikari acquire timeout
~~~

simulate 프로필은 실제 SELECT 뒤 커넥션을 명시적으로 점유한 상태에서 단일 초기화 락을 기다리게 해, 커넥션 점유 시간 증가가 pool을 고갈시키는 인과관계를 결정적으로 재현한다. observe 프로필은 실제 JVM class-load 로그/JFR을 수집한다.

## 준비물

- Java 21
- Docker Desktop
- Gradle은 wrapper 사용
- 선택: kind, kubectl

## 1. MySQL 시작

~~~bash
docker compose up -d mysql
docker compose ps
~~~

MySQL의 health가 healthy가 된 뒤 진행한다.

## 2. Cold run: timeout 재현

터미널 A:

~~~bash
./gradlew bootRun --args='--spring.profiles.active=cold'
~~~

터미널 B:

~~~bash
curl http://localhost:8080/api/lab/status
docker run --rm -i \
  -e BASE_URL=http://host.docker.internal:8080 \
  grafana/k6:0.54.0 run - < k6/cold.js
~~~

cold_failures가 1 이상이면 재현 성공이다. 일부 요청은 503으로 끝나며, DB가 아니라 Hikari에서 빌릴 커넥션이 없어 실패한 것이다.

실행 중 또는 직후 아래 지표를 확인한다.

~~~bash
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active
curl http://localhost:8080/actuator/metrics/hikaricp.connections.pending
curl http://localhost:8080/actuator/metrics/hikaricp.connections.acquire
curl http://localhost:8080/actuator/metrics/hikaricp.connections.usage
~~~

예상: active는 max 10에 도달하고, pending/acquire/usage가 커진다. MySQL의 단순 PK 조회는 느려지지 않았다는 점이 포인트다.

같은 프로세스로 재실험하려면 초기화 완료 후 gate를 리셋한다.

~~~bash
curl -X POST http://localhost:8080/api/lab/cold-path/reset
~~~

## 3. Warm run: 해결 검증

Cold 애플리케이션을 종료한 뒤 새 JVM으로 실행한다.

~~~bash
./gradlew bootRun --args='--spring.profiles.active=warm'
curl http://localhost:8080/api/lab/status
curl http://localhost:8080/actuator/health/startup
docker run --rm -i \
  -e BASE_URL=http://host.docker.internal:8080 \
  grafana/k6:0.54.0 run - < k6/warm.js
~~~

ApplicationRunner가 사용자 요청 전 ProductService.find 경로를 실행한다. 따라서 실제 요청은 이미 초기화된 경로를 타며, warm k6의 실패율은 1% 미만·p95는 500ms 미만이어야 한다.

## 4. Observe run: 실제 JVM class loading 관찰

이 실험은 반드시 새 JVM에서 수행한다.

~~~bash
mkdir -p build/jfr build/logs
JAVA_TOOL_OPTIONS='-XX:StartFlightRecording=filename=build/jfr/observe.jfr,dumponexit=true,settings=profile -Xlog:class+load=info:file=build/logs/classload.log' \
  ./gradlew bootRun --args='--spring.profiles.active=observe'
~~~

다른 터미널에서 첫 요청 한 번을 보낸 뒤 애플리케이션을 종료한다.

~~~bash
curl http://localhost:8080/api/products/1
rg 'springframework|hibernate|jackson|querydsl' build/logs/classload.log
jfr print --events jdk.ClassLoad build/jfr/observe.jfr | head -80
~~~

관찰 모드는 첫 API 호출 뒤에도 웹/JPA/직렬화 관련 클래스가 초기화될 수 있음을 보여준다. timeout을 항상 재현하는 증거는 2번 simulate 실험이다.

## 5. 풀 크기만 늘려 보기

~~~bash
HIKARI_MAX_POOL_SIZE=20 ./gradlew bootRun --args='--spring.profiles.active=cold'
~~~

실패 시점은 늦어지지만 cold path가 커넥션을 오래 점유하는 한 충분한 동시 요청에서 다시 고갈된다. 해결책은 단순 증설이 아니라 사용자 트래픽 유입 전 hot path 웜업이다.

## 6. 자동 테스트

~~~bash
./gradlew clean test
~~~

Testcontainers MySQL 통합 테스트는 작은 풀에서 cold path가 실패를 유발하고, 같은 경로를 미리 호출한 뒤에는 동시 요청이 성공하는지 검증한다.

## 7. 선택: kind + startupProbe

~~~bash
kind create cluster --name warmup-lab
docker build -t jvm-warmup-lab:local .
kind load docker-image jvm-warmup-lab:local --name warmup-lab
kubectl apply -f k8s/mysql.yaml
kubectl wait --for=condition=available deployment/mysql --timeout=120s
~~~

Cold 배포:

~~~bash
kubectl apply -f k8s/app-cold.yaml
kubectl rollout status deployment/jvm-warmup-lab
kubectl apply -f k8s/k6-cold-job.yaml
kubectl logs -f job/jvm-warmup-cold-k6
~~~

Warm 배포:

~~~bash
kubectl delete -f k8s/app-cold.yaml
kubectl apply -f k8s/app-warm.yaml
kubectl rollout status deployment/jvm-warmup-lab
kubectl apply -f k8s/k6-warm-job.yaml
kubectl logs -f job/jvm-warmup-warm-k6
~~~

Warm 배포에서는 startupProbe가 warmup 완료 전 readiness를 열지 않는다.

정리:

~~~bash
kind delete cluster --name warmup-lab
docker compose down -v
~~~

## 발표용 결론

1. Hikari timeout은 원인이 아니라 증상이다.
2. DB query time이 정상이어도, 커넥션을 보유한 JVM 작업이 처리량을 급락시킬 수 있다.
3. 웜업은 API를 한 번 호출하는 트릭이 아니라 사용자 트래픽 전 hot path를 준비하는 배포 단계다.
4. Kubernetes에서는 startupProbe와 readiness를 연결해야 사용자가 cold path 비용을 내지 않는다.
