package io.github.cn3shy.text2img.error;

import org.springframework.http.HttpStatus;

public class InvalidRequestException extends Text2ImgException {

    public InvalidRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
