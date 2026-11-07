package io.github.cn3shy.text2img.service;

import io.github.cn3shy.text2img.error.InvalidRequestException;
import io.github.cn3shy.text2img.web.Text2ImgRequest;

/**
 * 请求参数校验器。
 *
 * <p>规则与前端约定保持一致：
 * <ul>
 *   <li>prompt 必填，去掉首尾空格后不能为空，最多 1200 个字符（按 Unicode 码点计算）；</li>
 *   <li>width/height 可不传，缺省 1024；传了则必须在 256~2048 之间且是 8 的倍数。</li>
 * </ul>
 *
 * <p>工具类，不需要注册成 Bean，直接静态调用。
 */
public final class RequestValidator {

    /** prompt 最大长度，和接口文档一致。 */
    public static final int MAX_PROMPT_LENGTH = 1200;
    /** 未指定尺寸时的默认边长。 */
    public static final int DEFAULT_SIZE = 1024;
    /** 允许的最小/最大边长。 */
    public static final int MIN_SIZE = 256;
    public static final int MAX_SIZE = 2048;
    /** 模型 latent 要求尺寸是 8 的倍数。 */
    public static final int SIZE_STEP = 8;

    private RequestValidator() {
    }

    /**
     * 校验并返回规范化后的请求。
     *
     * @throws InvalidRequestException 参数不合法时抛出（HTTP 400）
     */
    public static GenerationRequest validate(Text2ImgRequest request) {
        if (request == null) {
            throw new InvalidRequestException("prompt 不能为空");
        }

        // 去掉首尾空白；null 也按空字符串处理
        String prompt = request.prompt() == null ? "" : request.prompt().trim();
        if (prompt.isEmpty()) {
            throw new InvalidRequestException("prompt 不能为空");
        }
        // 用 codePointCount 而不是 length，避免 emoji 等代理对字符被算成两个
        if (prompt.codePointCount(0, prompt.length()) > MAX_PROMPT_LENGTH) {
            throw new InvalidRequestException("prompt 最长 " + MAX_PROMPT_LENGTH + " 字符");
        }

        return new GenerationRequest(prompt, dimension(request.width(), "width"),
                dimension(request.height(), "height"));
    }

    /** 校验单个尺寸；未传时使用默认值。 */
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
