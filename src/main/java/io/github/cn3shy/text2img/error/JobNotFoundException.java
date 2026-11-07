package io.github.cn3shy.text2img.error;

import org.springframework.http.HttpStatus;

/** 任务不存在或已过期，统一返回 HTTP 404。 */
public class JobNotFoundException extends Text2ImgException {

    public JobNotFoundException() {
        super(HttpStatus.NOT_FOUND, "任务不存在或已过期");
    }
}
