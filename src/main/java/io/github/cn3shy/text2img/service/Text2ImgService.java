package io.github.cn3shy.text2img.service;

import io.github.cn3shy.text2img.web.Text2ImgRequest;

/**
 * 文字生图服务。
 *
 * <p>定义成接口方便以后替换实现（比如异步任务、其他后端），
 * Controller 只依赖这个接口，不关心具体是 ComfyUI 还是别的引擎。
 */
public interface Text2ImgService {

    /**
     * 根据提示词生成图片。
     *
     * @param request 前端请求参数
     * @return PNG 图片字节
     */
    byte[] generate(Text2ImgRequest request);
}
