package io.github.cn3shy.text2img.error;

import org.springframework.http.HttpStatus;

public class UpstreamException extends Text2ImgException {

    public UpstreamException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public UpstreamException(String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }
}
