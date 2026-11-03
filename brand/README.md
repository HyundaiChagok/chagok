# 차곡 로고

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="chagok-logo-dark.svg">
  <img src="chagok-logo.svg" alt="차곡" height="72">
</picture>

**차가 기록을 차곡차곡 쌓는다.** 위에서부터 객실, 몸체, 기록 두 줄을 같은 간격으로 쌓았습니다. '외관은 사진이, 기능은 차가 기록한다'를 그림으로 옮긴 것입니다.

- **차:** 바퀴가 몸체에 파고드는 휠 아치와 눕힌 앞유리로, 작게 줄여도 차로 읽힙니다. 휠 아치는 겹쳐 그린 것이 아니라 실제로 도려낸 윤곽이라 단색 아이콘과 알림 아이콘에서도 그대로 씁니다.
- **기록 줄:** 문서의 글줄처럼 왼쪽에 맞췄습니다. 마지막 짧은 줄만 주황입니다. 앱에서 주황은 '확인할 것'이고, 로고에서는 '차가 짚어 준 것'입니다.
- **글자:** 'Noto Sans KR'(SIL OFL 1.1)의 굵기 620 윤곽을 경로로 바꿨습니다. 차량 시스템 글꼴처럼 단정한 고딕이라 차 화면에서 어색하지 않습니다. 개성은 심벌이 맡고, 글자는 정확하게 읽히는 데 집중합니다.

## 파일

| 파일 | 쓰는 곳 |
|---|---|
| `chagok-logo.svg` | 기본 로고. 밝은 바탕 |
| `chagok-logo-dark.svg` | 어두운 바탕 |
| `chagok-logo-mono.svg` | 한 가지 색만 쓸 수 있을 때(인쇄, 도장) |
| `chagok-symbol.svg`, `chagok-symbol-dark.svg` | 자리가 좁을 때(작은 표시, 파비콘) |
| `chagok-wordmark.svg` | 심벌 없이 이름만 |
| `chagok-app-icon.svg` | 앱 아이콘과 같은 그림(`app/src/main/res/drawable/ic_launcher_foreground.xml`) |
| `png/chagok-avatar.png` | GitHub 조직 프로필 이미지(1024×1024) |
| `png/chagok-social.png` | 저장소 미리보기 이미지(1280×640) |
| `png/chagok-logo.png`, `png/chagok-logo-dark.png` | SVG를 못 쓰는 곳(발표 자료, 문서). 투명 배경 |
| `png/chagok-symbol.png`, `png/chagok-app-icon.png` | 심벌(투명 배경)과 앱 아이콘(512×512) |

## 색

앱(`ui/Theme.kt`)과 고객 웹의 색과 같습니다.

| 이름 | 값 | 쓰는 곳 |
|---|---|---|
| 잉크 | `#14191C` | 차, 첫 기록 줄, 글자 |
| 주황 | `#EC9846` | 마지막 짧은 기록 줄 |
| 바탕 | `#F2F4F5` | 앱 아이콘, 프로필 이미지의 바탕 |
| 밝은 잉크 | `#E3E7E9` | 어두운 바탕 위의 차와 글자 |

## 쓸 때 지킬 것

- 로고 둘레에 기록 줄 두께의 두 배 이상 빈 곳을 둡니다.
- 화면에서 로고 높이를 24px보다 작게 쓰지 않습니다. 더 작으면 심벌만 씁니다. 심벌은 16px까지 차로 읽힙니다.
- 주황은 마지막 기록 줄에만 씁니다. 차나 글자에 칠하면 택시처럼 보입니다.
- 차의 방향(앞이 오른쪽)을 뒤집지 않습니다. 늘리거나 기울이거나 그림자를 넣지 않습니다.
- 미리보기 이미지의 문구는 이 PC의 Noto Sans KR로 그린 그림입니다.
