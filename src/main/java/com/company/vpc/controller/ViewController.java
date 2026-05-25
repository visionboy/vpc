package com.company.vpc.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Thymeleaf 뷰 반환 컨트롤러.
 *
 * <p>VPC Peering 자동화 콘솔의 SPA(Single Page Application) 진입점 역할을 한다.
 * "/" 요청에 {@code index.html} 템플릿을 반환하며,
 * 이후 모든 API 호출은 {@code /api/v1/**} 엔드포인트를 통해 JSON으로 처리된다.
 */
@Controller
public class ViewController {

    /**
     * 루트 경로 접속 시 메인 콘솔 화면 반환.
     *
     * @return Thymeleaf 템플릿 이름 ("index" → templates/index.html)
     */
    @GetMapping("/")
    public String index() {
        return "index";
    }
}
