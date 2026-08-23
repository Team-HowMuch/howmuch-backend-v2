package com.amateur.howmuchbackend.adapter.in.web.commoncode;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class CommonCodeAdminPageController {
    @GetMapping("/admin/common-codes")
    public String commonCodes() {
        return "admin/common-codes";
    }
}
