package com.comatching.common.config;

import feign.Request;
import feign.RetryableException;
import feign.Retryer;

/**
 * GET 한정 1회 재시도.
 *
 * RetryableException 은 HTTP 응답을 받지 못한 전송 실패(연결 거부·타임아웃)다.
 * 배포 직후 재기동, 순간적인 커넥션 끊김 같은 일시 장애는 한 번만 다시 두드려도
 * 대부분 회복되므로, 안전한 요청에 한해 짧게 재시도한다.
 *
 * GET 으로 제한하는 이유: 전송 실패는 "서버가 처리를 했는지" 알 수 없는
 * 상태다. read-timeout 이라면 요청이 이미 서버에 도달해 처리됐을 수 있고,
 * 그 상태에서 POST(아이템 차감, 방 생성 등)를 다시 보내면 중복 실행이 된다.
 * GET 은 멱등이라 이 위험이 없다.
 *
 * 대가: GET 의 최악 지연이 read-timeout 두 배(기본 3s → 6.1s)가 된다.
 * 지속 장애라면 재시도까지 실패가 쌓여 서킷브레이커가 열리므로 두 배 대기가
 * 무한정 반복되지는 않는다. 재시도 전체가 브레이커 안에서 한 번의 호출로
 * 집계된다는 점도 참고.
 *
 * feign 은 요청마다 clone() 으로 새 인스턴스를 만들어 쓰므로 attempt 는
 * 요청 단위 상태다.
 */
public class InternalGetRetryer implements Retryer {

	private static final int MAX_ATTEMPTS = 2;
	private static final long BACKOFF_MILLIS = 100;

	private int attempt = 1;

	@Override
	public void continueOrPropagate(RetryableException e) {
		if (e.method() != Request.HttpMethod.GET || attempt >= MAX_ATTEMPTS) {
			throw e;
		}
		attempt++;

		try {
			Thread.sleep(BACKOFF_MILLIS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw e;
		}
	}

	@Override
	public Retryer clone() {
		return new InternalGetRetryer();
	}
}
