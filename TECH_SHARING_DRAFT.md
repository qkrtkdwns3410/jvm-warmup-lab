# 배포 직후 HikariCP Timeout은 왜 발생했을까?

## JVM Cold Path로 인한 커넥션 풀 고갈 재현과 웜업 검증

> **한 줄 요약**
>
> HikariCP timeout은 DB가 느리거나 커넥션 풀이 작다는 뜻이 아닐 수 있다.
> DB 커넥션을 빌린 뒤 애플리케이션 내부 작업이 길어지면, 커넥션이 제때 반환되지 않아 풀이 고갈되고 이후 요청이 실패할 수 있다.

참고 사례: [카카오페이 - 배포 직후 발생하는 응답 지연을 해결하기 위한 여정](https://tech.kakaopay.com/post/jvm-warm-up/)

---

## 1. 개요

### 1.1 이 글에서 다루는 문제

배포 직후 아래와 같은 오류가 발생한다고 가정한다.

~~~text
HikariPool - Connection is not available,
request timed out after 10000ms
~~~

이 오류를 보면 보통 아래를 먼저 의심한다.

- DB 쿼리가 느린가?
- 슬로우 쿼리가 발생했는가?
- HikariCP 최대 커넥션 수가 작은가?
- 커넥션 획득 timeout을 늘려야 하는가?

모두 확인해야 하는 항목이다. 하지만 이번 실험이 다루는 가설은 다르다.

> DB 쿼리는 빠르게 끝났지만, DB 커넥션을 반환하기 전에 JVM 내부 초기화 작업이 오래 걸릴 수 있다.

이 경우 HikariCP는 고장 난 것이 아니다. 이미 빌려 간 커넥션이 돌아오지 않아 새 요청에 줄 커넥션이 없는 상태다.

### 1.2 사전 개념: 커넥션 풀

애플리케이션은 DB 쿼리마다 TCP 연결을 새로 만들지 않는다. 미리 만들어 둔 DB 연결을 빌리고, 사용이 끝나면 반납한다.

~~~mermaid
flowchart LR
    A[애플리케이션 요청] --> B[HikariCP 커넥션 풀]
    B --> C[대여 가능한 커넥션]
    C --> D[MySQL]
    D --> C
    C --> B
    B --> A
~~~

커넥션 풀의 주요 상태는 아래와 같다.

| 상태 | 의미 |
| --- | --- |
| idle | 아무도 사용하지 않아 즉시 빌릴 수 있는 커넥션 |
| active | 요청이 빌려서 사용 중인 커넥션 |
| pending | idle 커넥션이 없어 대기 중인 요청 |

최대 커넥션 수가 10개이고 10개가 모두 active라면, 11번째 요청은 누군가 커넥션을 반납할 때까지 기다린다. 이 대기 시간이 connection timeout을 넘으면 요청은 실패한다.

~~~text
커넥션 풀 최대 크기: 10
active: 10
idle: 0
pending: 13

새 요청은 커넥션을 받을 수 없음
→ timeout까지 대기
→ timeout 초과 시 실패
~~~

### 1.3 실험 목표

이번 실험은 아래 질문에 답하기 위해 만들었다.

1. DB 쿼리가 느리지 않아도 HikariCP timeout이 발생할 수 있는가?
2. 커넥션을 오래 점유하는 JVM cold path가 풀을 고갈시키는가?
3. pool 크기만 늘리는 것이 근본 해결책인가?
4. 사용자 트래픽 전에 hot path를 미리 실행하면 문제가 완화되는가?
5. Kubernetes에서 startupProbe와 readiness를 함께 고려해야 하는 이유는 무엇인가?

### 1.4 실험 환경

| 항목 | 구성 |
| --- | --- |
| 애플리케이션 | Spring Boot 3.5.6 |
| Java | Java 21 |
| 웹 서버 | Spring MVC / Tomcat |
| ORM | Spring Data JPA / Hibernate |
| DB | MySQL 8.4 |
| 커넥션 풀 | HikariCP |
| HikariCP 최대 커넥션 수 | 10 |
| 커넥션 획득 timeout | 700ms |
| 부하 도구 | k6 |
| 동시 요청 수 | 24 |

---

## 2. 문제 상황

### 2.1 배포 직후에만 느려질 수 있는 이유

애플리케이션이 기동됐다고 해서 모든 코드와 프레임워크 내부 경로가 이미 실행된 것은 아니다. 실제 사용자의 첫 요청에서 아래 작업이 추가로 발생할 수 있다.

- 아직 사용되지 않은 Spring MVC 처리 경로 초기화
- Jackson 직렬화와 역직렬화 관련 클래스 로딩
- Hibernate/JPA/JDBC 관련 코드 경로 초기화
- QueryDSL 또는 프록시 관련 클래스 초기화
- JIT 컴파일을 위한 실행 정보 축적
- 처음 쓰는 HTTP, Redis, Kafka client의 연결 생성
- 캐시 초기화

이런 작업은 보통 처음 한 번만 발생한다. 따라서 평소에는 빠르지만 배포 직후만 느려질 수 있다.

### 2.2 문제가 되는 순서

초기화 작업이 존재한다는 사실 자체가 문제는 아니다. **DB 커넥션을 빌린 뒤에 초기화 작업이 실행되는 순서**가 문제다.

~~~mermaid
sequenceDiagram
    participant U as 사용자
    participant A as Spring 애플리케이션
    participant P as HikariCP
    participant D as MySQL
    participant J as JVM Cold Path

    U->>A: GET /api/products/1
    A->>P: DB 커넥션 요청
    P-->>A: 커넥션 대여
    A->>D: SELECT 실행
    D-->>A: 조회 결과 반환
    Note over A: 아직 커넥션은 반납되지 않음
    A->>J: 첫 사용 경로 초기화
    Note over J: 클래스 로딩, 프록시 초기화,<br/>직렬화 경로 준비 등
    J-->>A: 초기화 완료
    A->>P: 커넥션 반납
    A-->>U: 응답
~~~

정상적인 상황에서는 아래 과정이 매우 짧다.

~~~text
커넥션 대여 → DB 조회 → 커넥션 반납
~~~

하지만 cold path 때문에 커넥션 점유 시간이 길어지면 아래가 된다.

~~~text
커넥션 대여 → DB 조회 → JVM 초기화 작업 → 커넥션 반납
                              ↑
                       이 구간이 길어짐
~~~

### 2.3 동시 요청에서 문제가 커지는 이유

첫 요청 하나만 느리다면 영향은 작을 수 있다. 문제는 배포 직후 여러 요청이 동시에 들어올 때다.

~~~mermaid
sequenceDiagram
    participant R1 as 요청 1
    participant R2 as 요청 2
    participant R3 as 요청 3
    participant P as HikariCP
    participant J as 초기화 락

    R1->>P: 커넥션 대여
    R2->>P: 커넥션 대여
    R3->>P: 커넥션 대여

    R1->>J: 초기화 락 획득
    Note over R1,J: 초기화 수행 중<br/>커넥션 유지

    R2->>J: 초기화 락 대기
    R3->>J: 초기화 락 대기

    Note over R2,R3: 락을 기다리는 동안에도<br/>이미 빌린 커넥션은 반납하지 못함
~~~

요청 1만 초기화 작업을 실제로 수행하더라도, 요청 2와 요청 3도 초기화 락을 기다리는 동안 커넥션을 점유할 수 있다. 따라서 느린 요청 하나가 아니라, 초기화 락을 기다리는 여러 요청이 동시에 커넥션 풀을 잠식하게 된다.

### 2.4 timeout이 발생하는 전체 흐름

~~~mermaid
flowchart TD
    A[새 Pod 배포] --> B[사용자 요청 동시 유입]
    B --> C[요청별 DB 조회 수행]
    C --> D[각 요청이 DB 커넥션 보유]
    D --> E[첫 사용 JVM 초기화 락 대기]
    E --> F[커넥션 반납 지연]
    F --> G[HikariCP active가 max에 도달]
    G --> H[새 요청은 pending 상태]
    H --> I{connection timeout 초과?}
    I -- 예 --> J[HikariCP timeout과 5xx]
    I -- 아니오 --> H
~~~

HikariCP는 이 상황에서 정상적으로 동작한다.

1. 빌려 줄 idle 커넥션이 없다.
2. 기존 요청이 커넥션을 반납하기를 기다린다.
3. 설정된 timeout까지 기다린다.
4. 끝까지 반납되지 않으면 예외를 던진다.

### 2.5 재현 방식과 한계

실제 JVM class loading 시간은 머신 성능, JVM 버전, JIT 상태, 라이브러리 구성에 따라 달라진다. 따라서 실제 class loading만으로는 모든 환경에서 timeout을 안정적으로 재현하기 어렵다.

그래서 프로젝트는 두 모드를 제공한다.

| 모드 | 목적 |
| --- | --- |
| observe | 새 JVM에서 첫 API 호출 전후의 class-load 로그와 JFR을 관찰 |
| cold 또는 simulate | 커넥션 점유 시간을 고정해 timeout을 결정적으로 재현 |

simulate 모드에서는 아래 순서를 강제한다.

1. JPA로 상품을 조회한다.
2. 실제 MySQL SELECT를 수행한다.
3. 물리 커넥션을 반납하지 않은 상태를 유지한다.
4. 여러 요청이 단일 초기화 락을 기다리게 한다.
5. 커넥션 풀이 고갈되는지 확인한다.

> 이 실험은 실제 서비스의 클래스 로딩 시간이 정확히 1.5초라는 것을 주장하지 않는다.
> 커넥션을 점유한 애플리케이션 작업이 길어질 때 풀 고갈이 발생한다는 인과관계를 검증한다.

### 2.6 실제 테스트 결과

cold 프로필에서 아래 조건으로 부하를 실행했다.

| 설정 | 값 |
| --- | ---: |
| 초기화 대기 시간 | 1.5초 |
| HikariCP 최대 풀 크기 | 10 |
| connection timeout | 700ms |
| 동시 요청 | 24 |

| 항목 | 결과 |
| --- | ---: |
| 총 요청 수 | 24 |
| 성공 요청 수 | 10 |
| 실패 요청 수 | 14 |
| 실패율 | **58.33%** |
| p95 응답 시간 | 약 1.69초 |
| active connection | 10 |
| idle connection | 0 |
| pending request | 13 |

실제 로그는 아래와 같았다.

~~~text
HikariPool-1 - Connection is not available,
request timed out after 702ms
(total=10, active=10, idle=0, waiting=13)
~~~

해석은 아래와 같다.

~~~text
total=10   → 전체 커넥션 수는 10개
active=10  → 10개 모두 다른 요청이 사용 중
idle=0     → 즉시 빌려 줄 커넥션이 없음
waiting=13 → 13개 요청이 커넥션을 기다리는 중
~~~

DB 단순 조회 자체가 느려서가 아니라 커넥션이 제때 반납되지 않아 실패한 것이다.

---

## 3. 해결 방안

### 3.1 pool 크기 증가가 근본 해결이 아닌 이유

가장 먼저 떠올릴 수 있는 해결책은 최대 풀 크기를 늘리는 것이다.

~~~yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
~~~

이 설정이 항상 나쁜 것은 아니다. 그러나 커넥션을 오래 점유하는 원인을 제거하지는 못한다.

~~~text
기존: 10개가 막히면 timeout 시작
증설: 20개가 막히면 timeout 시작
~~~

실패 시점이 뒤로 밀릴 뿐, 충분한 동시 요청이 들어오면 다시 같은 문제가 발생한다.

### 3.2 해결의 핵심: 사용자보다 먼저 hot path를 실행한다

해결 방향은 사용자 요청 전에 실제 서비스에서 자주 호출되는 경로를 실행하는 것이다.

~~~java
@Override
public void run(ApplicationArguments args) {
    if (!properties.isWarmupEnabled()) {
        return;
    }

    state.markPending();
    productService.find(1L);
    state.markCompleted();
}
~~~

이 코드의 흐름은 단순하다.

1. 애플리케이션이 기동된다.
2. 아직 사용자 트래픽은 받지 않는다.
3. 실제 API가 사용할 서비스 경로를 미리 실행한다.
4. cold path 초기화가 끝난 상태가 된다.
5. 이후 사용자 요청은 이미 준비된 경로를 탄다.

### 3.3 단순 ping API 웜업으로 부족한 이유

아래처럼 단순한 health check만 호출하면 실제 API 경로가 준비되지 않을 수 있다.

~~~text
GET /ping
→ 200 OK
~~~

실제 API가 아래 작업을 한다면 ping API는 충분하지 않다.

~~~text
Controller
→ Service
→ JPA Repository
→ Hibernate
→ JDBC
→ MySQL
→ DTO 조합
→ Jackson 직렬화
~~~

따라서 웜업 대상은 가능한 한 아래 조건을 만족해야 한다.

- 실제 사용자가 많이 호출하는 API 경로
- JPA/JDBC/외부 client가 실제로 사용되는 경로
- 초기 지연이 큰 경로
- 캐시만 조회하지 않고 실제 객체 조합을 포함하는 경로

이번 실험에서는 ProductService.find를 hot path로 선정했다.

### 3.4 웜업만으로 충분하지 않은 이유

웜업이 진행되는 동안 로드밸런서가 사용자 요청을 Pod로 보내면 사용자가 초기화 비용을 지불할 수 있다.

~~~mermaid
sequenceDiagram
    participant K as Kubernetes
    participant P as Pod
    participant W as Warmup
    participant U as 사용자

    K->>P: 컨테이너 시작
    P->>W: hot path 웜업 시작
    U->>P: 사용자 요청 유입
    Note over U,P: 웜업 중인 Pod로 요청이 들어오면<br/>사용자가 초기화 비용을 부담
~~~

따라서 웜업 완료 전에는 사용자 트래픽을 받지 않도록 제어해야 한다.

### 3.5 startupProbe와 readiness 연결

프로젝트는 warmup 완료 여부를 Actuator health endpoint로 노출한다.

~~~text
/actuator/health/startup
~~~

웜업 전 상태:

~~~json
{
  "status": "DOWN",
  "details": {
    "warmup": "pending"
  }
}
~~~

웜업 후 상태:

~~~json
{
  "status": "UP",
  "details": {
    "warmup": "completed"
  }
}
~~~

Kubernetes에는 아래처럼 적용한다.

~~~yaml
startupProbe:
  httpGet:
    path: /actuator/health/startup
    port: 8080
  periodSeconds: 2
  failureThreshold: 45

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  periodSeconds: 2
~~~

~~~mermaid
flowchart LR
    A[Pod 시작] --> B[ApplicationRunner 실행]
    B --> C[실제 hot path 호출]
    C --> D{웜업 완료?}
    D -- 아니오 --> E[startupProbe 실패]
    E --> B
    D -- 예 --> F[warmup health UP]
    F --> G[readiness 통과]
    G --> H[Service Endpoint 등록]
    H --> I[사용자 트래픽 유입]
~~~

핵심은 아래 한 문장으로 정리할 수 있다.

> 웜업은 애플리케이션 내부 작업이고, startupProbe와 readiness는 그 작업이 끝날 때까지 사용자를 보호하는 배포 제어 장치다.

### 3.6 해결 관련 테스트

warm 프로필에서는 애플리케이션 기동 중 ProductService.find를 먼저 실행한다. 이후 cold와 동일한 조건으로 k6 부하를 실행했다.

| 조건 | Cold | Warm |
| --- | ---: | ---: |
| 동시 요청 수 | 24 | 24 |
| HikariCP 최대 풀 크기 | 10 | 10 |
| 초기화 대기 시간 | 1.5초 | 1.5초 |
| 사용자 요청이 초기화 비용 부담 | 예 | 아니오 |
| 성공 요청 수 | 10 | 24 |
| 실패 요청 수 | 14 | 0 |
| 실패율 | 58.33% | **0%** |
| p95 응답 시간 | 약 1.69초 | **약 92.51ms** |

Warm 실행 결과:

~~~text
checks: 100.00% (24 / 24)
http_req_failed: 0.00%
http_req_duration p95: 약 92.51ms
~~~

웜업 자체는 1.5초가 걸렸지만 그 비용은 사용자가 아니라 애플리케이션 기동 단계에서 처리됐다.

~~~text
Cold: 사용자 요청 → 초기화 비용 부담 → timeout 가능성
Warm: 애플리케이션 기동 → 초기화 비용 부담 → 완료 후 사용자 요청
~~~

### 3.7 자동화된 검증

Testcontainers 기반 통합 테스트로 아래를 자동 검증한다.

1. 작은 HikariCP 풀에서 cold path가 동시 요청 실패를 유발하는가?
2. 동일 경로를 미리 실행한 뒤에는 동시 요청이 성공하는가?
3. warmup 전 health 상태는 DOWN인가?
4. warmup 완료 후 health 상태는 UP인가?

실행 명령:

~~~bash
./gradlew clean test
~~~

검증 결과:

~~~text
BUILD SUCCESSFUL
~~~

---

## 4. 결론

### 4.1 핵심 결론

이번 실험을 통해 확인한 내용은 아래와 같다.

1. **HikariCP timeout은 원인이 아니라 증상일 수 있다.**
2. DB 쿼리가 빠르더라도, 커넥션을 보유한 애플리케이션 내부 작업이 길면 풀은 고갈될 수 있다.
3. maximumPoolSize 증가는 증상을 늦출 수 있지만 원인을 제거하지는 못한다.
4. 배포 직후 문제는 사용자가 첫 요청을 보냈을 때만 나타날 수 있다.
5. 웜업은 사용자 트래픽 전에 실제 hot path를 준비하는 과정이어야 한다.
6. Kubernetes에서는 startupProbe와 readiness를 함께 사용해야 사용자가 cold path 비용을 지불하지 않는다.

### 4.2 우리 서비스에서 발생하지 않을 수 있는 이유

동일한 문제가 모든 Spring 서비스에서 발생하는 것은 아니다. 아래 조건 중 하나라도 다르면 눈에 띄는 장애가 발생하지 않을 수 있다.

| 가능성 | 설명 |
| --- | --- |
| 이미 warm 상태 | health check나 기동 과정에서 실제 경로가 이미 실행됨 |
| 트래픽 유입이 완만함 | 새 Pod가 처음부터 대량 요청을 받지 않음 |
| hot path가 가벼움 | 초기화 비용이 작아 pool 고갈까지 가지 않음 |
| 커넥션 점유 범위가 짧음 | 조회 후 DB와 무관한 작업 전에 커넥션이 반환됨 |
| pool 여유가 큼 | 트래픽 대비 충분한 커넥션 수가 있음 |
| 관측되지 않음 | 잠깐의 latency 상승이 장애 기준 아래에 있었음 |

따라서 이 발표의 메시지는 “우리 서비스에도 동일 장애가 있다”가 아니다.

> 배포 직후 Hikari timeout이 발생했을 때, DB와 pool 설정만 보지 말고 **커넥션을 보유한 애플리케이션 내부 작업**까지 진단 범위에 포함해야 한다.

### 4.3 운영 환경 확인 항목

실제 서비스에서 비슷한 현상이 의심되면 배포 시각 기준으로 아래 지표를 함께 본다.

| 영역 | 확인 항목 | 의심 신호 |
| --- | --- | --- |
| HikariCP | active, idle, pending | 배포 직후 active 급증, idle 0 |
| HikariCP | acquire, usage time | 배포 직후에만 급증 |
| DB | query latency | 평소와 큰 차이가 없음 |
| API | Pod 생성 직후 latency와 5xx | 새 Pod에서만 느리거나 실패 |
| JVM | JFR, class-load 로그, CPU | 첫 요청 시점에 초기화 활동 증가 |

아래 조합이 보이면 cold path 가능성이 높다.

~~~text
배포 직후
 + Hikari usage/acquire time 증가
 + DB query time은 정상
 + 새 Pod의 API latency만 증가
 = cold path가 커넥션을 오래 점유했을 가능성
~~~

### 4.4 한계와 후속 과제

이번 실험은 커넥션 점유 시간 증가의 인과관계를 재현하는 데 초점을 맞췄다. 실제 운영 환경에서는 아래 추가 검증이 필요하다.

- 실제 어떤 클래스 또는 코드 경로가 지연을 유발하는지 JFR로 확인
- 웜업 대상 API 선정 기준 수립
- Redis, HTTP client, Kafka producer 등 외부 client 초기화 여부 확인
- 웜업이 DB와 외부 시스템에 과도한 부하를 주지 않는지 확인
- 여러 Pod가 동시에 배포될 때 웜업 부하가 집중되지 않는지 확인
- 조회 API와 쓰기 API의 웜업 전략을 분리할 필요가 있는지 검토

---

## 발표 마무리 문장

> 처음에는 HikariCP timeout을 보고 pool 크기를 의심했다.
> 하지만 풀은 고장 난 것이 아니라, 반환되지 않는 커넥션 때문에 정상적으로 고갈되고 있었다.
> 중요한 것은 커넥션 수를 늘리는 것이 아니라, 사용자가 cold path를 밟지 않도록 배포 과정을 설계하는 것이었다.
