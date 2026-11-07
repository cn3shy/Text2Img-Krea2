package io.github.cn3shy.text2img.error;

import org.springframework.http.HttpStatus;

/** 任务还没有成功出图（进行中或已失败），统一返回 HTTP 409。 */
public class JobNotReadyException extends Text2ImgException {

    public JobNotReadyException() {
        super(HttpStatus.CONFLICT, "图片还没准备好");
    }
}
