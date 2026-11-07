package io.github.cn3shy.text2img.error;

import org.springframework.http.HttpStatus;

/** 等待 ComfyUI 出图超时，统一返回 HTTP 504。 */
public class GenerationTimeoutException extends Text2ImgException {

    public GenerationTimeoutException(String message) {
        super(HttpStatus.GATEWAY_TIMEOUT, message);
    }
}
