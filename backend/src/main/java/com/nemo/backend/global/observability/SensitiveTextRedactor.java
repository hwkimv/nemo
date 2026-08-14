// backend/src/main/java/com/nemo/backend/global/observability/SensitiveTextRedactor.java
package com.nemo.backend.global.observability;

import java.util.regex.Pattern;

/**
 * 자유 텍스트에서 비밀값처럼 보이는 부분을 가린다.
 *
 * 헤더나 쿼리 파라미터처럼 <b>이름이 있는</b> 값은 이름으로 판단할 수 있다.
 * 그런데 로그 메시지는 구조가 없다. "무엇이 비밀인지" 이름표가 붙어 있지 않다.
 * 그래서 모양으로 찾는다.
 *
 * 완벽하지 않다. 모양이 특이하지 않은 비밀값(예: 짧은 인증코드)은 걸러내지 못한다.
 * 그러므로 이것은 <b>마지막 그물이지 첫 번째 방어선이 아니다.</b>
 * 애초에 로그에 비밀값을 찍지 않는 것이 먼저다.
 * ({@link com.nemo.backend.config.LogConfig} 참고)
 */
public final class SensitiveTextRedactor {

    private SensitiveTextRedactor() {
    }

    public static final String REDACTED = "[redacted]";

    /** JWT 모양: eyJ로 시작하는 세 토막 base64url */
    private static final Pattern JWT = Pattern.compile(
            "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    /** Authorization 헤더 값 모양: "Bearer xxx", "Basic xxx" */
    private static final Pattern AUTH_SCHEME = Pattern.compile(
            "(?i)\\b(Bearer|Basic|Token)\\s+[A-Za-z0-9\\-._~+/]+=*");

    /**
     * name=value 또는 name: value 에서 이름이 민감한 경우.
     * 값은 공백·&·따옴표·쉼표 전까지로 본다.
     */
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)\\b(authorization|access[-_]?token|refresh[-_]?token|id[-_]?token|token|password|passwd|secret|client[-_]?secret|credential|api[-_]?key|apikey)"
                    + "(\\s*[=:]\\s*)"
                    + "[^\\s&,;\"']+");

    /**
     * 순서가 중요하다. 이름이 붙은 것부터 지우고, 남은 것 중 모양으로 잡히는 것을 지운다.
     * 반대로 하면 "token=eyJ..." 이 "token=[redacted]" 가 아니라
     * "token=[redacted-jwt]" 같은 어중간한 형태로 남는다.
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = NAMED_SECRET.matcher(text).replaceAll("$1$2" + REDACTED);
        out = AUTH_SCHEME.matcher(out).replaceAll("$1 " + REDACTED);
        out = JWT.matcher(out).replaceAll(REDACTED);
        return out;
    }

    /** 키 이름 자체가 민감한지 (breadcrumb data, extra 등의 map 키 판단용) */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("token")
                || lower.contains("password")
                || lower.contains("passwd")
                || lower.contains("secret")
                || lower.contains("authorization")
                || lower.contains("credential")
                || lower.contains("apikey")
                || lower.contains("api_key")
                || lower.contains("api-key");
    }
}
