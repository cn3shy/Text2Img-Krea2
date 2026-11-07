package io.github.cn3shy.text2img.service;

import io.github.cn3shy.text2img.web.Text2ImgRequest;

public interface Text2ImgService {

    /**
     * Generates an image and returns the encoded PNG bytes.
     */
    byte[] generate(Text2ImgRequest request);
}
