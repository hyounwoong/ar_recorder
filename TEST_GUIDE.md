# 테스트 가이드

## 📋 테스트 전 준비사항

### 1. 서버 IP 주소 확인

**Windows에서:**
```powershell
ipconfig
```
`IPv4 주소`를 찾으세요 (예: `192.168.0.100`)

**Linux/Mac에서:**
```bash
ifconfig
# 또는
ip addr
```

### 2. Android 앱에서 서버 IP 설정

`app/src/main/java/com/ar/recorder/network/RetrofitClient.kt` 파일을 열고:

```kotlin
private const val BASE_URL = "http://YOUR_SERVER_IP:5000"  // 여기에 서버 IP 입력
```

예: `http://192.168.0.100:5000`

---

## 🧪 테스트 단계

### Step 1: 서버 실행 및 확인

**터미널 1 (서버 실행):**
```bash
cd server
pip install -r requirements.txt
python app.py
```

서버가 실행되면 다음과 같은 메시지가 보입니다:
```
 * Running on http://0.0.0.0:5000
```

**터미널 2 (서버 테스트):**
```bash
cd server
python test_server.py http://localhost:5000
```

또는 브라우저에서:
```
http://localhost:5000/api/health
```

✅ 성공하면: `{"status": "ok", "message": "Server is running"}`

---

### Step 2: Android 앱 빌드 및 설치

```bash
# 프로젝트 루트에서
cd app
../gradlew installDebug
```

또는 Android Studio에서:
- `Run` 버튼 클릭

---

### Step 3: 네트워크 연결 확인

**모바일과 서버가 같은 네트워크에 있는지 확인:**

1. 모바일에서 Wi-Fi 설정 확인
2. 서버와 같은 Wi-Fi 네트워크에 연결되어 있는지 확인

**방화벽 확인 (서버 측):**
- Windows: 방화벽에서 포트 5000 허용
- Linux: `sudo ufw allow 5000`

---

### Step 4: 실제 테스트

1. **Android 앱 실행**
2. **START 버튼 클릭** → 녹화 시작
3. **몇 초간 카메라로 촬영**
4. **STOP 버튼 클릭**

**예상 동작:**
- "업로드 중..." 메시지 표시
- 서버 로그에서 요청 확인
- 처리 완료 후 "처리 완료! 컵: (x, y, z)" 메시지 표시
- AR 화면에 초록색 구체 표시 (컵 좌표 위치)

---

## 🔍 문제 해결

### 문제 1: "업로드 실패: Connection refused"

**원인:** 서버가 실행되지 않았거나 IP 주소가 잘못됨

**해결:**
- 서버가 실행 중인지 확인
- `RetrofitClient.kt`의 `BASE_URL`이 올바른지 확인
- 모바일과 서버가 같은 네트워크에 있는지 확인

---

### 문제 2: "업로드 실패: timeout"

**원인:** 모델 실행 시간이 너무 길거나 네트워크가 느림

**해결:**
- `RetrofitClient.kt`의 `readTimeout`을 늘리기 (현재 60초)
- 서버 로그 확인하여 실제 처리 시간 확인

---

### 문제 3: 서버에서 "ModuleNotFoundError"

**원인:** 필요한 패키지가 설치되지 않음

**해결:**
```bash
cd server
pip install -r requirements.txt
```

---

### 문제 4: Android 빌드 오류

**원인:** 의존성 문제

**해결:**
```bash
cd app
../gradlew clean
../gradlew build
```

---

## 📊 로그 확인 방법

### 서버 로그
서버 터미널에서 실시간으로 요청과 오류를 확인할 수 있습니다.

### Android 로그
```bash
adb logcat | grep -E "SessionUploader|ArRecorderRenderer"
```

또는 Android Studio의 Logcat에서 필터링:
- Tag: `SessionUploader`
- Tag: `ArRecorderRenderer`

---

## ✅ 성공 확인 체크리스트

- [ ] 서버가 `http://0.0.0.0:5000`에서 실행 중
- [ ] Health check API 응답 성공
- [ ] Android 앱에서 서버 IP 설정 완료
- [ ] 모바일과 서버가 같은 네트워크
- [ ] 녹화 후 STOP 버튼 클릭 시 "업로드 중..." 표시
- [ ] 서버 로그에서 요청 수신 확인
- [ ] "처리 완료" 메시지 표시
- [ ] AR 화면에 초록색 구체 표시

---

## 🎯 다음 단계

테스트가 성공하면:
1. `server/app.py`의 `process_session_folder()` 함수에서 실제 컵 좌표 추출 로직 구현
2. `msvl.ipynb`의 로직을 참고하여 컵 좌표 계산 추가
