# ALink · 에이링크

**AI가 도와주는 북마크 아카이브 서비스**

흩어진 링크를 간편하게 저장하고, AI의 도움으로 요약하고 정리하세요.
키워드로 북마크를 다시 찾고, 저장한 정보를 액션으로 이어갈 수 있습니다.

[웹 바로가기](https://archivelink.app) · [App Store](https://apps.apple.com/kr/app/%EC%97%90%EC%9D%B4%EB%A7%81%ED%81%AC-alink-ai%EA%B0%80-%EB%8F%84%EC%99%80%EC%A3%BC%EB%8A%94-%EB%B6%81%EB%A7%88%ED%81%AC-%EC%95%84%EC%B9%B4%EC%9D%B4%EB%B8%8C/id6788230955) · [사용 가이드](https://warp-raft-63c.notion.site/ALink-Tips-412d602d12548000ab51c9da56783374)

## 소개 영상

[ALink Tips](https://warp-raft-63c.notion.site/ALink-Tips-412d602d12548000ab51c9da56783374)의 실제 사용 영상입니다. 미리보기를 클릭하면 가이드의 원본 영상으로 이동합니다.

| 빠른 아카이브 | 간편한 검색 |
| :---: | :---: |
| [<img src=".github/assets/readme/share-demo.gif" width="240" alt="공유 메뉴에서 링크를 ALink에 저장하는 영상">](https://warp-raft-63c.notion.site/ALink-Tips-412d602d12548000ab51c9da56783374#413d602d125480f59716e5ee5fb698be) | [<img src=".github/assets/readme/search-demo.gif" width="240" alt="키워드로 저장한 북마크를 검색하는 영상">](https://warp-raft-63c.notion.site/ALink-Tips-412d602d12548000ab51c9da56783374#413d602d125480a8a006f2853608530a) |
| 유튜브, 인스타그램, 브라우저에서 간편하게 저장 | 제목을 몰라도 생각나는 키워드로 검색 |

## Backend

ALink의 API 서버, 링크 수집 Lambda, AWS 인프라 구성을 관리하는 레포지토리입니다.

## Tech Stack

| 분야 | 기술 | 용도 |
| --- | --- | --- |
| Language & Runtime | ![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white) ![JVM 21](https://img.shields.io/badge/JVM%2021-437291?style=flat) | Kotlin 기반 API 서버 |
| Framework | ![Ktor](https://img.shields.io/badge/Ktor-087CFA?style=flat&logo=ktor&logoColor=white) ![Netty](https://img.shields.io/badge/Netty-4C8CBF?style=flat) | 비동기 HTTP 서버 |
| Dependency Injection | ![Koin](https://img.shields.io/badge/Koin-F6A800?style=flat) | 의존성 주입 |
| ORM & Database Access | ![Exposed](https://img.shields.io/badge/Exposed-7F52FF?style=flat) ![HikariCP](https://img.shields.io/badge/HikariCP-168B8B?style=flat) | SQL DSL과 JDBC 커넥션 풀 |
| Database | ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?style=flat&logo=postgresql&logoColor=white) ![pg_bigm](https://img.shields.io/badge/pg_bigm-336791?style=flat) | 데이터 저장과 키워드 검색 인덱스 |
| Migration | ![Flyway](https://img.shields.io/badge/Flyway-CC0200?style=flat&logo=flyway&logoColor=white) | 데이터베이스 스키마 버전 관리 |
| AI | ![Google Gemini](https://img.shields.io/badge/Google%20Gemini-8E75B2?style=flat&logo=googlegemini&logoColor=white) | 북마크 요약과 정리 |
| Authentication | ![JWT](https://img.shields.io/badge/JWT-333333?style=flat&logo=jsonwebtokens&logoColor=white) | 토큰 기반 인증 |
| API Documentation | ![OpenAPI](https://img.shields.io/badge/OpenAPI-6BA539?style=flat&logo=openapiinitiative&logoColor=white) ![Swagger UI](https://img.shields.io/badge/Swagger%20UI-85EA2D?style=flat&logo=swagger&logoColor=white) ![ReDoc](https://img.shields.io/badge/ReDoc-8A2BE2?style=flat) | API 명세와 인터랙티브 문서 |
| Link Extraction | ![Node.js](https://img.shields.io/badge/Node.js-5FA04E?style=flat&logo=nodedotjs&logoColor=white) ![AWS Lambda](https://img.shields.io/badge/AWS%20Lambda-FF9900?style=flat) ![Mozilla Readability](https://img.shields.io/badge/Mozilla%20Readability-FF7139?style=flat) ![Cheerio](https://img.shields.io/badge/Cheerio-E88C1F?style=flat&logo=cheerio&logoColor=white) | 링크 메타데이터와 본문 추출 |
| Infrastructure | ![Amazon ECS](https://img.shields.io/badge/Amazon%20ECS-FF9900?style=flat) ![Amazon RDS](https://img.shields.io/badge/Amazon%20RDS-527FFF?style=flat) ![Amazon S3](https://img.shields.io/badge/Amazon%20S3-569A31?style=flat) ![CloudFront](https://img.shields.io/badge/CloudFront-8C4FFF?style=flat) | 컨테이너 실행, 데이터베이스, 파일 저장과 CDN |
| Infrastructure as Code | ![Terraform](https://img.shields.io/badge/Terraform-844FBA?style=flat&logo=terraform&logoColor=white) ![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat&logo=docker&logoColor=white) | 인프라 구성과 컨테이너화 |
| Observability | ![Micrometer](https://img.shields.io/badge/Micrometer-117388?style=flat) ![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=flat&logo=prometheus&logoColor=white) ![Logback](https://img.shields.io/badge/Logback-425A70?style=flat) | 메트릭 노출과 애플리케이션 로그 |
| Testing | ![Kotest](https://img.shields.io/badge/Kotest-66A526?style=flat) ![Testcontainers](https://img.shields.io/badge/Testcontainers-2496ED?style=flat) ![JaCoCo](https://img.shields.io/badge/JaCoCo-C71A36?style=flat) | 도메인·서비스 테스트, 통합 테스트, 커버리지 |
| Build & CI | ![Gradle](https://img.shields.io/badge/Gradle-02303A?style=flat&logo=gradle&logoColor=white) ![Spotless](https://img.shields.io/badge/Spotless-2563EB?style=flat) ![GitHub Actions](https://img.shields.io/badge/GitHub%20Actions-2088FF?style=flat&logo=githubactions&logoColor=white) | 빌드, 포맷 검사, CI |
