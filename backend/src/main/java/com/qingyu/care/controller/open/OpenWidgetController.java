package com.qingyu.care.controller.open;

import com.qingyu.care.config.OpenApiAuthInterceptor;
import com.qingyu.care.dto.open.OpenWidgetConfigResponse;
import com.qingyu.care.entity.Application;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Widget 公开配置端点。认证由 OpenApiAuthInterceptor 完成（无密钥则走 Origin 白名单），
 * 拦截器已将 Application 存入 request attribute，此处直接取用、不再查库。
 *
 * @author ruan
 */
@RestController
@RequestMapping("/open/v1/widget")
public class OpenWidgetController {

    /**
     * GET /open/v1/widget/config —— 返回渲染挂件所需的品牌信息。
     */
    @GetMapping("/config")
    public OpenWidgetConfigResponse config(HttpServletRequest request) {
        Application app = (Application) request.getAttribute(OpenApiAuthInterceptor.ATTR_APP);
        return OpenWidgetConfigResponse.of(app);
    }
}
