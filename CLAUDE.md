# COMAtching Backend

Spring 기반 MSA. 서비스는 gateway / user / matching / chat / item / notification 6개이며
`common-module` 을 공유한다. 서비스 간 통신은 내부 REST(`/api/internal/**`)와 Kafka 이벤트.

## 코드 리뷰

리뷰 기준은 [docs/code-review-guidelines.md](docs/code-review-guidelines.md) 에 있다.
코드 리뷰를 할 때는 그 문서를 먼저 읽고 거기 적힌 페르소나·언어·관점을 따른다.
요약하면:

- **리뷰 코멘트는 한국어로 작성한다.**
- Spring MSA 실무 경험이 있는 Senior Back-end Engineer 관점으로 본다.
- "동작한다"는 이유만으로 승인하지 않고, 미래의 변경에 얼마나 버티는지를 본다.
- 문제를 지적할 때는 "왜 문제인지"와 "어떻게 개선할지"를 함께 쓴다.

이 저장소 특유의 주의점:

- **서비스 경계를 넘는 변경은 하위 호환성을 특히 주의해서 본다** —
  `common-module` 의 DTO, 서비스 간 내부 API, Kafka 이벤트 스키마는
  배포 순서가 어긋나는 순간 깨진다.
  - **단, PR 자동 리뷰(`claude-code-review.yml`)는 diff 에 나온 코드만 보고
    끝낸다.** 다른 서비스로 건너가 소비 코드를 찾아 읽는 하위 호환성
    분석은 턴·시간을 크게 늘린다 — diff 가 `common-module`/내부 API/Kafka
    스키마를 건드리면 자동 리뷰는 코멘트에 "cross-service 영향 확인 필요"
    한 줄만 남기고, 실제 분석은 PR 코멘트에 `@claude` 로 명시적으로
    요청했을 때(`claude.yml`)만 수행한다.
- 운영 설정(`docker-compose.prod.yml`, `application-aws.yml`)은 EC2 단일 호스트에
  6개 JVM 이 함께 뜨는 전제로 메모리 한도가 잡혀 있다. 한도를 건드리는 변경은
  전체 예산과 함께 본다.
