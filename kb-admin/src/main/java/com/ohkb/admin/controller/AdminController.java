package com.ohkb.admin.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 管理后台主控制器（Thymeleaf 服务端渲染）。
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    @GetMapping({"", "/"})
    public String index() {
        return "admin/index";
    }

    @GetMapping("/kb")
    public String knowledgeBase() {
        return "admin/kb/list";
    }

    @GetMapping("/tickets")
    public String tickets() {
        return "admin/tickets/list";
    }

    @GetMapping("/analytics")
    public String analytics() {
        return "admin/analytics/dashboard";
    }
}
