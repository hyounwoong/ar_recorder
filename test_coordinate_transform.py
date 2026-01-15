#!/usr/bin/env python3
"""
보정 로직 검증 테스트 (Python)

사용법:
    python test_coordinate_transform.py

이 테스트는 ArRecorderRenderer.kt의 보정 로직을 Python으로 재구현하여
수학적으로 올바른지 검증합니다.
"""

import numpy as np
from scipy.spatial.transform import Rotation

EPSILON = 0.001  # 부동소수점 비교 허용 오차

def quaternion_to_rotation_matrix(quat):
    """Quaternion을 회전 행렬로 변환"""
    r = Rotation.from_quat(quat)
    return r.as_matrix()

def build_anchor_transform_matrix_current(pos, quat):
    """
    앵커 pose로부터 4x4 변환 행렬 생성 (수정된 구현 방식)
    
    수정된 Kotlin 코드의 방식:
    1. 회전 행렬 생성
    2. 회전 행렬을 상단 3x3에 복사
    3. 이동 벡터를 직접 설정 (12, 13, 14번 인덱스)
    """
    rotation = quaternion_to_rotation_matrix(quat)
    
    # 올바른 형식: T = [[R  t],
    #                   [0  1]]
    matrix = np.eye(4)
    matrix[:3, :3] = rotation
    matrix[:3, 3] = pos
    
    return matrix

def build_anchor_transform_matrix_correct(pos, quat):
    """
    앵커 pose로부터 4x4 변환 행렬 생성 (올바른 구현)
    
    올바른 형식:
    T = [[R  t],
         [0  1]]
    """
    rotation = quaternion_to_rotation_matrix(quat)
    
    matrix = np.eye(4)
    matrix[:3, :3] = rotation
    matrix[:3, 3] = pos
    
    return matrix

def transform_w0_to_current_world_current(point_w0, first_anchor_pos, first_anchor_quat,
                                          current_anchor_pos, current_anchor_quat):
    """W0 좌표를 현재 좌표로 변환 (현재 구현)"""
    A0 = build_anchor_transform_matrix_current(first_anchor_pos, first_anchor_quat)
    A_t = build_anchor_transform_matrix_current(current_anchor_pos, current_anchor_quat)
    S_inv = A_t @ np.linalg.inv(A0)
    
    point_w0_homogeneous = np.array([point_w0[0], point_w0[1], point_w0[2], 1.0])
    result = S_inv @ point_w0_homogeneous
    
    return result[:3]

def transform_w0_to_current_world_correct(point_w0, first_anchor_pos, first_anchor_quat,
                                          current_anchor_pos, current_anchor_quat):
    """W0 좌표를 현재 좌표로 변환 (올바른 구현)"""
    A0 = build_anchor_transform_matrix_correct(first_anchor_pos, first_anchor_quat)
    A_t = build_anchor_transform_matrix_correct(current_anchor_pos, current_anchor_quat)
    S_inv = A_t @ np.linalg.inv(A0)
    
    point_w0_homogeneous = np.array([point_w0[0], point_w0[1], point_w0[2], 1.0])
    result = S_inv @ point_w0_homogeneous
    
    return result[:3]

def assert_array_almost_equal(expected, actual, epsilon=EPSILON):
    """두 배열이 거의 같은지 확인"""
    diff = np.linalg.norm(expected - actual)
    assert diff < epsilon, f"Expected {expected}, got {actual}, diff: {diff}"
    return True

def test_simple_translation():
    """테스트 1: 단순 이동만 있는 경우"""
    print("\n[테스트 1] 단순 이동 테스트")
    
    # 첫 프레임 앵커: 원점, 회전 없음
    first_anchor_pos = np.array([0.0, 0.0, 0.0])
    first_anchor_quat = np.array([0.0, 0.0, 0.0, 1.0])  # 단위 quaternion
    
    # 현재 프레임 앵커: x축으로 1m 이동
    current_anchor_pos = np.array([1.0, 0.0, 0.0])
    current_anchor_quat = np.array([0.0, 0.0, 0.0, 1.0])
    
    # W0 좌표: 첫 프레임 앵커 위치
    point_w0 = np.array([0.0, 0.0, 0.0])
    
    # 올바른 구현으로 변환
    result_correct = transform_w0_to_current_world_correct(
        point_w0, first_anchor_pos, first_anchor_quat,
        current_anchor_pos, current_anchor_quat
    )
    
    # 예상 결과: 현재 앵커 위치
    expected = np.array([1.0, 0.0, 0.0])
    
    print(f"  W0: {point_w0}")
    print(f"  Expected: {expected}")
    print(f"  Correct: {result_correct}")
    
    assert_array_almost_equal(expected, result_correct)
    print("  [PASS] 통과")

def test_simple_rotation():
    """테스트 2: 회전만 있는 경우"""
    print("\n[테스트 2] 단순 회전 테스트")
    
    # 첫 프레임 앵커: 원점, 회전 없음
    first_anchor_pos = np.array([0.0, 0.0, 0.0])
    first_anchor_quat = np.array([0.0, 0.0, 0.0, 1.0])
    
    # 현재 프레임 앵커: 원점, y축으로 90도 회전
    # y축 90도 회전 quaternion: [0, sin(45°), 0, cos(45°)]
    current_anchor_pos = np.array([0.0, 0.0, 0.0])
    current_anchor_quat = np.array([0.0, 0.7071068, 0.0, 0.7071068])  # y축 90도
    
    # W0 좌표: 첫 프레임 앵커 기준 x축 방향 1m
    point_w0 = np.array([1.0, 0.0, 0.0])
    
    # 올바른 구현으로 변환
    result_correct = transform_w0_to_current_world_correct(
        point_w0, first_anchor_pos, first_anchor_quat,
        current_anchor_pos, current_anchor_quat
    )
    
    # 예상 결과: y축 90도 회전하면 x축 [1,0,0]이 z축 음수 방향 [0,0,-1]이 됨
    expected = np.array([0.0, 0.0, -1.0])
    
    print(f"  W0: {point_w0}")
    print(f"  Expected: {expected}")
    print(f"  Correct: {result_correct}")
    
    assert_array_almost_equal(expected, result_correct, 0.01)
    print("  [PASS] 통과")

def test_translation_and_rotation():
    """테스트 3: 이동 + 회전이 있는 경우"""
    print("\n[테스트 3] 이동 + 회전 테스트")
    
    # 첫 프레임 앵커: 원점, 회전 없음
    first_anchor_pos = np.array([0.0, 0.0, 0.0])
    first_anchor_quat = np.array([0.0, 0.0, 0.0, 1.0])
    
    # 현재 프레임 앵커: [1, 0, 0] 이동 + y축 90도 회전
    current_anchor_pos = np.array([1.0, 0.0, 0.0])
    current_anchor_quat = np.array([0.0, 0.7071068, 0.0, 0.7071068])
    
    # W0 좌표: 첫 프레임 앵커 위치
    point_w0 = np.array([0.0, 0.0, 0.0])
    
    # 올바른 구현으로 변환
    result_correct = transform_w0_to_current_world_correct(
        point_w0, first_anchor_pos, first_anchor_quat,
        current_anchor_pos, current_anchor_quat
    )
    
    # 예상 결과: 현재 앵커 위치
    expected = np.array([1.0, 0.0, 0.0])
    
    print(f"  W0: {point_w0}")
    print(f"  Expected: {expected}")
    print(f"  Correct: {result_correct}")
    
    assert_array_almost_equal(expected, result_correct, 0.01)
    print("  [PASS] 통과")

def test_current_vs_correct_implementation():
    """테스트 4: 현재 구현 vs 올바른 구현 비교"""
    print("\n[테스트 4] 현재 구현 vs 올바른 구현 비교")
    
    # 첫 프레임 앵커: [0, 0, 0], 회전 없음
    first_anchor_pos = np.array([0.0, 0.0, 0.0])
    first_anchor_quat = np.array([0.0, 0.0, 0.0, 1.0])
    
    # 현재 프레임 앵커: [1, 0, 0] 이동 + y축 90도 회전
    current_anchor_pos = np.array([1.0, 0.0, 0.0])
    current_anchor_quat = np.array([0.0, 0.7071068, 0.0, 0.7071068])
    
    # W0 좌표: 첫 프레임 앵커 기준 [0.5, 0, 0]
    point_w0 = np.array([0.5, 0.0, 0.0])
    
    # 현재 구현으로 변환
    result_current = transform_w0_to_current_world_current(
        point_w0, first_anchor_pos, first_anchor_quat,
        current_anchor_pos, current_anchor_quat
    )
    
    # 올바른 구현으로 변환
    result_correct = transform_w0_to_current_world_correct(
        point_w0, first_anchor_pos, first_anchor_quat,
        current_anchor_pos, current_anchor_quat
    )
    
    print(f"  W0: {point_w0}")
    print(f"  Current: {result_current}")
    print(f"  Correct: {result_correct}")
    
    # 두 결과가 다른지 확인
    diff = np.linalg.norm(result_current - result_correct)
    print(f"  Difference: {diff:.6f}")
    
    if diff > EPSILON:
        print("  [WARNING] Current implementation differs from correct implementation!")
        print("  This indicates a bug in buildAnchorTransformMatrix")
        print(f"  The difference is: {diff:.6f}")
    else:
        print("  [OK] Current implementation matches correct implementation")
    
    return diff

def test_inverse_transform():
    """테스트 5: 역변환 검증"""
    print("\n[테스트 5] 역변환 검증")
    
    # 첫 프레임 앵커: [0, 0, 0], 회전 없음
    first_anchor_pos = np.array([0.0, 0.0, 0.0])
    first_anchor_quat = np.array([0.0, 0.0, 0.0, 1.0])
    
    # 현재 프레임 앵커: [1, 2, 3] 이동 + 복잡한 회전
    current_anchor_pos = np.array([1.0, 2.0, 3.0])
    current_anchor_quat = np.array([0.1, 0.2, 0.3, 0.9])
    # 정규화
    quat_norm = np.linalg.norm(current_anchor_quat)
    normalized_quat = current_anchor_quat / quat_norm
    
    # W0 좌표
    point_w0 = np.array([0.5, 0.3, -0.2])
    
    # W0 → 현재 좌표로 변환
    result_correct = transform_w0_to_current_world_correct(
        point_w0, first_anchor_pos, first_anchor_quat,
        current_anchor_pos, normalized_quat
    )
    
    # 현재 좌표 → W0로 역변환
    A0 = build_anchor_transform_matrix_correct(first_anchor_pos, first_anchor_quat)
    A_t = build_anchor_transform_matrix_correct(current_anchor_pos, normalized_quat)
    S = np.linalg.inv(A_t) @ A0  # 역변환
    
    result_homogeneous = np.array([result_correct[0], result_correct[1], result_correct[2], 1.0])
    back_to_w0 = S @ result_homogeneous
    back_to_w0_result = back_to_w0[:3]
    
    print(f"  Original W0: {point_w0}")
    print(f"  Transformed: {result_correct}")
    print(f"  Back to W0: {back_to_w0_result}")
    
    assert_array_almost_equal(point_w0, back_to_w0_result, 0.01)
    print("  [PASS] 통과")

def main():
    print("=" * 60)
    print("보정 로직 검증 테스트 실행")
    print("=" * 60)
    
    try:
        test_simple_translation()
    except Exception as e:
        print(f"  ✗ 실패: {e}")
        import traceback
        traceback.print_exc()
    
    try:
        test_simple_rotation()
    except Exception as e:
        print(f"  ✗ 실패: {e}")
        import traceback
        traceback.print_exc()
    
    try:
        test_translation_and_rotation()
    except Exception as e:
        print(f"  ✗ 실패: {e}")
        import traceback
        traceback.print_exc()
    
    try:
        diff = test_current_vs_correct_implementation()
        if diff > EPSILON:
            print("\n[WARNING] 결론: 현재 구현에 문제가 있습니다!")
            print("   buildAnchorTransformMatrix 함수를 수정해야 합니다.")
        else:
            print("\n[OK] 결론: 현재 구현이 올바릅니다.")
    except Exception as e:
        print(f"  ✗ 실패: {e}")
        import traceback
        traceback.print_exc()
    
    try:
        test_inverse_transform()
    except Exception as e:
        print(f"  ✗ 실패: {e}")
        import traceback
        traceback.print_exc()
    
    print("\n" + "=" * 60)
    print("모든 테스트 완료")
    print("=" * 60)

if __name__ == "__main__":
    main()
