<p align="center">
  <img src="docs/app-icon.png" width="128" alt="CloudiPix 앱 아이콘: 파란 하늘과 언덕 위로 떠오르는 해" />
</p>

<h1 align="center">CloudiPix</h1>

<p align="center"><b>NAS와 클라우드의 사진 폴더를 휴대폰 앨범처럼 보는 Android 갤러리</b></p>

<p align="center">
  <a href="https://github.com/loopix8000/CloudiPix/releases/latest">APK 다운로드</a> · Android 11 이상
</p>

WebDAV나 FTP 서버의 사진 폴더를 휴대폰 폴더처럼 보는 갤러리입니다. 폴더를 탭으로 나눠 정리하고, 앱이나 폴더를 잠글 수 있습니다.

## 주요 기능

| 갤러리 폴더 목록 | 서버 사진 보기 | 동기화 설정 | 동기화 기록 |
| :---: | :---: | :---: | :---: |
| <img src="screenshots/cloud-album.png" width="200" alt="갤러리 폴더 목록에서 클라우드 아이콘이 표시된 Atlantic Coast와 Dolomites 앨범" /> | <img src="screenshots/cloud-viewer.png" width="200" alt="서버 사진과 NAS의 Dolomites 경로를 보여 주는 사진 보기 화면" /> | <img src="screenshots/folder-sync.png" width="200" alt="NAS의 Atlantic Coast 폴더에 대한 동기화 저장 위치 설정" /> | <a href="screenshots/folder-sync-history.png"><img src="screenshots/folder-sync-history.png" width="200" alt="WebDAV 폴더의 업로드 1건과 다운로드 4건이 표시된 양방향 동기화 기록" /></a> |

### 클라우드: 네트워크 폴더를 휴대폰 폴더처럼

- **같은 목록에서 보기:** 연결한 WebDAV·FTP 폴더가 휴대폰 폴더 사이에 앨범으로 나타납니다. 구름 아이콘으로 서버 앨범을 구분합니다.
- **내려받지 않고 바로 열기:** 썸네일을 바로 넘겨 보고, 사진을 누르면 서버의 원본을 엽니다.
- **연결이 끊겨도 목록 유지:** 한 번 불러온 앨범은 서버에 연결되지 않을 때도 목록과 썸네일을 보여 줍니다.
- **양방향 동기화:** 휴대폰에도 두고 싶은 폴더는 동기화를 켭니다. 폴더마다 저장 위치를 정하고, 양쪽의 추가·수정·삭제를 서로 맞춥니다. 동기화 기록에서 결과를 확인할 수 있습니다.
- **휴대폰과 서버 사이 정리:** 사진을 휴대폰과 서버 폴더 사이에서 복사하거나 옮길 수 있습니다.

### 탭: 폴더를 주제별로 정리

여행, 가족, 업무처럼 원하는 이름의 탭을 만들고 폴더를 나눠 담습니다. 탭 사이는 옆으로 넘겨 이동하고, 탭 순서와 이름은 언제든 바꿀 수 있습니다. 탭을 지워도 앨범과 사진은 그대로 남습니다.

### 잠금: 앱 또는 폴더 단위

앱 전체를 잠그거나 원하는 폴더만 잠글 수 있습니다. 잠금은 기기의 화면 잠금(지문·얼굴·PIN 등)으로 해제합니다.

### 현대적인 화면

사진을 누르면 썸네일 자리에서 커지며 열리고, 닫으면 다시 그 칸으로 줄어듭니다. 설정 화면은 옆으로 밀리며 전환됩니다. 테마, 강조색, 글꼴, 화면 요소 크기를 고를 수 있고, 사진이 많은 앨범에서도 스크롤과 전환이 끊기지 않도록 다듬었습니다.

### 사진 편집

자르기, 회전, 반전, 밝기·노출·대비·채도·색온도 조정, 미리보기가 있는 필터, 그리기와 텍스트 넣기를 지원합니다. 편집본은 원래 사진이 있던 폴더(휴대폰 또는 서버)에 저장됩니다.

## 개인정보

광고가 없고, 사용 데이터와 오류 보고서를 수집하지 않습니다. 앱이 연결하는 곳은 사용자가 등록한 서버와 새 버전 확인을 위한 GitHub 릴리스뿐입니다.

## 빌드

Windows에서 JDK 21과 Android SDK 36, Build Tools 35.0.0을 준비한 뒤 프로젝트 디렉터리에서 실행합니다.

```powershell
.\gradlew.bat :app:assembleDebug
```

APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.

## 오픈 소스 라이선스

앱의 **설정 → 앱 정보**에 있는 목록과 같습니다.

| 이름 | 라이선스 |
| --- | --- |
| [Simplify my Gallery](https://github.com/simplifymy-app/gallery) | Mozilla Public License 2.0 |
| [AndroidX, Jetpack Compose, Room, Media3](https://developer.android.com/jetpack/androidx) | Apache License 2.0 |
| [Hilt](https://dagger.dev/hilt/) | Apache License 2.0 |
| [Coil](https://coil-kt.github.io/coil/) | Apache License 2.0 |
| [Telephoto](https://github.com/saket/telephoto) | Apache License 2.0 |
| [OkHttp](https://square.github.io/okhttp/) | Apache License 2.0 |
| [Apache Commons Net](https://commons.apache.org/proper/commons-net/) | Apache License 2.0 |
| [Lucide](https://lucide.dev/license) | ISC License / Feather MIT License |
| [ImageToolboxLibs (GPUImage)](https://github.com/T8RIN/ImageToolboxLibs/tree/8.4.1/libs/gpuimage) | Apache License 2.0 |
| [Cropper, Gesture (Image Toolbox)](https://github.com/T8RIN/ImageToolbox/tree/8fbf8bc335758465107278772b54e33070cf0fa2/lib) | Apache License 2.0 |
| [Caveat, IBM Plex, Outfit, Space Grotesk](https://openfontlicense.org/) | SIL Open Font License 1.1 |

Simplify my Gallery에서 가져와 고친 소스 파일에는 [MPL 2.0](LICENSE)이 적용됩니다. 글꼴 라이선스 전문은 [licenses](licenses/) 폴더에, Lucide 라이선스 전문은 [licenses/Lucide-ISC.txt](licenses/Lucide-ISC.txt)에 있습니다.
