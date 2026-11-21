# 이슈 227 공식 Java SDK 호환성 실험

| 공급자 | SDK | 도입 판단 | 보류 근거 |
|---|---|---|---|
| OpenAI | `com.openai:openai-java:4.111.0` | **보류** | 실모델의 도구·스키마 동시 사용 미검증, 응답 전 코루틴 취소 시 HTTP 전송 종료를 확인하지 못함 |
| Gemini | `com.google.genai:google-genai:2.6.0` | **보류** | 실모델의 도구·스키마 동시 사용 미검증, 모델 목록의 깨진 2xx 본문 및 요약 응답의 명시적 null 처리 차이, 코루틴 취소 후에도 응답 전송 완료 |

## 요청과 응답

| 항목 | OpenAI | Gemini |
|---|---|---|
| 요약 경로 | Ktor와 SDK 모두 `POST /v1/responses` | `POST /v1beta/models/{model}:generateContent`와 버전 없는 `POST /models/{model}:generateContent` 각각 일치. SDK의 `apiVersion("")`으로 baseUrl 경로를 그대로 사용 |
| 키 검증 경로 | `GET /v1/models` 일치 | `GET /v1beta/models`와 `GET /models` 각각 일치 |
| 인증 | POST와 GET의 Bearer 헤더 일치 | Ktor는 POST와 GET의 `key` 쿼리, SDK는 두 요청의 `x-goog-api-key` 헤더. 공개 API 문서에서 헤더 방식을 명시 |
| 입력과 스키마 | model, instructions, user의 input_text, web_search, 스키마 이름과 strict 값 일치. 입력 메시지의 선택 필드 `type`은 `JsonMissing`으로 생략. 실제 스키마 전체를 `AiSummarySpec.openAiJsonSchema`와 비교 | 모델 경로, 사용자 contents, 도구와 application/json 동시 전달 확인. SDK가 `systemInstruction`과 `urlContext`를 전송하고 Ktor는 두 필드의 snake_case 이름을 사용. 실제 스키마 전체를 `AiSummarySpec.geminiResponseSchema`와 비교 |
| 텍스트 선택 | 전체 output의 content를 순서대로 탐색. 빈 content, null 및 공백 text를 건너뜀 | 첫 candidate의 parts만 탐색. 첫 candidate에 텍스트가 없으면 후속 candidate에 유효한 텍스트가 있어도 `AI_EMPTY_RESPONSE`. SDK의 타입 변환은 part의 명시적 `"text":null`에 `GenAiIOException` 발생 |
| 요약 필드 | 정규화한 rawResponse, linkId 우선 및 id 대체, nullable folderId, title, summary, tags, todos, nullable reminderAt 일치 | 동일 |
| 사용량 | usage 없음 → trim한 텍스트 길이, 합계 누락 → 0, 명시적 0 → 0, 양수 → 지정값 | 동일 |
| 실패 | 빈 출력 및 공백만 있는 출력은 `AI_EMPTY_RESPONSE`. 선택된 텍스트의 잘못된 JSON은 공통 파서의 `JsonDecodingException` | 빈 후보, 유효 part 없음, 공백만 있는 후보와 잘못된 모델 JSON에서 동일 |

Gemini Developer API 문서는 `x-goog-api-key`와 버전이 포함된 경로를 명시한다. 로컬 버전 없는 경로는 기존 baseUrl 처리와의 일치만 검증한다. camelCase 요청 필드도 공식 레퍼런스에 기재되어 있다. 지원 문서는 대상 모델의 도구·스키마 동시 실행 여부를 확인하는 근거와 구분했다. [Gemini API 인증](https://ai.google.dev/api) · [Gemini generateContent](https://ai.google.dev/api/generate-content) · [Gemini 구조화 출력과 도구](https://ai.google.dev/gemini-api/docs/structured-output) · [OpenAI 명시 스키마 예제](https://github.com/openai/openai-java/blob/v4.111.0/openai-java-example/src/main/java/com/openai/example/ResponsesStructuredOutputsRawExample.java)

## 키 검증과 실행

| 실험 | Ktor | OpenAI SDK | Gemini SDK |
|---|---|---|---|
| 빈 2xx 본문 / 빈 모델 목록 / 해석 불가능한 2xx / 다음 페이지 토큰 | 성공 / 성공 / 성공 / 성공 | 성공 / 성공 / 성공 / 성공. 첫 응답을 파싱하지 않고 닫음 | 성공 / 성공 / `GenAiIOException` / 성공. 동기 SDK 목록 호출을 IO 디스패처에서 실행 |
| 페이지와 요청 수 | 검증마다 GET 1회. 후속 GET과 추론 POST 0회 | 동일. SDK의 모델 목록은 페이지 넘김을 지원하지 않는다 | 동일. 실제 첫 페이지에 토큰이 있는 fixture를 사용 |
| 401·403·503·429·307 | 원래 상태를 등록 서비스까지 전달 | 동일 | 동일. 비동기 페이지를 기다리면 SDK가 첫 페이지의 오류를 `GenAiIOException`으로 감싸므로 상태 보존 실험에 동기 호출 사용 |
| 307 리디렉션 | 요약과 검증 각각 최초 1회, 목적지 0회. 요약은 `ContentConvertException` | 요청 수 동일. 요약은 `UnexpectedStatusCodeException` | 요청 수 동일. 요약은 `ApiException` |
| 첫 후보 503 후 다음 후보 성공 | 후보당 POST 1회, 워커의 성공 모델·요약 저장 확인 | 시도 순서와 저장 결과 동일. 실패 원인은 `InternalServerException` | 시도 순서와 저장 결과 동일. 실패 원인은 `ServerException` |
| 지연 응답 | `HttpRequestTimeoutException` | `OpenAIIoException` | `GenAiIOException` |
| 요약 응답 헤더 전 코루틴 취소 | 호출자 취소, 서버의 응답 쓰기 실패 | 호출자 취소, 2초 관찰 창 내 서버의 쓰기 결과 미확인 | 호출자 취소, 서버의 응답 쓰기 완료 |
| 시험 키·목적지 교차 및 동시 호출 | 각 요청의 키·목적지 일치. 같은 목적지에 순차 요청 4회의 TCP 연결 4개 | 키·목적지 일치. 순차 요청 4회에 연결 1개 | 키·목적지 일치. 순차 요청 4회에 연결 1개 |

등록 서비스에서 401·403은 `AI_API_KEY_INVALID`, 503은 `AI_VENDOR_TEMPORARY_UNAVAILABLE`, 429·307은 `COMMON_INTERNAL_SERVER_ERROR`가 되어 기존 암호문을 유지했다. 서비스 및 워커 실험은 기존 Koin 테스트 라우터의 외부 호출을 실제 Ktor 또는 SDK 실험으로 위임한다.

Ktor CIO의 연결 제한 5초와 요청 제한 15초를 기준으로 SDK의 연결 및 요청 제한을 구성했다. 지연 응답 실험은 같은 요청 제한 700ms를 적용했고 요청 수는 각 1회였다. OpenAI는 `maxRetries(0)`, Gemini는 `attempts(1)`과 `retryOnConnectionFailure(false)`를 사용한다. 취소 실험에서는 시작된 요청 1회와 후속 요청 0회를 관찰했다.

## 실모델 실행

| 공급자 | 대상 모델 후보 | 도구와 스키마 동시 수용 및 응답 적합성 |
|---|---|---|
| OpenAI | `gpt-5.5`, `gpt-5.4`, `gpt-5.4-mini` | 미검증 |
| Gemini | `gemini-3.5-flash`, `gemini-3-flash-preview`, `gemini-3.1-flash-lite` | 미검증 |

대상은 [모델 시드 설정](../src/main/resources/db/data/afterMigrate.sql)의 후보들이다. 시험 전용 자격증명과 실호출 허가가 제공되지 않아 공급자 호출은 0회였다.

## 재현과 증거

- [요청·응답·실행 계약 실험](../src/test/kotlin/com/qlink/ai/client/AiSdkCompatibilityTest.kt), [요약 fixture](../src/test/kotlin/com/qlink/support/fixture/AiCompatibilityFixture.kt).
- [등록 서비스 상태 및 저장 실험](../src/test/kotlin/com/qlink/ai/service/PutAiUserProviderServiceTest.kt), [워커 후보 순서와 저장 실험](../src/test/kotlin/com/qlink/ai/worker/service/AiSummaryWorkerRegressionTest.kt).
- `test --tests '*AiSdkCompatibilityTest' --tests '*PutAiUserProviderServiceTest' --tests '*AiSummaryWorkerRegressionTest'`를 실행하면 `build/sdk-compatibility/`에 OpenAI `openai-v1-{ktor,sdk}.json`과 Gemini `gemini-{v1beta,root}-{ktor,sdk}.json`이 생성된다. 사용량·오류·취소·연결 관찰값은 `build/test-results/test/TEST-com.qlink.ai.client.AiSdkCompatibilityTest.xml`에 남는다.
