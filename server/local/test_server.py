"""
서버 테스트 스크립트
서버가 제대로 동작하는지 확인하는 간단한 테스트
"""
import requests
import sys

def test_health_check(server_url):
    """Health check 엔드포인트 테스트"""
    try:
        print(f"Testing health check: {server_url}/api/health")
        response = requests.get(f"{server_url}/api/health", timeout=5)
        print(f"Status Code: {response.status_code}")
        print(f"Response: {response.json()}")
        return response.status_code == 200
    except Exception as e:
        print(f"Health check failed: {e}")
        return False

if __name__ == "__main__":
    # 서버 URL 설정 (기본값)
    server_url = "http://localhost:5000"
    
    if len(sys.argv) > 1:
        server_url = sys.argv[1]
    
    print(f"Testing server at: {server_url}")
    print("-" * 50)
    
    if test_health_check(server_url):
        print("\n✅ Health check passed!")
    else:
        print("\n❌ Health check failed!")
        print("\n서버가 실행 중인지 확인하세요:")
        print("  python app.py")
