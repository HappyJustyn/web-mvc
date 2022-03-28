package com.example.demo.framework;

import com.mitchellbosecke.pebble.PebbleEngine;
import com.mitchellbosecke.pebble.loader.ServletLoader;
import com.mitchellbosecke.pebble.template.PebbleTemplate;

import javax.servlet.ServletContext;
import java.io.IOException;
import java.io.Writer;
import java.util.Map;

/**
 * @author Justyn
 * @version 1.0
 * @date 2022/3/26 17:16
 */
public class ViewEngine {

    private final PebbleEngine engine;

    public ViewEngine(ServletContext servletContext) {
        // 定义一个ServletLoader用于加载模板
        ServletLoader loader = new ServletLoader(servletContext);
        // 模板编码
        loader.setCharset("UTF-8");
        // 模板前缀，这里默认模板必须放在`/WEB-INF/templates`目录
        loader.setPrefix("/WEB-INF/templates");
        // 模板后缀
        loader.setSuffix("");
        // 创建Pebble实例
        this.engine = new PebbleEngine.Builder()
                // 默认打开HTML字符转义，防止XSS攻击
                .autoEscaping(true)
                // 禁用缓存使得每次修改模板可以立刻看到效果
                .cacheActive(false)
                .loader(loader).build();
    }

    public void render(ModelAndView mv, Writer writer) throws IOException {
        String view = mv.view;
        Map<String, Object> model = mv.model;
        // 根据view找到模板文件
        PebbleTemplate template = this.engine.getTemplate(view);
        // 渲染并写入Writer
        template.evaluate(writer, model);
    }
}
