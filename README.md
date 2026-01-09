# AR Recorder

ARCore를 사용하여 카메라 이미지와 포즈(pose) 정보를 실시간으로 캡처하고 저장하는 Android 애플리케이션입니다.

## 주요 기능

- 📸 ARCore 카메라 이미지 캡처 (5 FPS)
- 📐 카메라 포즈 정보 저장 (위치, 회전)
- 📷 카메라 내부 파라미터 저장 (intrinsics)
- 💾 JSONL 형식으로 메타데이터 저장
- 🔄 Depth-Anything-3 모델과 호환되는 형식으로 데이터 변환

## 퀵스타트

### 1. 프로젝트 클론

```bash
git clone https://github.com/hyounwoong/ar_recorder.git
cd ar_recorder
```

### 2. Android SDK 설정

프로젝트 루트에 `local.properties` 파일을 생성하고 Android SDK 경로를 설정:

```properties
sdk.dir=C\:\\Users\\YourUsername\\AppData\\Local\\Android\\Sdk
```

> **참고**: Android Studio로 프로젝트를 한 번 열면 `local.properties`가 자동으로 생성됩니다.

### 3. 빌드 및 설치

USB로 Android 기기를 연결하고 USB 디버깅을 활성화한 후:

**Windows:**
```powershell
.\gradlew installDebug
```

**macOS/Linux:**
```bash
./gradlew installDebug
```

### 4. ARCore 자동 설치

앱이 실행되면 **Google Play Services for AR (ARCore)**가 설치되어 있는지 자동으로 확인하고, 없으면 설치를 요청합니다.

> **참고**: 일부 기기에서는 ARCore가 시스템 레벨에 통합되어 있어 별도 앱이 보이지 않을 수 있습니다. 이는 정상입니다.

## 사용 방법

1. 앱 실행
2. 카메라 권한 허용
3. **Start Recording** 버튼 클릭하여 녹화 시작
4. **Stop Recording** 버튼 클릭하여 녹화 중지

### 저장 위치

녹화된 데이터는 다음 위치에 저장됩니다:

```
/storage/emulated/0/ARRecorder/session_<timestamp>/
├── frame_<timestamp>_<frame_num>.jpg  # 캡처된 이미지들
└── session_<timestamp>.jsonl          # 메타데이터 (포즈, intrinsics)
```

### 데이터 형식

각 JSONL 줄은 하나의 프레임에 대한 JSON 객체입니다:

```json
{
  "frame_num": 0,
  "t_ns": 1234567890123456,
  "pose": {
    "pos": [0.0, 0.0, 0.0],
    "quat": [0.0, 0.0, 0.0, 1.0]
  },
  "intrinsics": {
    "fx": 1000.0,
    "fy": 1000.0,
    "cx": 640.0,
    "cy": 480.0
  },
  "w": 1920,
  "h": 1080,
  "display_rotation": 0
}
```

## Python 후처리 스크립트

`inference_with_pose.py`를 사용하여 Depth-Anything-3 모델에 맞는 형식으로 변환:

```bash
pip install numpy scipy pillow depth-anything-3
```

스크립트 내의 `base_folder` 경로를 수정한 후 실행:

```bash
python inference_with_pose.py
```

## 요구사항

- ARCore를 지원하는 Android 기기 (Android 7.0 이상)
- Android SDK 설치
- JDK 17 이상

## 문제 해결

**빌드 오류: "SDK location not found"**
- `local.properties` 파일이 프로젝트 루트에 있는지 확인
- Android SDK 경로가 올바른지 확인

**카메라 권한 오류**
- 기기 설정 → 앱 → AR Recorder → 권한에서 카메라 권한 허용

## 참고 자료

- [ARCore 공식 문서](https://developers.google.com/ar)
- [ARCore Android SDK](https://github.com/google-ar/arcore-android-sdk)
- [Depth-Anything-3](https://github.com/DepthAnything/Depth-Anything-3)

## 라이선스

Apache License 2.0
