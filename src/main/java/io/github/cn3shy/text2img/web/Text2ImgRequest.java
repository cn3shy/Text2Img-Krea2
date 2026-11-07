package io.github.cn3shy.text2img.web;

/**
 * {@code POST /text2img} 的请求体。
 *
 * <pre>{@code
 * { "prompt": "一只橘猫", "width": 1024, "height": 1024 }
 * }</pre>
 *
 * <ul>
 *   <li>{@code prompt}：必填，最长 1200 字符；</li>
 *   <li>{@code width}/{@code height}：可选，缺省 1024，必须是 8 的倍数且在 256~2048 之间。</li>
 * </ul>
 */
public record Text2ImgRequest(String prompt, Integer width, Integer height) {
}
