"""
로컬 PC 서버 설정 파일
환경 변수나 이 파일에서 설정을 변경할 수 있습니다.
"""
import os
from typing import List

# 서버 설정
SERVER_HOST = os.getenv("SERVER_HOST", "0.0.0.0")
SERVER_PORT = int(os.getenv("SERVER_PORT", "5000"))

# CORS 설정
# 프로덕션 환경에서는 특정 origin만 허용하도록 변경하세요
# 예: CORS_ORIGINS = ["http://localhost:3000", "https://yourdomain.com"]
CORS_ORIGINS: List[str] = ["*"]  # 개발 환경: 모든 origin 허용
# CORS_ORIGINS = ["http://192.168.0.100:5000"]  # 특정 origin만 허용

# 파일 업로드 설정
MAX_UPLOAD_SIZE = int(os.getenv("MAX_UPLOAD_SIZE", "104857600"))  # 100MB (바이트 단위)

# 로깅 설정
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")

# GPU 서버 설정 (SSH 접속용)
GPU_SERVER_HOST = os.getenv("GPU_SERVER_HOST", "10.28.228.101")
GPU_SERVER_PORT = int(os.getenv("GPU_SERVER_PORT", "31788"))  # SSH 포트
GPU_SERVER_USER = os.getenv("GPU_SERVER_USER", "root")
GPU_SERVER_SSH_KEY = os.getenv("GPU_SERVER_SSH_KEY", "C:/Users/hyoun/image_segmentation.pem")  # SSH 키 파일 경로
GPU_SERVER_WORK_DIR = os.getenv("GPU_SERVER_WORK_DIR", "/data/ephemeral/home/measure_volume_by_multiview/project/ar_folder")  # GPU 서버 작업 디렉토리
GPU_SERVER_VENV_PATH = os.getenv("GPU_SERVER_VENV_PATH", "/data/ephemeral/home/py310/bin/activate")  # GPU 서버 가상환경 경로
