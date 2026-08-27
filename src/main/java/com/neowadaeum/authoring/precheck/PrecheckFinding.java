package com.neowadaeum.authoring.precheck;

/**
 * 무엇이 어디서 걸렸나 (R8.2).
 *
 * <p><b>걸린 항목 자체는 담지 않는다</b> (R8.7, S-11). 담기면 응답이 곧 우회 사전이 된다 —
 * 작성자가 알아야 하는 것은 <b>어디를 고쳐야 하는가</b>이지 무엇이 목록에 있는가가 아니다.
 *
 * @param field 필드 경로. 예 {@code characters[0].name}
 * @param span 원문에서의 {@code [시작, 끝)} 문자 오프셋. 클라이언트가 그 자리에 밑줄을 긋는다
 * @param kind 분류. {@code "부적절한 내용입니다"} 가 아니라 무엇이 문제인지 드러나야 한다
 * @param message 사람이 읽는 안내. <b>원문도 항목도 넣지 않는다</b>
 */
public record PrecheckFinding(String field, int[] span, String kind, String message) {
}
