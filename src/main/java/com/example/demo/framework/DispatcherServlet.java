package com.example.demo.framework;

import com.example.demo.controller.IndexController;
import com.example.demo.controller.UserController;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 接收处理所有请求
 *
 * @author Justyn
 * @version 1.0
 * @date 2022/3/26 12:12
 */
@WebServlet(urlPatterns = "/")
public class DispatcherServlet extends HttpServlet {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 存储请求路径到具体方法的映射
     */
    private Map<String, GetDispatcher> getMappings = new HashMap<>();
    private Map<String, PostDispatcher> postMappings = new HashMap<>();

    private List<Class<?>> controllers = List.of(IndexController.class, UserController.class);

    private ViewEngine viewEngine;

    private static final Set<Class<?>> supportedGetParameterTypes = Set.of(int.class, long.class, boolean.class,
            String.class, HttpServletRequest.class, HttpServletResponse.class, HttpSession.class);
    private static final Set<Class<?>> supportedPostParameterTypes = Set.of(HttpServletRequest.class,
            HttpServletResponse.class, HttpSession.class);

    /**
     * 当Servlet容器创建当前Servlet实例后，会自动调用init(ServletConfig)方法
     */
    @Override
    public void init() throws ServletException {
        logger.info("init {}...", getClass().getSimpleName());
        ObjectMapper objectMapper = new ObjectMapper();
        // 解析JSON的时候，遇到bean中没有与之对应的属性，不报异常（JsonMappingException）
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 依次处理每个Controller
        for (Class<?> controllerClass : controllers) {
            try {
                Object controllerInstance = controllerClass.getConstructor().newInstance();
                // 依次处理每个Method
                for (Method method : controllerClass.getMethods()) {
                    if (method.getAnnotation(GetMapping.class) != null) {
                        // 处理@GetMapping
                        if (method.getReturnType() != ModelAndView.class && method.getReturnType() != void.class) {
                            throw new UnsupportedOperationException(
                                    "Unsupported return type: " + method.getReturnType() + " for method: " + method);
                        }
                        for (Class<?> parameterClass : method.getParameterTypes()) {
                            if (!supportedGetParameterTypes.contains(parameterClass)) {
                                throw new UnsupportedOperationException(
                                        "Unsupported parameter type: " + parameterClass + " for method: " + method);
                            }
                        }
                        String[] parameterNames = Arrays.stream(method.getParameters()).map(p -> p.getName())
                                .toArray(String[]::new);
                        String path = method.getAnnotation(GetMapping.class).value();
                        logger.info("Found GET: {} => {}", path, method);
                        this.getMappings.put(path, new GetDispatcher(controllerInstance, method, parameterNames,
                                method.getParameterTypes()));
                    } else if (method.getAnnotation(PostMapping.class) != null) {
                        // 处理@PostMapping
                        if (method.getReturnType() != ModelAndView.class && method.getReturnType() != void.class) {
                            throw new UnsupportedOperationException(
                                    "Unsupported return type: " + method.getReturnType() + " for method: " + method);
                        }
                        // Controller方法参数只能有一个JavaBean
                        Class<?> requestBodyClass = null;
                        for (Class<?> parameterClass : method.getParameterTypes()) {
                            if (!supportedPostParameterTypes.contains(parameterClass)) {
                                if (requestBodyClass == null) {
                                    requestBodyClass = parameterClass;
                                } else {
                                    throw new UnsupportedOperationException("Unsupported duplicate request body type: "
                                            + parameterClass + " for method: " + method);
                                }
                            }
                        }
                        String path = method.getAnnotation(PostMapping.class).value();
                        logger.info("Found POST: {} => {}", path, method);
                        this.postMappings.put(path, new PostDispatcher(controllerInstance, method,
                                method.getParameterTypes(), objectMapper));
                    }
                }

            } catch (ReflectiveOperationException e) {
                throw new ServletException(e);
            }
        }
        // 创建ViewEngine
        this.viewEngine = new ViewEngine(getServletContext());
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        process(req, resp, this.getMappings);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        process(req, resp, this.postMappings);
    }

    private void process(HttpServletRequest req, HttpServletResponse resp,
                         Map<String, ? extends AbstractDispatcher> dispatcherMap) throws ServletException, IOException {
        resp.setContentType("text/html");
        resp.setCharacterEncoding("UTF-8");
        String path = req.getRequestURI().substring(req.getContextPath().length());
        // 根据路径查找Dispatcher
        AbstractDispatcher dispatcher = dispatcherMap.get(path);
        if (dispatcher == null) {
            resp.sendError(404);
            return;
        }
        ModelAndView mv = null;
        try {
            mv = dispatcher.invoke(req, resp);
        } catch (ReflectiveOperationException e) {
            throw new ServletException(e);
        }
        // 允许返回null
        if (mv == null) {
            return;
        }
        // 允许返回`redirect:`开头的view表示重定向
        if (mv.view.startsWith("redirect:")) {
            resp.sendRedirect(mv.view.substring(9));
            return;
        }
        // 将模板引擎渲染的内容写入响应
        PrintWriter pw = resp.getWriter();
        this.viewEngine.render(mv, pw);
        pw.flush();
    }

}

abstract class AbstractDispatcher {
    public abstract ModelAndView invoke(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ReflectiveOperationException;
}

class GetDispatcher extends AbstractDispatcher {
    /**
     * Controller实例
     */
    Object instance;
    /**
     * Controller方法
     */
    Method method;
    /**
     * 方法参数名
     */
    String[] parameterNames;
    /**
     * 方法参数类型
     */
    Class<?>[] parameterClasses;

    public GetDispatcher(Object instance, Method method, String[] parameterNames, Class<?>[] parameterClasses) {
        this.instance = instance;
        this.method = method;
        this.parameterNames = parameterNames;
        this.parameterClasses = parameterClasses;
    }

    /**
     * 如果是GET请求，我们希望MVC框架能直接把URL参数按方法参数对应起来然后传入
     * 如果Controller的方法在处理请求时需要访问HttpServletRequest、HttpServletResponse、HttpSession这些实例时，只要方法参数有定义，就可以自动传入
     */
    @Override
    public ModelAndView invoke(HttpServletRequest request, HttpServletResponse response) throws InvocationTargetException, IllegalAccessException {
        Object[] arguments = new Object[parameterClasses.length];
        for (int i = 0; i < parameterClasses.length; i++) {
            String parameterName = parameterNames[i];
            Class<?> parameterClass = parameterClasses[i];
            if (parameterClass == HttpServletRequest.class) {
                arguments[i] = request;
            } else if (parameterClass == HttpServletResponse.class) {
                arguments[i] = response;
            } else if (parameterClass == HttpSession.class) {
                arguments[i] = request.getSession();
            } else if (parameterClass == int.class) {
                arguments[i] = Integer.valueOf(getOrDefault(request, parameterName, "0"));
            } else if (parameterClass == long.class) {
                arguments[i] = Long.valueOf(getOrDefault(request, parameterName, "0"));
            } else if (parameterClass == boolean.class) {
                arguments[i] = Boolean.valueOf(getOrDefault(request, parameterName, "false"));
            } else if (parameterClass == String.class) {
                arguments[i] = getOrDefault(request, parameterName, "");
            } else {
                throw new RuntimeException("Missing handler for type:" + parameterClass);
            }
        }
        return (ModelAndView) this.method.invoke(this.instance, arguments);
    }

    private String getOrDefault(HttpServletRequest request, String parameterName, String defaultValue) {
        String result = request.getParameter(parameterName);
        return result == null ? defaultValue : result;
    }
}

class PostDispatcher extends AbstractDispatcher {
    /**
     * Controller实例
     */
    Object instance;
    /**
     * Controller方法
     */
    Method method;
    /**
     * 方法参数类型
     */
    Class<?>[] parameterClasses;
    /**
     * JSON映射
     */
    ObjectMapper objectMapper;

    public PostDispatcher(Object instance, Method method, Class<?>[] parameterClasses, ObjectMapper objectMapper) {
        this.instance = instance;
        this.method = method;
        this.parameterClasses = parameterClasses;
        this.objectMapper = objectMapper;
    }

    /**
     * 如果是POST请求，我们希望MVC框架能直接把Post参数变成一个JavaBean后通过方法参数传入
     */
    @Override
    public ModelAndView invoke(HttpServletRequest request, HttpServletResponse response) throws IOException, InvocationTargetException, IllegalAccessException {
        Object[] arguments = new Object[parameterClasses.length];
        for (int i = 0; i < parameterClasses.length; i++) {
            Class<?> parameterClass = parameterClasses[i];
            if (parameterClass == HttpServletRequest.class) {
                arguments[i] = request;
            } else if (parameterClass == HttpServletResponse.class) {
                arguments[i] = response;
            } else if (parameterClass == HttpSession.class) {
                arguments[i] = request.getSession();
            } else {
                // 读取JSON并解析为JavaBean
                BufferedReader reader = request.getReader();
                arguments[i] = this.objectMapper.readValue(reader, parameterClass);
            }
        }
        return (ModelAndView) this.method.invoke(this.instance, arguments);
    }


}
