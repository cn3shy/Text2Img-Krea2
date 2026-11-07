package io.github.cn3shy.text2img.web;

import io.github.cn3shy.text2img.error.CapacityExceededException;
import io.github.cn3shy.text2img.service.GenerationCapacity;
import io.github.cn3shy.text2img.service.Text2ImgService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文字生图接口。
 *
 * <p>前端请求的是 {@code POST /api/text2img}，Vite/Vercel 代理会剥掉 {@code /api}
 * 前缀，所以 Spring Boot 这边实际暴露的是 {@code POST /text2img}。
 */
@RestController
@RequestMapping("/text2img")
public class Text2ImgController {

    private final Text2ImgService service;
    private final GenerationCapacity capacity;

    /**
     * 构造器注入：只有一个构造器时 Spring 会自动装配，无需 {@code @Autowired}。
     */
    public Text2ImgController(Text2ImgService service, GenerationCapacity capacity) {
        this.service = service;
        this.capacity = capacity;
    }

    /**
     * 同步出图。成功时直接返回 PNG 字节，前端按 {@code image/png} 处理；
     * 失败时由 {@link ApiExceptionHandler} 转成 {@code {"message": "..."}}。
     *
     * <p>这是 legacy 入口，与异步任务共享并发额度；线上前端已不再调用它。
     */
    @PostMapping(produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> text2img(@RequestBody(required = false) Text2ImgRequest request) {
        if (!capacity.tryAcquire()) {
            throw new CapacityExceededException();
        }
        try {
            byte[] png = service.generate(request);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(png);
        } finally {
            capacity.release();
        }
    }
}
