package io.github.cn3shy.text2img.service;

/**
 * Validated, normalised version of {@link io.github.cn3shy.text2img.web.Text2ImgRequest}.
 */
public record GenerationRequest(String prompt, int width, int height) {
}
