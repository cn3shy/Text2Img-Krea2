package io.github.cn3shy.text2img.web;

/**
 * Body of {@code POST /text2img}. {@code width}/{@code height} are optional and
 * default to 1024x1024; {@code prompt} is required.
 */
public record Text2ImgRequest(String prompt, Integer width, Integer height) {
}
