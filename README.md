# freewall-and

검열 없는 안드로이드 환경을 위한 **freewall Android** 클라이언트 앱입니다.

> *Privacy is a universal right.* — Mullvad VPN

---

## 주요 기능

- **검증된 DPI 패킷 우회 엔진**:
  - TLS ClientHello 첫 1바이트 단독 전송 + 20ms 지연을 통한 SNI 감청 무력화
  - ISP 방화벽 SNI 차단(Warning.or.kr 등) 완벽 우회
- **원터치 보호 (One-touch Protection)**:
  - 홈 화면의 대형 히어로 파워 서클 버튼으로 직관적인 ON / OFF
  - 상단 알림창 스와이프 방지 상시 고정 알림 (Ongoing Notification)
- **상단바 퀵 설정 타일 (Quick Settings Tile)**:
  - 앱을 켜지 않고도 상단 제어센터에서 원터치로 DPI 보호 시작/중지
- **화면 3대 분리 네비게이션**:
  - **보호 (홈)**: 대형 전원 버튼과 상태 표시등 중심의 심플한 화면
  - **설정**: DNS 보안 리다이렉션 (Quad9 UDP 9953), 우회 프리셋 선택, 자동 시작
  - **로그**: 실시간 바이패스 이벤트 스트리밍, 복사 및 비우기 지원 터미널 뷰어
- **100% 코틀린 네이티브 프록시**:
  - 루팅이 필요 없는 비루팅 환경 동작
  - Android 14+ (`targetSdk 37`) 최신 포그라운드 서비스 가이드라인 완벽 준수
