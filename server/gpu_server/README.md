# GPU 서버 스크립트

GPU 서버에서 실행할 모델 처리 스크립트들입니다.

## 📁 디렉토리 구조

```
gpu_server/
├── inference_with_pose.py    # 기존 모델 실행 스크립트
├── process_and_save_result.py  # 모델 실행 + 결과 JSON 저장
├── msvl.ipynb                 # 컵 좌표 계산 노트북
└── README.md                  # 이 파일
```

## 📋 파일 설명

### `inference_with_pose.py`
- 기존 모델 실행 스크립트
- 세션 폴더에서 데이터를 읽어서 Depth-Anything-3 모델 실행
- GLB 파일로 결과 저장

### `process_and_save_result.py`
- `inference_with_pose.py`를 기반으로 작성
- 모델 실행 후 컵 좌표를 추출
- 결과를 JSON 파일로 저장
- 로컬 PC 서버에서 SSH로 실행됨

### `msvl.ipynb`
- 컵 좌표 계산 로직이 포함된 노트북
- 향후 `process_and_save_result.py`에 통합 예정

## 🚀 사용 방법

이 스크립트들은 로컬 PC 서버에서 자동으로 GPU 서버에 업로드되고 실행됩니다.

수동 실행:
```bash
cd /data/ephemeral/home/measure_volume_by_multiview/project/ar_folder
python3 process_and_save_result.py /data/ephemeral/home/measure_volume_by_multiview/project/ar_folder
```

## 📝 주의사항

- GPU 서버의 작업 디렉토리는 `/data/ephemeral/home/measure_volume_by_multiview/project/ar_folder`입니다
- 세션 폴더는 `session_*` 패턴으로 저장됩니다
- 결과는 `{session_name}_result.json` 형식으로 저장됩니다
