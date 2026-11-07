package io.github.cn3shy.text2img.error;

import org.springframework.http.HttpStatus;

/** 调用 ComfyUI 失败（连不上、拒绝工作流、返回异常等），统一返回 HTTP 503。 */
public class UpstreamException extends Text2ImgException {

    public UpstreamException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public UpstreamException(String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }
}
