# v0.1.0 검증 기록

검증 시각: 2026-10-12 20:59 UTC. 운영 서버/기존 OBS 위젯 서비스에는 배포하거나 설정을 변경하지 않았다.

## 검증한 배포 파일

- 파일: `chzzk-donation-api-fabric-1.21.1-0.1.0.jar`
- 크기: 72,636 bytes
- SHA-256: `ba6f93069a3cc69f275f40e7f6dc248da6072efebb6b2aca6bcccf9af4880bad`
- Minecraft 1.21.1 / Java 21 / Fabric Loader 0.16.14 / Fabric API 0.116.17+1.21.1

## 자동 테스트 및 빌드

실행:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
bash gradlew --no-daemon clean test build qaClasses compileExampleJava
```

결과: **40 tests, 0 failures, 0 errors, 0 skipped**, `BUILD SUCCESSFUL`.

범위:
- 후원 실시간 패킷 및 일반 채팅/이력 구분.
- extras/profile 표현, 금액 유효성·충돌, 긴 ID/메시지 중복키.
- 저장 후 재오픈 중복 차단, 손상 저장소 거부 및 저장 실패 처리.
- 연결 ACK, 서버 ping, 종료/재연결 수명주기.
- HTTP 헤더 이후 본문 멈춤 시간 제한, 과대 응답 제한, 요청 중 종료.
- 설정 JSON 타입/중복/UUID 검증.
- IO 대기열 초과, 실패 상태 reload 차단, 접속 epoch, 수신→저장→이벤트 경로.
- 개발자 소비 모드 예제 컴파일.

## 실제 Minecraft 런타임

`python scripts/runtime_smoke.py`를 통해 격리된 Xvfb 클라이언트와 로컬 테스트 서버를 실행했다.

**전용 서버와 싱글플레이 내장 서버 모두 통과**, 정상 종료 코드 0.

- 실제 Minecraft 클라이언트 접속/싱글플레이 월드 로딩.
- status/reload/test 명령 동작.
- TEST 콜백이 논리 서버 스레드에서 전달.
- 고의로 예외를 던지는 테스트 리스너가 다음 리스너를 막지 않음.
- TEST 명령이 실제 후원용 RECEIVED 리스너를 호출하지 않음.
- 별도 QA 전용 가상 입력을 실제 수신 처리 경로에 넣어, RECEIVED 1회 전달·중복 1회 억제·미접속 차단·접속 epoch 변경 차단 확인.
- 이 가상 입력은 **실제 치지직 유료 후원이 아니다**. QA 모드/fixture는 배포 JAR에서 제외.

QA용 고의 리스너 예외 경고는 예상된 출력이다. 초기 QA 도구는 JOIN 즉시 플레이어 명령을 실행해 실패했으며, 플레이어 등록 완료 후 실행하도록 고친 뒤 위 결과를 다시 확인했다.

## 배포 JAR 단독 실행

`python scripts/packaged_smoke.py`로 remap된 위 SHA-256의 JAR를 별도 Fabric 서버 런처에 설치해 확인했다.

- Minecraft 1.21.1 시작 완료.
- API 초기화, 빈 기본 설정 생성, status/reload 성공.
- QA 동반 모드가 없는 상태에서 정상 실행/종료.

## 실제 치지직 연결

배포 코드와 같은 Java `ChzzkClient`로 실제 상위 서비스에 직접 접속했다.

- source 조회 → 읽기 토큰 → WebSocket 연결 → 성공 join ACK 확인.
- 출력: `JAVA_DIRECT_HANDSHAKE_PASS paid_donation_verified=false`.
- 종료 후 출력: `owned_threads_after_close=0`.
- 비밀 소스 값은 stdin으로만 전달했으며 공개 패키지에 넣지 않았다.

## 검증하지 않은 범위

- **실제 유료 후원 → 특정 콘텐츠 모드의 게임 효과까지 전체 검증은 미실시.**
- 모든 후원 유형(영상/미션/특수 알림 등) 지원을 보장하지 않는다. 알려진 후원 메시지 형식을 처리하는 초기 API다.
- 대규모 장기 방송 부하/장시간 네트워크 장애/치지직 프로토콜 변경 대응은 별도 운영 검증 필요.
- 연결 성공만으로 실제 후원 인식률이나 결제 정산 정확도를 증명하지 않는다.
- 공식 CHZZK OAuth SDK 또는 공식 인증 프로그램이 아니다.

## 원본 증거 경로

프로젝트 `artifacts/qa/`:
- `build.log`, `runtime-both-result.json`
- `dedicated.log`, `multiplayer-client.log`, `integrated-client.log`
- `packaged-jar.log`, `packaged-jar-result.json`
- `upstream-java-probe.log`, `upstream-python-probe.json`

테스트 XML/HTML은 `build/test-results/test/`, `build/reports/tests/test/`에 있다. 로그·월드·실제 연결 설정은 공개 패키지에 포함하지 않는다.
