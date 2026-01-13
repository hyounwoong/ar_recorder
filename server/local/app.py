from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
import zipfile
import tarfile
import tempfile
import shutil
import os
import sys
from pathlib import Path
import traceback
from typing import List
import json
import subprocess
import glob
import time

# 설정 파일 import
import config

app = FastAPI(
    title="AR Recorder API",
    description="AR 세션 데이터를 처리하고 컵 좌표를 반환하는 API",
    version="1.0.0"
)

# CORS 허용 (모바일 앱에서 접근 가능하도록)
app.add_middleware(
    CORSMiddleware,
    allow_origins=config.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

def upload_to_gpu_server(local_path, remote_path):
    """로컬 파일을 GPU 서버로 SCP 업로드"""
    if not config.GPU_SERVER_HOST or not config.GPU_SERVER_SSH_KEY:
        raise ValueError("GPU 서버 설정이 없습니다. config.py를 확인하세요.")
    
    # SSH 키 파일 존재 확인
    ssh_key = os.path.abspath(config.GPU_SERVER_SSH_KEY)
    if not os.path.exists(ssh_key):
        raise FileNotFoundError(f"SSH 키 파일을 찾을 수 없습니다: {ssh_key}")
    
    # Windows 경로를 슬래시로 변환 (SCP가 이해할 수 있도록)
    ssh_key_normalized = ssh_key.replace('\\', '/')
    local_path_normalized = os.path.abspath(local_path).replace('\\', '/')
    
    host = config.GPU_SERVER_HOST
    port = config.GPU_SERVER_PORT
    user = config.GPU_SERVER_USER
    
    # SCP 명령어 구성 (shell=False 사용, 따옴표 없이)
    scp_cmd = [
        "scp",
        "-i", ssh_key_normalized,
        "-P", str(port),
        "-o", "StrictHostKeyChecking=no",
        "-o", "ConnectTimeout=30",  # 연결 타임아웃 30초
        "-o", "ServerAliveInterval=60",
        local_path_normalized,
        f"{user}@{host}:{remote_path}"
    ]
    
    print(f"GPU 서버로 업로드 중: {local_path_normalized} -> {remote_path}")
    try:
        result = subprocess.run(scp_cmd, capture_output=True, text=True, shell=False, timeout=300)
    except subprocess.TimeoutExpired:
        raise Exception(f"SCP 업로드 타임아웃: GPU 서버({host}:{port})에 연결할 수 없습니다. VPN이 켜져 있는지 확인하세요.")
    
    if result.returncode != 0:
        error_msg = result.stderr if result.stderr else result.stdout
        if "Connection timed out" in error_msg or "Connection refused" in error_msg:
            raise Exception(f"GPU 서버 연결 실패: {host}:{port}에 연결할 수 없습니다.\n"
                          f"확인 사항:\n"
                          f"1. VPN이 켜져 있는지 확인\n"
                          f"2. GPU 서버가 실행 중인지 확인\n"
                          f"3. 네트워크 연결 상태 확인\n"
                          f"원본 에러: {error_msg}")
        raise Exception(f"SCP 업로드 실패: {error_msg}")
    
    print("업로드 완료")

def download_from_gpu_server(remote_path, local_path):
    """GPU 서버에서 파일을 SCP 다운로드"""
    if not config.GPU_SERVER_HOST or not config.GPU_SERVER_SSH_KEY:
        raise ValueError("GPU 서버 설정이 없습니다. config.py를 확인하세요.")
    
    # SSH 키 파일 존재 확인
    ssh_key = os.path.abspath(config.GPU_SERVER_SSH_KEY)
    if not os.path.exists(ssh_key):
        raise FileNotFoundError(f"SSH 키 파일을 찾을 수 없습니다: {ssh_key}")
    
    # Windows 경로를 슬래시로 변환
    ssh_key_normalized = ssh_key.replace('\\', '/')
    local_path_normalized = os.path.abspath(local_path).replace('\\', '/')
    
    host = config.GPU_SERVER_HOST
    port = config.GPU_SERVER_PORT
    user = config.GPU_SERVER_USER
    
    # SCP 명령어 구성 (shell=False 사용)
    scp_cmd = [
        "scp",
        "-i", ssh_key_normalized,
        "-P", str(port),
        "-o", "StrictHostKeyChecking=no",
        "-o", "ConnectTimeout=30",  # 연결 타임아웃 30초
        "-o", "ServerAliveInterval=60",
        f"{user}@{host}:{remote_path}",
        local_path_normalized
    ]
    
    print(f"GPU 서버에서 다운로드 중: {remote_path} -> {local_path_normalized}")
    try:
        result = subprocess.run(scp_cmd, capture_output=True, text=True, shell=False, timeout=300)
    except subprocess.TimeoutExpired:
        raise Exception(f"SCP 다운로드 타임아웃: GPU 서버({host}:{port})에 연결할 수 없습니다.")
    
    if result.returncode != 0:
        error_msg = result.stderr if result.stderr else result.stdout
        if "Connection timed out" in error_msg or "Connection refused" in error_msg:
            raise Exception(f"GPU 서버 연결 실패: {host}:{port}에 연결할 수 없습니다.\n"
                          f"확인 사항:\n"
                          f"1. VPN이 켜져 있는지 확인\n"
                          f"2. GPU 서버가 실행 중인지 확인\n"
                          f"원본 에러: {error_msg}")
        raise Exception(f"SCP 다운로드 실패: {error_msg}")
    
    print("다운로드 완료")

def run_on_gpu_server(command, realtime_output=False):
    """GPU 서버에서 명령어 실행"""
    if not config.GPU_SERVER_HOST or not config.GPU_SERVER_SSH_KEY:
        raise ValueError("GPU 서버 설정이 없습니다. config.py를 확인하세요.")
    
    # SSH 키 파일 존재 확인
    ssh_key = os.path.abspath(config.GPU_SERVER_SSH_KEY)
    if not os.path.exists(ssh_key):
        raise FileNotFoundError(f"SSH 키 파일을 찾을 수 없습니다: {ssh_key}")
    
    # Windows 경로를 슬래시로 변환
    ssh_key_normalized = ssh_key.replace('\\', '/')
    
    host = config.GPU_SERVER_HOST
    port = config.GPU_SERVER_PORT
    user = config.GPU_SERVER_USER
    
    # SSH 명령어 구성
    ssh_cmd = [
        "ssh",
        "-i", ssh_key_normalized,
        "-p", str(port),
        "-o", "StrictHostKeyChecking=no",
        "-o", "ConnectTimeout=30",  # 연결 타임아웃 30초
    ]
    
    # 실시간 출력을 위해 pseudo-terminal 할당 및 연결 유지 옵션 추가
    if realtime_output:
        ssh_cmd.extend([
            "-t",  # pseudo-terminal 할당 (라인 버퍼링 활성화)
            "-o", "ServerAliveInterval=60",  # 연결 유지
            "-o", "ServerAliveCountMax=3",
        ])
    
    ssh_cmd.extend([
        f"{user}@{host}",
        command
    ])
    
    print(f"GPU 서버에서 명령 실행: {command}")
    
    if realtime_output:
        # 실시간 출력 (모델 실행용)
        process = subprocess.Popen(
            ssh_cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding='utf-8',  # UTF-8 인코딩 명시 (GPU 서버 출력)
            errors='replace',  # 디코딩 오류 시 대체 문자로 처리
            bufsize=1,  # 라인 버퍼링
            universal_newlines=True
        )
        
        output_lines = []
        try:
            # 실시간 출력을 위해 라인 단위로 읽기
            while True:
                line = process.stdout.readline()
                if not line:
                    # 프로세스가 종료되었는지 확인
                    if process.poll() is not None:
                        break
                    # 아직 실행 중이면 잠시 대기
                    time.sleep(0.1)
                    continue
                
                line = line.rstrip()
                if line:  # 빈 줄 제외
                    print(f"[GPU] {line}")  # 실시간 출력
                    output_lines.append(line)
                    sys.stdout.flush()  # 즉시 출력 보장
            
            process.wait(timeout=600)
            
            if process.returncode != 0:
                # 에러 출력도 포함
                error_output = '\n'.join(output_lines[-20:]) if output_lines else "출력 없음"
                raise Exception(f"SSH 명령 실행 실패 (코드: {process.returncode})\n최근 출력:\n{error_output}")
            
            return '\n'.join(output_lines)
        except subprocess.TimeoutExpired:
            process.kill()
            raise Exception(f"SSH 명령 실행 타임아웃 (600초 초과): {command}")
        except Exception as e:
            if process.poll() is None:
                process.kill()
            raise
    else:
        # 기존 방식 (빠른 명령용)
        result = subprocess.run(ssh_cmd, capture_output=True, text=True, timeout=600, shell=False)
        
        if result.returncode != 0:
            error_msg = result.stderr if result.stderr else result.stdout
            raise Exception(f"SSH 명령 실행 실패 (코드: {result.returncode}): {error_msg}")
        
        # 출력이 있으면 로그에 표시
        if result.stdout:
            print(f"GPU 서버 출력: {result.stdout[:500]}")  # 처음 500자만 표시
        
        return result.stdout

def process_session_on_gpu_server(session_folder_path):
    """GPU 서버에서 세션 폴더를 처리하고 컵 좌표를 반환"""
    try:
        total_start_time = time.time()
        
        # 경로가 파일인지 폴더인지 확인
        if os.path.isfile(session_folder_path):
            # 파일이면 부모 디렉토리를 세션 폴더로 사용
            actual_session_folder = os.path.dirname(session_folder_path)
            print(f"[경고] 파일 경로가 전달됨. 부모 디렉토리 사용: {actual_session_folder}")
        else:
            actual_session_folder = session_folder_path
        
        # 실제 세션 폴더 이름 찾기 (session_* 패턴 또는 JSONL 파일 이름에서 추출)
        session_folder_name = None
        # 먼저 session_* 폴더가 있는지 확인
        session_folders_in_dir = glob.glob(os.path.join(actual_session_folder, "session_*"))
        if session_folders_in_dir:
            session_folder_name = os.path.basename(session_folders_in_dir[0])
        else:
            # session_* 폴더가 없으면 JSONL 파일 이름에서 추출
            jsonl_files = glob.glob(os.path.join(actual_session_folder, "*.jsonl"))
            if jsonl_files:
                jsonl_basename = os.path.basename(jsonl_files[0])
                # session_1768285041960.jsonl -> session_1768285041960
                if jsonl_basename.startswith("session_") and jsonl_basename.endswith(".jsonl"):
                    session_folder_name = jsonl_basename[:-6]  # .jsonl 제거
                else:
                    # 파일명이 session_* 형식이 아니면 extracted를 session_* 형식으로 변경
                    session_folder_name = f"session_{int(time.time() * 1000)}"
            else:
                # 기본값: extracted를 session_* 형식으로 변경
                session_folder_name = f"session_{int(time.time() * 1000)}"
        
        if not session_folder_name:
            session_folder_name = os.path.basename(actual_session_folder.rstrip('/'))
        remote_output_path = os.path.join(config.GPU_SERVER_WORK_DIR, f"{session_folder_name}_result.json")
        
        # 1. GPU 서버에 세션 폴더 업로드
        print(f"GPU 서버로 세션 폴더 업로드 중: {actual_session_folder}")
        start_time = time.time()
        
        # 폴더 전체를 tar.gz로 압축해서 업로드
        with tempfile.NamedTemporaryFile(suffix='.tar.gz', delete=False) as tar_file:
            tar_path = tar_file.name
        
        try:
            # Python의 tarfile로 압축 (Windows 호환)
            tar_start = time.time()
            with tarfile.open(tar_path, 'w:gz') as tar:
                tar.add(actual_session_folder, arcname=session_folder_name)
            print(f"  압축 완료 ({(time.time() - tar_start):.2f}초)")
            
            # GPU 서버로 업로드
            upload_start = time.time()
            remote_tar_path = os.path.join(config.GPU_SERVER_WORK_DIR, f"{session_folder_name}.tar.gz")
            upload_to_gpu_server(tar_path, remote_tar_path)
            print(f"  업로드 완료 ({(time.time() - upload_start):.2f}초)")
            
            # GPU 서버에서 압축 해제
            extract_start = time.time()
            print(f"GPU 서버에서 압축 해제 중: {os.path.basename(remote_tar_path)}")
            # 압축 해제 후 폴더 이름이 session_* 형식이 아니면 변경
            # extracted 폴더가 생성되면 session_* 형식으로 이름 변경
            extract_cmd = (
                f"cd {config.GPU_SERVER_WORK_DIR} && "
                f"tar -xzf {os.path.basename(remote_tar_path)} && "
                f"if [ -d extracted ] && [ ! -d {session_folder_name} ]; then mv extracted {session_folder_name}; fi && "
                f"rm {os.path.basename(remote_tar_path)}"
            )
            run_on_gpu_server(extract_cmd, realtime_output=True)
            print(f"압축 해제 완료 ({(time.time() - extract_start):.2f}초)")
            
        finally:
            if os.path.exists(tar_path):
                os.unlink(tar_path)
        
        print(f"[1단계] 세션 폴더 업로드 및 압축 해제 완료 ({(time.time() - start_time):.2f}초)")
        
        # 2. GPU 서버 스크립트 경로 설정
        start_time = time.time()
        # gpu_server 디렉토리의 스크립트를 GPU 서버로 업로드
        local_gpu_server_dir = Path(__file__).parent.parent / "gpu_server"
        remote_script_path = os.path.join(config.GPU_SERVER_WORK_DIR, "process_and_save_result.py")
        
        local_script_path = local_gpu_server_dir / "process_and_save_result.py"
        if local_script_path.exists():
            print(f"GPU 서버 스크립트 업로드: {local_script_path}")
            upload_to_gpu_server(str(local_script_path), remote_script_path)
        
        # inference_with_pose.py도 업로드 (process_and_save_result.py가 import함)
        local_inference_path = local_gpu_server_dir / "inference_with_pose.py"
        remote_inference_path = os.path.join(config.GPU_SERVER_WORK_DIR, "inference_with_pose.py")
        if local_inference_path.exists():
            print(f"inference_with_pose.py 업로드: {local_inference_path}")
            upload_to_gpu_server(str(local_inference_path), remote_inference_path)
        
        print(f"[2단계] 스크립트 업로드 완료 ({(time.time() - start_time):.2f}초)")
        
        # 3. GPU 서버에서 모델 실행
        start_time = time.time()
        print("GPU 서버에서 모델 실행 중...")
        # 가상환경 활성화 후 Python 실행 (bash -c로 실행하여 source 명령 사용 가능)
        # 가상환경 활성화 확인: which python으로 가상환경의 python이 사용되는지 확인
        # 실패 시 명확한 에러 메시지 출력을 위해 set -e 사용
        script_command = (
            f"bash -c 'set -e && "
            f"cd {config.GPU_SERVER_WORK_DIR} && "
            f"source {config.GPU_SERVER_VENV_PATH} && "
            f"python_path=$(which python) && "
            f"echo \"[GPU] 가상환경 Python 경로: $python_path\" && "
            f"python process_and_save_result.py {config.GPU_SERVER_WORK_DIR}'"
        )
        
        # 실시간 출력 활성화
        output = run_on_gpu_server(script_command, realtime_output=True)
        print(f"[3단계] GPU 서버 실행 완료 ({(time.time() - start_time):.2f}초)")
        
        # 4. 결과 JSON 파일 다운로드
        start_time = time.time()
        result_file_name = f"{session_folder_name}_result.json"
        remote_result_path = os.path.join(config.GPU_SERVER_WORK_DIR, result_file_name)
        
        with tempfile.NamedTemporaryFile(mode='w', suffix='.json', delete=False) as result_file:
            local_result_path = result_file.name
        
        try:
            download_from_gpu_server(remote_result_path, local_result_path)
            
            # 결과 읽기
            with open(local_result_path, 'r') as f:
                result_data = json.load(f)
                cup_coordinates = result_data.get('cup_coordinates', [0.0, 0.0, 0.0])
                rotation_axis = result_data.get('rotation_axis')
        except Exception as e:
            print(f"결과 파일 다운로드 실패: {e}")
            # 출력에서 JSON 파싱 시도
            try:
                # output에서 JSON 추출
                import re
                json_match = re.search(r'\{.*\}', output, re.DOTALL)
                if json_match:
                    result_data = json.loads(json_match.group())
                    cup_coordinates = result_data.get('cup_coordinates', [0.0, 0.0, 0.0])
                    rotation_axis = result_data.get('rotation_axis')
                else:
                    cup_coordinates = [0.0, 0.0, 0.0]
                    rotation_axis = None
            except:
                cup_coordinates = [0.0, 0.0, 0.0]
                rotation_axis = None
        finally:
            if os.path.exists(local_result_path):
                os.unlink(local_result_path)
        
        print(f"[4단계] 결과 다운로드 완료 ({(time.time() - start_time):.2f}초)")
        print(f"[전체] 총 처리 시간: {(time.time() - total_start_time):.2f}초")
        
        result = {
            "success": True,
            "cup_coordinates": cup_coordinates,
            "message": "처리 완료 (GPU 서버에서 실행됨)"
        }
        
        # rotation_axis가 있으면 추가
        if rotation_axis:
            result["rotation_axis"] = rotation_axis
        
        return result
    
    except Exception as e:
        error_msg = f"GPU 서버 처리 중 오류 발생: {str(e)}\n{traceback.format_exc()}"
        print(error_msg)
        return {
            "success": False,
            "error": str(e),
            "message": error_msg
        }

@app.get("/api/health")
async def health_check():
    """서버 상태 확인"""
    return {"status": "ok", "message": "Server is running"}

@app.post("/api/process-session")
async def process_session(file: UploadFile = File(...)):
    """세션 ZIP 파일을 업로드하고 처리"""
    try:
        if not file.filename:
            raise HTTPException(status_code=400, detail="Empty filename")
        
        # 임시 디렉토리에 ZIP 파일 저장 및 압축 해제
        with tempfile.TemporaryDirectory() as temp_dir:
            zip_path = os.path.join(temp_dir, file.filename)
            
            # 파일 저장
            with open(zip_path, "wb") as f:
                content = await file.read()
                f.write(content)
            
            # ZIP 파일 압축 해제
            extract_dir = os.path.join(temp_dir, "extracted")
            os.makedirs(extract_dir, exist_ok=True)
            
            with zipfile.ZipFile(zip_path, 'r') as zip_ref:
                zip_ref.extractall(extract_dir)
            
            # 세션 폴더 찾기 (session_* 패턴)
            session_folders = sorted(glob.glob(os.path.join(extract_dir, "session_*")))
            if not session_folders:
                # 직접 extracted 디렉토리가 세션 폴더일 수도 있음
                jsonl_files = glob.glob(os.path.join(extract_dir, "*.jsonl"))
                if jsonl_files:
                    session_folder = extract_dir
                else:
                    raise HTTPException(status_code=400, detail="세션 폴더를 찾을 수 없습니다")
            else:
                session_folder = session_folders[0]
            
            # 세션 처리 (GPU 서버에서 실행)
            result = process_session_on_gpu_server(session_folder)
            
            if result["success"]:
                return result
            else:
                raise HTTPException(status_code=500, detail=result.get("error", "처리 실패"))
    
    except HTTPException:
        raise
    except Exception as e:
        error_msg = f"요청 처리 중 오류: {str(e)}\n{traceback.format_exc()}"
        print(error_msg)
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == '__main__':
    import uvicorn
    # 서버 실행
    # config.py에서 설정한 HOST와 PORT 사용
    print(f"Starting server on {config.SERVER_HOST}:{config.SERVER_PORT}")
    uvicorn.run(
        app, 
        host=config.SERVER_HOST, 
        port=config.SERVER_PORT,
        log_level=config.LOG_LEVEL.lower()
    )
