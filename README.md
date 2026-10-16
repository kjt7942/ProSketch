# ProSketch

Procreate(iPad)를 참고해 만든 Android 태블릿용 드로잉 앱입니다. Galaxy Tab S7 FE(2560×1600, S펜)를 기준으로 맞췄고, UI는 한국어입니다.

- 패키지: `com.prosketch.app` · 최소 Android 10(API 29) · 타깃 API 36
- 언어/UI: Kotlin + Jetpack Compose, 캔버스는 RenderNode 기반 GPU 합성
- 배포: [GitHub Releases](https://github.com/kjt7942/ProSketch/releases/latest) + 앱 내 자동 업데이트

---

## 설치 (실기기)

1. 태블릿 브라우저로 **https://github.com/kjt7942/ProSketch/releases/latest** 에 접속합니다.
2. `ProSketch-vX.Y.Z.apk`를 내려받아 엽니다.
3. 처음 한 번은 "출처를 알 수 없는 앱"(브라우저 또는 내 파일) 설치를 허용해야 합니다.

USB 디버깅으로 설치할 때는 `adb install -r ProSketch-vX.Y.Z.apk`를 실행합니다.

## 업데이트

앱이 GitHub의 최신 릴리스를 직접 확인하고 설치합니다. 작품, 브러시, 팔레트는 그대로 유지됩니다.

- **자동 확인**: 앱을 켜면 갤러리에서 최신 릴리스를 한 번 확인합니다. 새 버전이 있으면 변경 사항과 함께 알림이 뜹니다.
- **수동 확인**: 갤러리 오른쪽 위 ⟳(업데이트 확인) 버튼, 또는 편집 화면의 **동작 → 도움말 → 업데이트 확인**을 누릅니다.
- [업데이트]를 누르면 APK를 내려받아 Android 설치 화면을 엽니다. 처음 한 번은 "ProSketch에서 앱 설치 허용"을 켜야 합니다(앱이 설정 화면으로 안내합니다).
- 모든 릴리스는 같은 서명 키로 서명되므로 덮어쓰기 설치가 됩니다.

## 새 버전 배포 (개발자)

```powershell
./release.ps1 1.0.1     # gradle.properties 버전 갱신 → 커밋 → 태그 v1.0.1 → 푸시
```

태그가 올라가면 GitHub Actions(`.github/workflows/release.yml`)가 다음을 수행합니다.

1. JDK 21과 Android SDK를 설치하고 `assembleRelease -PappVersion=1.0.1`로 빌드합니다.
2. 저장소 Secrets의 키로 서명하고 서명을 검증합니다.
3. `ProSketch-v1.0.1.apk`와 커밋 목록으로 만든 변경 사항을 첨부해 릴리스를 만듭니다.

Actions 탭에서 **Release → Run workflow**에 버전을 넣어 수동으로 실행할 수도 있습니다.

버전 규칙: `X.Y.Z` → `versionCode = X*10000 + Y*100 + Z`. 버전이 이전보다 커야 업데이트로 인식됩니다.

### 서명 키

| 위치 | 내용 |
|---|---|
| `C:\Users\USER\.android\prosketch-release.jks` | 릴리스 키스토어(저장소에 올리지 않음) |
| `keystore.properties` (프로젝트 루트, git 제외) | 로컬 빌드용 경로·비밀번호. 사본: `C:\Users\USER\.android\prosketch-keystore.properties` |
| GitHub Secrets | `PROSKETCH_KEYSTORE_BASE64`, `PROSKETCH_KEYSTORE_PASSWORD`, `PROSKETCH_KEY_ALIAS`, `PROSKETCH_KEY_PASSWORD` |

> 키스토어를 잃어버리면 기존 설치본을 업데이트할 수 없습니다(삭제 후 재설치해야 함). `.jks`와 properties 파일을 별도로 백업해 두세요.

## 빌드

```
./gradlew.bat assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew.bat assembleRelease   # app/build/outputs/apk/release/app-release.apk (keystore.properties 있으면 릴리스 키로 서명)
```

JDK 21, Android SDK 36, Gradle 9.1(래퍼 포함), AGP 9.0.1, Kotlin 2.3.20, Compose BOM 2026.03.01을 사용합니다. 프로젝트 경로에 한글이 있어도 빌드되도록 `android.overridePathCheck=true`를 설정했습니다.

---

## 주요 기능
- **브러시 엔진**
  - S펜 압력, 기울기, 방위각과 속도에 반응합니다. 압력 곡선은 사용자가 편집할 수 있습니다.
  - StreamLine 손떨림 보정, 시작·끝 테이퍼, 그레인 텍스처, 습식 혼합(희석·충전·당기기), 색상 지터, 문지르기를 지원합니다.
  - 기본 브러시 31종(7개 카테고리)이 들어 있고, 브러시 스튜디오(11개 섹션과 드로잉 패드)에서 브러시를 만들거나 수정할 수 있습니다. 팁과 그레인은 사용자 이미지로 바꿀 수 있습니다.
- **레이어**
  - 혼합 모드 17종, 불투명도, 클리핑 마스크, 알파 잠금, 참조 레이어, 잠금, 병합을 지원합니다.
  - 길게 눌러 끌면 순서를 바꾸고, 가시성 체크를 길게 누르면 단독 가시성이 켜집니다.
  - 레이어 수는 기기 메모리에 맞춰 제한되며, 2560×1600 캔버스에서 약 43개입니다.
- **색상**: 디스크, 클래식, 하모니(5가지), 값(HSB·RGB·HEX), 팔레트 탭이 있습니다. 최근 색상과 이전 색상 전환도 지원합니다.
- **ColorDrop**
  - 색상 버튼을 캔버스로 끌어다 놓으면 해당 영역이 채워집니다.
  - 놓기 전에 누른 채로 있다가 좌우로 밀면 임계값이 바뀝니다.
  - 선 가장자리 아래까지 채워지고, '계속 채우기'와 참조 레이어를 지원합니다.
- **QuickShape**: 그린 뒤 펜을 떼지 않고 잠시 멈추면 직선, 호, 폴리라인, 타원, 다각형으로 바뀝니다. 그 상태에서 다른 손가락을 탭하면 정원이나 정사각형처럼 완벽한 도형이 되고, 끌어서 크기와 회전을 조절합니다.
- **선택**: 자동, 올가미/다각형, 직사각형, 타원 방식이 있고 추가·제거, 반전, 페더, 저장/불러오기, 색상 채우기를 지원합니다.
- **변형**: 자유형태, 균등, 왜곡, 뒤틀기(베지어 메시)를 지원합니다. 뒤집기, 45° 회전, 화면에 맞추기, 보간 방식 선택, 자석 기능도 있습니다.
- **조정**
  - 색조·채도·밝기, 색상 균형, 커브, 그라디언트 맵, 밝기/대비, 포스터화, 반전을 지원합니다.
  - 가우시안 흐림, 움직임 흐림, 선명하게, 노이즈, 블룸, 글리치, 하프톤, 색수차, 모자이크도 있습니다.
  - 미리보기는 실시간이며, 캔버스를 좌우로 밀어 값을 조절합니다.
  - 픽셀 유동화는 8가지 모드에 크기, 압력, 왜곡, 모멘텀 설정이 있습니다.
- **그리기 가이드**: 2D 격자, 등각, 원근(소실점 최대 3개), 대칭(수직·수평·사분면·방사형, 회전 대칭)을 지원하고 그리기 도우미로 선을 가이드에 맞춥니다.
- **애니메이션 어시스트**: 어니언 스킨, 재생(반복·핑퐁·한 번), FPS 설정을 지원하고 GIF와 MP4로 내보냅니다.
- **타임랩스**: 자동 녹화, 다시 보기, MP4 내보내기(전체 길이 또는 30초)를 지원합니다.
- **입출력**
  - 내보내기 형식은 PSD(레이어 유지), PNG, JPEG, PDF, PNG 레이어 ZIP, .prosketch입니다. 갤러리에 바로 저장할 수도 있습니다.
  - PSD, 이미지, .prosketch 파일을 가져올 수 있습니다.
- **기타**: 텍스트 추가, 사진 삽입, 참조 창, 캔버스 자르기/크기 조정/뒤집기, 자동 저장, 실행 취소 최대 250단계, GitHub 자동 업데이트를 지원합니다.

## 제스처 (Procreate 핸드북 기준)
| 제스처 | 동작 |
|---|---|
| 두 손가락 탭 / 길게 누르기 | 실행 취소 / 연속 실행 취소 |
| 세 손가락 탭 | 다시 실행 |
| 핀치·비틀기 | 확대·축소·회전 (90° 근처에서 스냅) |
| 빠르게 핀치 닫기 | 화면에 맞추기 |
| 손가락 길게 누르기 | 스포이드 (다른 손가락으로 탭하면 현재 레이어만 샘플링) |
| 세 손가락 좌우 문지르기 | 레이어 지우기 |
| 세 손가락 아래로 쓸기 | 복사 및 붙여넣기 메뉴 |
| 네 손가락 탭 | 전체 화면 |
| S펜 버튼 | 누르는 동안 지우개 (설정에서 스포이드나 실행 취소로 변경) |
| S펜 호버 | 브러시 크기 커서 표시 |

손가락 입력 기본값은 "S펜을 쓰기 시작하면 손가락은 이동만"입니다. 그래서 손바닥이 닿아도 그려지지 않습니다.

## 키보드 단축키 (Book Cover Keyboard)
B 브러시, S 문지르기, E 지우개, `[` `]` 브러시 크기, Ctrl+Z / Ctrl+Shift+Z 실행 취소/다시 실행, Ctrl+0 화면에 맞추기, Ctrl+C/X/V 복사/잘라내기/붙여넣기, Ctrl+T 변형, Ctrl+D 선택 해제, L 레이어 패널, Esc 패널 닫기 또는 모드 종료.

## 소스 구조
```
app/src/main/java/com/prosketch/app/
├─ MainActivity.kt            몰입 모드, 갤러리↔편집 전환, 키보드 단축키, 일시정지 시 자동 저장
├─ engine/
│  ├─ Document.kt             문서·레이어 모델, 혼합 모드 17종
│  ├─ Guides.kt               격자·등각·원근·대칭 가이드와 그리기 도우미
│  ├─ Brush.kt                브러시 파라미터, 기본 브러시 31종
│  ├─ BrushTextures.kt        팁 모양 11종·그레인 9종 생성
│  ├─ BrushEngine.kt          TiledBuffer, Stabilizer(StreamLine), BrushStroke, SmudgeStroke
│  ├─ QuickShape.kt           직선·호·폴리라인·타원·다각형 인식
│  ├─ History.kt              실행 취소 250단계 + 메모리 예산
│  ├─ Compositor.kt           레이어 합성, 클리핑, 썸네일
│  ├─ Filters.kt              조정 17종
│  ├─ Selection.kt            선택 마스크, FloodFill(ColorDrop)
│  └─ Transform.kt            자유형/균등/왜곡/뒤틀기, LiquifySession
├─ data/
│  ├─ Stores.kt               Prefs, PressureCurve, PaletteStore, BrushStore
│  ├─ ProjectStore.kt         작품 저장·목록·.prosketch 압축 포맷
│  ├─ Psd.kt                  PSD 읽기/쓰기(PackBits, 한글 레이어 이름)
│  ├─ Media.kt                Mp4Encoder(H.264), GifEncoder
│  ├─ Exporter.kt             PNG/JPEG/PDF/ZIP 내보내기, TimelapseRecorder
│  └─ Updater.kt              GitHub Releases 확인·다운로드·설치
└─ ui/
   ├─ canvas/CanvasView.kt    렌더링 캐시, S펜·터치 입력, 모든 제스처, 변형 핸들
   ├─ editor/                 Editor(상태), Tools, EditorScreen, ColorPanel, LayersPanel,
   │                          BrushPanels(라이브러리·스튜디오), ModeBars, ActionsPanel
   ├─ gallery/GalleryScreen.kt  갤러리, 새 캔버스, 가져오기, 이름 변경·복제·공유·삭제
   └─ common/                 Widgets(테마·공통 위젯), UpdateUi(업데이트 대화상자)
```

---

## 작성 히스토리

이 앱은 Claude Code(Claude Opus 5.5)와의 대화로 처음부터 작성했습니다. 아래는 최초 요청부터의 진행 기록입니다. 시각은 한국 시간(KST)입니다.

### 0. 요청 원문

**최초 요청** (2026-10-17 00:36)
> https://apps.apple.com/kr/app/procreate/id425073498
> 아이패드용 앱인 Procreate에 대해 조사하고 비슷한 기능을 수행하는 안드로이드용 앱을 생성하자.
> 갤럭시탭s7FE에서 사용할 수 있도록 해. 니가 할 수 있는 최대치의 퀄리티로 집중해서 작성해. 필요시 rea mcp를 활용하도록.

**추가 요청 1** (작업 중)
> https://help.procreate.com/procreate/handbook/introduction 여기에서도 참고할 사항을 확보해.

**추가 요청 2** (작업 중)
> 지금 만드는 앱의 이름은 "ProSketch"로 하자.

**추가 요청 3** (2026-10-17 05:50)
> 깃허브에 저장소 생성해서 커밋, 푸시.
> readme에 최초 요청한 스크립트부터 작성 히스토리를 상세하게 기록.
> 실기기에 깃허브를 통해 배포하고, 업데이트 할수있도록 준비해줘.

**추가 요청 4** (작업 중)
> 깃허브 저장소는 "ProSketch"로 공개로 생성.

### 1. 환경 확인과 조사 (00:36 ~ 00:47)
- PC의 Android 빌드 환경을 확인했습니다. JDK 21, Android SDK 36, Gradle 9.1 캐시가 있었고, 연결된 기기는 없었습니다.
- 같은 PC의 기존 Gradle 프로젝트(`toy-car-run`)에서 검증된 버전 조합(AGP 9.0.1, Kotlin 2.3.20, Compose BOM)을 가져왔습니다.
- **App Store 페이지 조사**: Procreate 5.4.14 기준 기능 목록을 정리했습니다(브러시 200종 이상, 브러시 스튜디오, 레이어·혼합 모드, QuickShape, ColorDrop, 조정, 픽셀 유동화, 그리기 가이드, 애니메이션 어시스트, 타임랩스, PSD 입출력 등).
- **Procreate 핸드북 조사**(추가 요청 1): 25개 페이지를 백그라운드로 읽었습니다. 제스처 표, 브러시 스튜디오 11개 섹션 항목, 색상 패널 5개 탭, 레이어 옵션, 선택·변형 방식, 픽셀 유동화 모드, QuickShape 편집, ColorDrop 임계값 조절, 실행 취소 250단계 같은 세부 동작을 설계 기준으로 삼았습니다.
- **rea MCP**: Procreate는 iPad 전용 앱이라 분석할 바이너리를 구할 수 없었습니다. 그래서 rea는 테스트 단계(ADB 화면 캡처·로그)에 쓰기로 했습니다.

### 2. 프로젝트 골격 (00:47 ~ 00:52)
- Gradle 래퍼와 빌드 스크립트를 만들고, 최소 앱으로 빌드를 검증했습니다.
- 문제 1: Bash heredoc이 `settings.gradle.kts`의 백슬래시를 지워 빌드가 실패했습니다. 참고 프로젝트의 파일을 복사해 고쳤습니다.
- 문제 2: Kotlin 툴체인이 설치되지 않은 JDK 버전을 찾았습니다. 설치된 JDK 21(`jvmToolchain(21)`)로 맞췄습니다.
- 앱 이름을 **ProSketch**로 정하고(추가 요청 2), 패키지를 `com.prosketch.app`, 폴더를 `ProSketch`로 바꿨습니다. 한글 경로(`rea-리버스엔지니어링`)에서도 빌드되는 것을 확인했습니다.
- material-icons-extended에 실제로 있는 아이콘 이름을 AAR에서 확인한 뒤 사용했습니다.

### 3. 엔진 (00:52 ~ 01:10)
작성 순서대로 정리했습니다.
1. `Document.kt`: 문서·레이어 모델과 혼합 모드 17종. 마스크 버퍼 크기를 행 stride 기준으로 고쳤습니다.
2. `Guides.kt`: 2D 격자, 등각, 원근(소실점 1~3개), 대칭 가이드와 그리기 도우미.
3. `Brush.kt`: Procreate 브러시 스튜디오 항목을 따른 파라미터와 기본 브러시 31종(스케치, 잉크, 페인팅, 에어브러시, 캘리그래피, 텍스처, 아티스틱).
4. `BrushTextures.kt`: 팁 모양 11종과 그레인 9종을 코드로 생성합니다. 사용자 이미지도 쓸 수 있습니다.
5. `BrushEngine.kt`: 타일 버퍼, StreamLine 보정, 압력·기울기·속도 반응, 테이퍼, 그레인, 습식 혼합, 색상 지터, 문지르기.
6. `QuickShape.kt`: 직선·호·폴리라인·타원·삼각형/사각형 인식과 "완벽한 도형" 보정.
7. `History.kt`: 실행 취소 최대 250단계. 기기 메모리에 맞춘 예산을 둡니다.
8. `Compositor.kt`: 레이어 합성, 클리핑 마스크, 알파 잠금.
9. `Filters.kt`: 조정 17종(HSB, 색상 균형, 커브, 그라디언트 맵, 가우시안·움직임 흐림, 선명하게, 노이즈, 블룸, 글리치, 하프톤, 색수차 등).
10. `Selection.kt`: 자동·올가미·사각형·타원 선택, 페더, FloodFill.
11. `Transform.kt`: 자유형·균등·왜곡·뒤틀기 변형과 보간, 픽셀 유동화 세션(모멘텀 포함).

### 4. 데이터 계층 (01:06 ~ 01:15)
- `Stores.kt`: 환경설정, 압력 곡선, 팔레트, 사용자 브러시 저장.
- `ProjectStore.kt`: 작품 저장·불러오기, 갤러리 목록, `.prosketch` 압축 포맷.
- `Psd.kt`: PSD 쓰기와 읽기. 레이어, 혼합 모드, 클리핑, 한글 레이어 이름을 유지하고 PackBits로 압축합니다.
- `Media.kt`와 `Exporter.kt`: MP4(H.264)와 GIF 인코더, PNG/JPEG/PDF/ZIP 내보내기, 타임랩스 녹화. 타임랩스 렌더링은 백그라운드로 옮겼습니다.
- UI 작업 전에 전체 컴파일을 검증했습니다.
- `Editor.kt`(편집 상태·동작 컨트롤러)와 `Tools.kt`(선택·변형·조정 도구 컨트롤러)를 작성했습니다.

**01:16 사용량 한도로 작업이 중단되었습니다.** 이 시점까지의 코드는 모두 컴파일되었지만 화면 UI는 아직 없었습니다. 한도가 초기화된 03:01에 이어서 진행했습니다.

### 5. UI (03:01 ~ 03:17)
1. `CanvasView.kt`: RenderNode 캐시로 GPU 합성을 하고, S펜·터치 입력을 처리합니다. 손바닥 무시, 모든 멀티터치 제스처, S펜 버튼과 호버 커서, 변형 핸들, 스포이드 돋보기가 들어 있습니다.
2. `Widgets.kt`: 다크 테마, 세로 슬라이더, 패널, 체커보드 등 공통 위젯. 세로 슬라이더가 오래된 값을 캡처하던 버그를 고쳤습니다.
3. `ColorPanel.kt`: 디스크·클래식·하모니·값·팔레트 탭, 최근 색상, 이전 색상 전환.
4. `LayersPanel.kt`: 썸네일, 혼합 모드·불투명도, 레이어 옵션 메뉴, 길게 눌러 끌어 순서 변경, 배경 색상, 단독 가시성.
5. `BrushPanels.kt`: 브러시 라이브러리(획 미리보기)와 브러시 스튜디오(11개 섹션, 드로잉 패드).
6. `ModeBars.kt`: 선택·변형·조정·유동화·가이드 모드의 하단 도구 막대.
7. `ActionsPanel.kt`: 동작 메뉴(추가·캔버스·공유·비디오·설정·도움말), 텍스트·크기 조정·캔버스 정보 대화상자, 압력 곡선 편집기.
8. `EditorScreen.kt`: 상단 바, 사이드바(크기·불투명도 슬라이더), ColorDrop 드래그, HUD, 패널 배치.
9. `GalleryScreen.kt`: 새 캔버스 프리셋, 사진/PSD/.prosketch 가져오기, 이름 변경·복제·공유·삭제, 다중 선택.
10. `MainActivity.kt`: 몰입 모드, 화면 전환, 키보드 단축키, 일시정지 시 자동 저장.
- 03:17 전체 디버그 APK 빌드에 성공했습니다.

### 6. 에뮬레이터 테스트 (03:17 ~ 03:35)
- 실기기가 연결되어 있지 않아 cmdline-tools, emulator, Android 14 x86_64 이미지를 설치했습니다. Tab S7 FE와 같은 사양(2560×1600, 320dpi, RAM 4GB)의 AVD `TabS7FE`를 만들고 WHPX 가속으로 부팅했습니다.
- 설치를 기다리는 동안 코드를 검토하다가, 조정을 적용할 때 메인 스레드가 교착될 수 있는 문제(`runBlocking` 안의 `withContext(Main)`)를 찾아 고쳤습니다.
- rea MCP의 ADB 도구는 서버에 adb 경로(`REA_ADB_PATH`)가 설정되어 있지 않아 실행되지 않았습니다. 그래서 adb를 직접 썼습니다(`input`/`motionevent`로 조작, `screencap`으로 화면 확인).
- 확인한 항목:
  - 갤러리, 새 캔버스 프리셋
  - 획 테이퍼와 그레인 질감, 브러시 라이브러리 미리보기
  - 레이어·색상·동작 패널
  - 자동 저장 후 재실행해도 작품이 유지되는지
  - 가우시안 흐림 실시간 미리보기와 캔버스 스와이프 조절
  - 변형 이동·핸들
  - QuickShape 타원 스냅
  - ColorDrop 채우기(선 아래까지)
  - 실행 취소
  - 브러시 스튜디오와 드로잉 패드
  - 픽셀 유동화와 저장·복원
  - PSD 내보내기: 파일을 꺼내 독립 파서(psd-tools)로 열어 레이어 구조, 위치, 혼합 모드, 한글 이름(배경, 레이어 1)을 확인했습니다.
  - R8 축소 릴리스 빌드 실행, 크래시 로그 없음
- 테스트 중 고친 문제:
  - QuickShape 도형에 이음새가 생김 → 도형으로 바꿀 때 테이퍼를 끕니다.
  - 회전 핸들이 상단 바에 가려짐 → 위쪽 공간이 없으면 아래로 뒤집습니다.
  - 색상 디스크 표시점이 원 밖으로 나감 → 원 안으로 제한했습니다.
  - 브러시 크기 곡선이 작은 값에서 둔함 → s^1.5 곡선으로 바꿨습니다.
  - 미리보기 획이 너무 가늘었음 → 최소 크기를 두었습니다.
  - 패널 배경이 비쳐 보임 → 불투명하게 바꿨습니다.
  - 갤러리 카드 아래 여백이 큼 → 카드 비율을 1.3으로 바꿨습니다.
  - "화면 크기" 프리셋 → `maximumWindowMetrics`를 씁니다.
- README를 작성하고 에뮬레이터를 종료했습니다. 결과물: 릴리스 APK 약 3MB, Kotlin 약 1만 400줄.

### 7. GitHub 저장소와 배포·업데이트 (05:50 ~)
- **앱 내 업데이트**를 추가했습니다(`data/Updater.kt`, `ui/common/UpdateUi.kt`).
  - GitHub API로 `kjt7942/ProSketch`의 최신 릴리스를 읽고, 태그 버전을 설치된 버전과 숫자로 비교합니다.
  - 새 버전이 있으면 변경 사항을 보여 주고, APK를 진행률과 함께 캐시에 내려받습니다(CDN 리디렉션은 직접 따라가고, 크기를 검증합니다). 그다음 FileProvider로 시스템 설치 화면을 엽니다.
  - "앱 설치 허용" 권한이 없으면 설정 화면으로 안내합니다.
  - 앱을 켤 때 한 번 조용히 확인하고, 갤러리 버튼과 도움말 탭에서 수동으로 확인할 수 있습니다. 다운로드는 화면을 옮겨도 계속됩니다.
  - 매니페스트에 `INTERNET`과 `REQUEST_INSTALL_PACKAGES` 권한을 추가했습니다.
- **버전 체계**: `-PappVersion`(CI는 git 태그) 또는 `gradle.properties`의 `appVersion`에서 versionName을 정하고, versionCode는 `X*10000+Y*100+Z`로 계산합니다.
- **릴리스 서명**: 기존 APK는 디버그 키로 서명되어 있어 기기마다 키가 달라 업데이트가 불가능했습니다. RSA 4096 릴리스 키스토어를 새로 만들어 로컬(`keystore.properties`)과 CI(GitHub Secrets)가 같은 키로 서명하게 했습니다.
- **CI/CD**: `.github/workflows/release.yml`. `v*` 태그를 푸시하면 빌드, 서명, 서명 검증, 릴리스 생성, APK 첨부까지 자동으로 진행합니다. 로컬 배포 스크립트는 `release.ps1`입니다.
- `.gitignore`로 빌드 산출물, APK, `local.properties`, 키스토어와 비밀번호 파일을 제외했습니다.
- 공개 저장소 `kjt7942/ProSketch`를 만들어 푸시하고, v1.0.0 릴리스를 배포했습니다.
