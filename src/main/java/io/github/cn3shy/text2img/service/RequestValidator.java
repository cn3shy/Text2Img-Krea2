package io.github.cn3shy.text2img.service;

import io.github.cn3shy.text2img.error.InvalidRequestException;
import io.github.cn3shy.text2img.web.Text2ImgRequest;

public final class RequestValidator {

    public static final int MAX_PROMPT_LENGTH = 1200;
    public static final int DEFAULT_SIZE = 1024;
    public static final int MIN_SIZE = 256;
    public static final int MAX_SIZE = 2048;
    public static final int SIZE_STEP = 8;

    private RequestValidator() {
    }

    public static GenerationRequest validate(Text2ImgRequest request) {
        if (request == null) {
            throw new InvalidRequestException("prompt 不能为空");
        }
        String prompt = request.prompt() == null ? "" : request.prompt().trim();
        if (prompt.isEmpty()) {
            throw new InvalidRequestException("prompt 不能为空");
        }
        if (prompt.codePointCount(0, prompt.length()) > MAX_PROMPT_LENGTH) {
            throw new InvalidRequestException("prompt 最长 " + MAX_PROMPT_LENGTH + " 字符");
        }
        return new GenerationRequest(prompt, dimension(request.width(), "width"),
                dimension(request.height(), "height"));
    }

    private static int dimension(Integer value, String name) {
        if (value == null) {
            return DEFAULT_SIZE;
        }
        if (value < MIN_SIZE || value > MAX_SIZE || value % SIZE_STEP != 0) {
            throw new InvalidRequestException(
                    name + " 必须是 " + MIN_SIZE + "~" + MAX_SIZE + " 之间且为 " + SIZE_STEP + " 的倍数");
        }
        return value;
    }
}
