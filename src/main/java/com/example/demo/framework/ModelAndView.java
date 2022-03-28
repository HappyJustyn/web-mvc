package com.example.demo.framework;

import java.util.HashMap;
import java.util.Map;

/**
 * 存储视图和模型数据
 *
 * @author Justyn
 * @version 1.0
 * @date 2022/3/26 12:22
 */
public class ModelAndView {
    Map<String, Object> model;
    String view;

    public ModelAndView(String view) {
        this.view = view;
        this.model = Map.of();
    }

    public ModelAndView(String view, String name, Object value) {
        this.view = view;
        this.model = new HashMap<>();
        this.model.put(name, value);
    }

    public ModelAndView(String view, Map<String, Object> model) {
        this.view = view;
        this.model = new HashMap<>(model);
    }
}
