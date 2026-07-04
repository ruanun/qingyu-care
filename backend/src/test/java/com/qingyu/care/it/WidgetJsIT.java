package com.qingyu.care.it;

import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 公开挂件脚本：免登录可取(200) + Content-Type 为 JavaScript + 非空脚本体。 */
class WidgetJsIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void widgetJs免登录返回200且ContentType为javascript() throws Exception {
        mockMvc.perform(get("/widget.js").param("appKey", "any"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")))
                .andExpect(content().string(containsString("function")));
    }
}
