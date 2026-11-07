package io.github.cn3shy.text2img.error;

import org.springframework.http.HttpStatus;

/** 同时进行的生成任务已达上限，统一返回 HTTP 503。 */
public class CapacityExceededException extends Text2ImgException {

    public CapacityExceededException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "当前生成任务较多，请稍后再试");
    }
}
