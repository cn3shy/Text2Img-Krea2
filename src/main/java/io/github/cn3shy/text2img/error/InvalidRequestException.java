package io.github.cn3shy.text2img.error;

import org.springframework.http.HttpStatus;

/** 请求参数不合法，统一返回 HTTP 400。 */
public class InvalidRequestException extends Text2ImgException {

    public InvalidRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
