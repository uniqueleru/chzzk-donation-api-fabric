# Fabric 소비 모드 개발 API

대상: Minecraft 1.21.1 / Java 21 / Yarn `1.21.1+build.3`.
모드 ID: `chzzk_donation_api`. 현재 개발 API 버전: `0.1.0` (초기 API, 향후 변경 가능).

## 의존성

배포된 기본 JAR를 소비 프로젝트의 `libs/`에 넣는다.

```groovy
dependencies {
    modImplementation files('libs/chzzk-donation-api-fabric-1.21.1-0.1.0.jar')
}
```

소비 모드 `fabric.mod.json`에도 필수 의존성을 명시한다:

```json
"depends": {
  "minecraft": "1.21.1",
  "java": ">=21",
  "fabricloader": ">=0.16.14",
  "fabric-api": "*",
  "chzzk_donation_api": ">=0.1.0 <0.2.0"
}
```

공개 Maven 저장소에 업로드된 상태는 아니다. 기본은 별도 API 모드 JAR를 설치하는 방식이며, 각 소비 모드 안에 중복 번들하지 않는다.

## 등록

```java
import dev.leru.chzzk.api.ChzzkDonationEvents;
import net.fabricmc.api.ModInitializer;
import net.minecraft.text.Text;

public final class MyContentMod implements ModInitializer {
    @Override
    public void onInitialize() {
        ChzzkDonationEvents.RECEIVED.register((server, donation) -> {
            var streamer = server.getPlayerManager().getPlayer(donation.streamerUuid());
            if (streamer == null) return;
            if (donation.amount() >= 10000L) {
                streamer.sendMessage(Text.literal("후원 조건 달성!"));
                // 여기에서 원하는 효과를 구현한다.
            }
        });
    }
}
```

- `RECEIVED`: 치지직 직접 연결에서 인식한 실제 후원 이벤트. `donation.test() == false`.
- `TEST`: 운영자 테스트 명령으로 생성. `donation.test() == true`. 자동으로 `RECEIVED`에 전달되지 않는다.
- 리스너 인자: `MinecraftServer server, Donation donation`.
- 실행 문맥: **논리 서버 메인 스레드**. 일반적인 월드/엔티티 변경은 이 스레드에서 가능하다. HTTP/대용량 파일 처리 같은 블로킹 작업은 하지 않는다.
- 리스너의 일반적인 런타임 예외는 다른 리스너의 호출을 막지 않도록 격리한다. 치명적인 JVM 오류까지 복구하는 것은 아니다.
- 이벤트에 전체 `Player` 객체를 보관하지 않는다. UUID로 현재 플레이어를 다시 조회한다.
- 가입 코드는 한 번 등록하고, 서버/월드 종료 후 이전 `MinecraftServer` 참조를 보관하지 않는다.

## Donation 필드

- `bindingId()` — 운영자 설정의 비밀이 아닌 연결 이름.
- `streamerUuid()` — 후원한 시청자가 아니라 **후원받은 방송인**의 게임 UUID.
- `eventId()` — 중복 판별용 이벤트 식별자. 경우에 따라 원본 ID 대신 안정적인 해시로 생성.
- `donor()` — 표시용 후원자명. 익명 가능하며 신원 인증용이 아님.
- `message()` — 표시용 후원 문구. 길이 제한/정리될 수 있으므로 원문 정산 자료로 사용하지 않는다.
- `amount()` — 수신 데이터에 명시된 양의 정수 후원 값 (`long`). UI 표기 및 플랫폼 금액 의미는 실제 후원 검증으로 확인한다. 일반적으로 치즈 후원 금액 조건에 사용한다.
- `timestampMillis()` — 후원 시각 정보(밀리초), 없는 경우 수신 처리 시각.
- `test()` — 테스트 여부.

소스 값/접속 토큰은 공개 이벤트에 포함하지 않는다.

## 멱등성과 실패 처리

플랫폼 이벤트 식별자만을 글로벌 키로 쓰지 말고, 소비 모드의 저장소에서 `(bindingId, eventId)` 또는 안정적인 방송인 매핑을 포함한 키를 사용한다. 운영 중 연결 ID를 임의로 바꾸면 소비 모드의 키도 달라진다.

라이브러리는 수신 기록을 먼저 저장한 뒤 이벤트를 전달한다. 저장 후 크래시/서버 정지/대기열 초과/방송인 퇴장이 일어나면 효과가 실행되지 않을 수 있다. 이는 중복 지급보다 누락을 우선하는 정책이다. 리스너 실패를 라이브러리가 자동 재실행하지 않는다.

중요한 지급은 소비 모드에서 **지급 기록과 효과의 원자성 또는 복구 절차**를 설계해야 한다. 모드가 로드된 서버의 다른 코드가 Fabric 이벤트 invoker를 직접 호출하는 것은 신뢰 경계 밖이며, 이 API가 악성 서버 모드까지 방어하는 것은 아니다.

## 테스트

예제는 `TEST`에 따로 가입하여 테스트 알림을 표시한다. 실서비스의 유료 보상 로직을 TEST에 연결할지 여부는 소비 모드가 명시적으로 결정한다.

```java
ChzzkDonationEvents.TEST.register((server, donation) -> {
    // 테스트 전용 연출. 실결제로 기록하거나 실제 금전 가치가 있는 지급을 하지 않는다.
});
```

공통 코어의 `dev.leru.chzzk.core`와 Fabric 내부 `dev.leru.chzzk.fabric`는 구현 계층이다. 소비 모드의 공개 의존 계약은 `dev.leru.chzzk.api` 패키지로 한정하는 것을 권장한다.
