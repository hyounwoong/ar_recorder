# 서버 디렉토리 구조 검토 결과

## ✅ 검토 완료 사항

### 1. 디렉토리 구조
- ✅ `local/`: 로컬 PC용 파일들이 깔끔하게 분리됨
- ✅ `gpu_server/`: GPU 서버용 파일들이 분리됨
- ✅ 불필요한 파일들이 정리됨

### 2. 파일 수정 사항

#### `gpu_server/inference_with_pose.py`
- ✅ **수정 완료**: top-level 실행 코드를 `if __name__ == "__main__":` 블록으로 감쌈
- ✅ 함수들(`load_jsonl`, `extract_frame_number`, `get_frame_number_from_timestamp`, `SAMPLING_INTERVAL_NS`)이 모듈로 import 가능하도록 수정
- ✅ `process_and_save_result.py`에서 안전하게 import 가능

#### `local/requirements.txt`
- ✅ **수정 완료**: `requests` 패키지 추가 (test_server.py에서 사용)

### 3. 파일 간 의존성
- ✅ `local/app.py` → `local/config.py` (정상)
- ✅ `local/app.py` → `../gpu_server/` (경로 참조 정상)
- ✅ `gpu_server/process_and_save_result.py` → `gpu_server/inference_with_pose.py` (import 가능)

### 4. 설정 파일
- ✅ `local/config.py`: 모든 필요한 설정 항목 포함
- ✅ GPU 서버 접속 정보 설정 가능

### 5. 문서
- ✅ `local/README.md`: 로컬 서버 사용 가이드 완성
- ✅ `gpu_server/README.md`: GPU 서버 스크립트 설명 완성

## 📋 최종 구조

```
server/
├── local/                    # 로컬 PC용
│   ├── app.py               # FastAPI 서버
│   ├── config.py            # 설정 파일
│   ├── requirements.txt     # 패키지 의존성 (requests 추가됨)
│   ├── test_server.py      # 테스트 스크립트
│   └── README.md
│
├── gpu_server/             # GPU 서버용
│   ├── inference_with_pose.py      # 함수 export 가능 (수정됨)
│   ├── process_and_save_result.py   # 모델 실행 + 결과 저장
│   ├── msvl.ipynb                   # 컵 좌표 계산 노트북
│   └── README.md
│
└── GPU 서버 사용 가이드.txt  # 참고 문서
```

## 🎯 사용 방법

### 로컬 PC에서 서버 실행
```bash
cd server/local
pip install -r requirements.txt
python app.py
```

### 설정
`local/config.py`에서 GPU 서버 접속 정보 설정:
```python
GPU_SERVER_HOST = "10.196.197.20"
GPU_SERVER_PORT = 30514
GPU_SERVER_SSH_KEY = "C:/Users/username/aistages.pem"
```

## ✨ 개선 사항

1. **`inference_with_pose.py` 모듈화**: 함수만 export하고 실행 코드는 main 블록으로 분리
2. **의존성 추가**: `test_server.py`에서 사용하는 `requests` 패키지 추가
3. **디렉토리 분리**: 로컬 PC용과 GPU 서버용 파일 명확히 분리

## 🔍 추가 확인 권장 사항

1. GPU 서버에서 실제로 `inference_with_pose.py`를 import할 때 문제가 없는지 테스트
2. `process_and_save_result.py`의 `extract_cup_coordinates` 함수가 `msvl.ipynb`의 로직을 반영하는지 확인 (향후 개선 예정)
