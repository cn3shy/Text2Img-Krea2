package io.github.cn3shy.text2img.service;

/**
 * 校验、补默认值之后用于真正出图的请求。
 *
 * <p>与 {@link io.github.cn3shy.text2img.web.Text2ImgRequest} 的区别：
 * 这里的 prompt 已经 trim 过，width/height 一定非空，可以安全地直接使用。
 */
public record GenerationRequest(String prompt, int width, int height) {
}
