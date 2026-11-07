package io.github.cn3shy.text2img.error;

import org.springframework.http.HttpStatus;

/**
 * Base type for errors that are safe to return to the frontend as
 * {@code {"message": "..."}} with a specific HTTP status.
 */
public class Text2ImgException extends RuntimeException {

    private final HttpStatus status;

    public Text2ImgException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public Text2ImgException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
