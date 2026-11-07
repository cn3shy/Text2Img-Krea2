package io.github.cn3shy.text2img.error;

import org.springframework.http.HttpStatus;

public class GenerationTimeoutException extends Text2ImgException {

    public GenerationTimeoutException(String message) {
        super(HttpStatus.GATEWAY_TIMEOUT, message);
    }
}
