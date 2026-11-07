package io.github.cn3shy.text2img.web;

import io.github.cn3shy.text2img.service.Text2ImgService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Frontend calls {@code POST /api/text2img}; the Vite/Vercel proxy strips the
 * {@code /api} prefix, so this application exposes {@code POST /text2img}.
 */
@RestController
@RequestMapping("/text2img")
public class Text2ImgController {

    private final Text2ImgService service;

    public Text2ImgController(Text2ImgService service) {
        this.service = service;
    }

    @PostMapping(produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> text2img(@RequestBody(required = false) Text2ImgRequest request) {
        byte[] png = service.generate(request);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }
}
