# 로컬 PC 서버 (중간 서버)

로컬 PC에서 실행하는 FastAPI 서버입니다. 모바일 앱과 통신하고, GPU 서버로 모델 실행을 요청합니다.

## 📁 디렉토리 구조

```
local/
├── app.py              # FastAPI 서버 메인 파일
├── config.py           # 서버 설정 파일
├── requirements.txt    # Python 패키지 의존성 (통신 관련만)
├── test_server.py      # 서버 테스트 스크립트
└── README.md           # 이 파일
```

## 🚀 빠른 시작

### 1. 패키지 설치

```bash
cd local
pip install -r requirements.txt
```

### 2. 설정

`config.py` 파일에서 GPU 서버 접속 정보를 설정하세요:

```python
GPU_SERVER_HOST = "10.196.197.20"  # GPU 서버 IP
GPU_SERVER_PORT = 30514            # SSH 포트
GPU_SERVER_SSH_KEY = "C:/Users/username/aistages.pem"  # SSH 키 경로
```

### 3. 서버 실행

```bash
python app.py
```

서버가 `http://0.0.0.0:5000`에서 실행됩니다.

### 4. PC IP 확인 및 Android 앱 설정

**PC IP 확인:**
```powershell
ipconfig
```

**Android 앱 설정:**
`app/src/main/java/com/ar/recorder/network/RetrofitClient.kt`:
```kotlin
private const val BASE_URL = "http://YOUR_PC_IP:5000"
```

## 📋 역할

- 모바일 앱으로부터 ZIP 파일 수신
- GPU 서버로 세션 데이터 업로드 (SSH/SCP)
- GPU 서버에서 모델 실행 요청
- 결과 JSON 파일 다운로드
- 모바일 앱으로 결과 반환

## ⚙️ 설정 항목

자세한 설정은 `config.py` 파일을 참고하세요.

- `SERVER_HOST`, `SERVER_PORT`: 로컬 서버 주소
- `GPU_SERVER_*`: GPU 서버 접속 정보
- `CORS_ORIGINS`: CORS 허용 origin

## 🔗 관련 파일

- GPU 서버 스크립트: `../gpu_server/`
- Android 앱: `../../app/`
