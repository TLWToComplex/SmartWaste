package com.example.shizhan.rubbish.controller.android;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.shizhan.rubbish.common.R;
import com.example.shizhan.rubbish.common.SessionIdentity;
import com.example.shizhan.rubbish.entity.Classification;
import com.example.shizhan.rubbish.entity.ClassifyData;
import com.example.shizhan.rubbish.service.Interface.ClassificationService;
import com.example.shizhan.rubbish.service.VisionClassificationService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/classification")
public class ClassificationController {
    private final ClassificationService service;
    private final VisionClassificationService vision;

    public ClassificationController(ClassificationService service, VisionClassificationService vision) {
        this.service = service;
        this.vision = vision;
    }

    @GetMapping
    public R<Page<Classification>> selectPage(@RequestParam(defaultValue = "1") Integer page,
            @RequestParam(required = false) Integer sortId, @RequestParam(defaultValue = "") String name) {
        Page<Classification> pageInfo = new Page<>(Math.max(1, page), 20);
        LambdaQueryWrapper<Classification> query = new LambdaQueryWrapper<>();
        query.eq(sortId != null, Classification::getSortId, sortId)
                .like(Classification::getName, name).orderByAsc(Classification::getId);
        service.page(pageInfo, query);
        return R.success(pageInfo);
    }

    @PostMapping
    public R<ClassifyData> classify(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        SessionIdentity.requireUser(request);
        return R.success(vision.classify(file));
    }
}
