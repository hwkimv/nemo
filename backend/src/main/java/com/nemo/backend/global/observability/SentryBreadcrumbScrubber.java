// backend/src/main/java/com/nemo/backend/global/observability/SentryBreadcrumbScrubber.java
package com.nemo.backend.global.observability;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryOptions;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Sentry breadcrumb에서 비밀값처럼 보이는 부분을 가린다.
 *
 * <h3>왜 별도로 필요한가</h3>
 * {@link SentryScrubber}는 이벤트의 <b>request와 user</b>만 손본다. breadcrumb은 건드리지 않는다.
 * 그런데 설정이 {@code sentry.logging.minimum-breadcrumb-level: info}이므로
 * <b>INFO 이상 로그가 전부 breadcrumb으로 Sentry에 실려 간다.</b>
 *
 * 즉 누군가 {@code log.info("... token={}", token)} 한 줄을 추가하면,
 * request 스크러빙을 아무리 촘촘히 해도 그 값은 그대로 나간다.
 *
 * 이 구조는 {@link SentryScrubber}에서 헤더를 허용 목록으로 처리한 이유와 같다.
 * <b>새로 추가되는 것이 기본적으로 안전한 쪽에 서야 한다.</b>
 * 로그는 계속 추가되고, 추가하는 사람이 Sentry를 떠올릴 것이라고 기대할 수 없다.
 *
 * <h3>한계</h3>
 * 로그 메시지는 구조가 없어 "이름으로" 판단할 수 없고 모양으로 찾는다.
 * 모양이 특이하지 않은 비밀값은 걸러내지 못한다.
 * <b>애초에 로그에 비밀값을 찍지 않는 것이 먼저다.</b> 이건 마지막 그물이다.
 */
@Component
public class SentryBreadcrumbScrubber implements SentryOptions.BeforeBreadcrumbCallback {

    @Override
    public Breadcrumb execute(Breadcrumb breadcrumb, Hint hint) {
        breadcrumb.setMessage(SensitiveTextRedactor.redact(breadcrumb.getMessage()));

        Map<String, Object> data = breadcrumb.getData();
        if (data != null && !data.isEmpty()) {
            Map<String, Object> safe = new HashMap<>(data.size());
            data.forEach((key, value) -> {
                if (SensitiveTextRedactor.isSensitiveKey(key)) {
                    safe.put(key, SensitiveTextRedactor.REDACTED);
                } else if (value instanceof String text) {
                    safe.put(key, SensitiveTextRedactor.redact(text));
                } else {
                    safe.put(key, value);
                }
            });
            safe.forEach(breadcrumb::setData);
        }

        return breadcrumb;
    }
}
