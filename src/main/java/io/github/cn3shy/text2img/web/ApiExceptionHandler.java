package io.github.cn3shy.text2img.web;

import io.github.cn3shy.text2img.error.Text2ImgException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。
 *
 * <p>所有错误都统一返回 {@code {"message": "..."}}，前端在非 2xx 响应里会读取
 * message / msg / error / detail 字段并展示。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 业务异常：异常自带 HTTP 状态码（400/503/504）。 */
    @ExceptionHandler(Text2ImgException.class)
    public ResponseEntity<Map<String, String>> handleText2Img(Text2ImgException exception) {
        if (exception.status().is5xxServerError()) {
            log.warn("生图失败: status={} message={}", exception.status().value(), exception.getMessage());
        }
        return ResponseEntity.status(exception.status())
                .body(Map.of("message", exception.getMessage()));
    }

    /** 请求体不是合法 JSON，或字段类型对不上。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        log.debug("请求体解析失败", exception);
        return ResponseEntity.badRequest()
                .body(Map.of("message", "请求体不是合法的 JSON"));
    }

    /** 兜底处理：未被上面规则覆盖的异常。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception exception) {
        // Spring MVC 自身抛出的异常（405、404、415 等）已经带有正确的状态码，
        // 不能被兜底处理器改写成 500。
        if (exception instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            if (status.is5xxServerError()) {
                log.error("请求处理失败 status={}", status.value(), exception);
            }
            return ResponseEntity.status(status).body(Map.of("message", messageFor(status)));
        }
        log.error("未预期的服务端异常", exception);
        return ResponseEntity.internalServerError()
                .body(Map.of("message", "服务器内部错误，请稍后重试"));
    }

    private static String messageFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> "接口不存在";
            case 405 -> "请求方法不支持";
            case 415 -> "不支持的 Content-Type";
            default -> "请求处理失败";
        };
    }
}
