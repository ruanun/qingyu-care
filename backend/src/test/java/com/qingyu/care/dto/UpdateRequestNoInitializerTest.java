package com.qingyu.care.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护本次修复唯一的铁律——「{@code *UpdateRequest} 一律不写字段初始化器」——但改成扫描式而非
 * 点状覆盖。
 *
 * <p>此前的守护（{@code PartialUpdateSemanticsTest}）只挑了每个已知 DTO 里的一两个字段断言
 * 具体值。这条铁律真正的失效模式是「新增一个 DTO」或「给现有 DTO 加一个带初始化器的新字段」——
 * 两种情况点状测试都不会注意到，直到线上局部 PUT 再把某个字段静默打回默认值才会被发现。
 *
 * <p>改成反射扫描：找出 {@code com.qingyu.care.dto} 包下所有类名以 {@code UpdateRequest} 结尾
 * 的类，用无参构造实例化后断言<b>每一个字段</b>都是 {@code null}——这是判断「有没有初始化器」
 * 最直接的方式，不依赖记住哪个字段曾经出过事。
 *
 * <p>扫描范式与 {@code config/AsyncExecutorRoutingTest} 同源（同样用
 * {@code ClassPathScanningCandidateComponentProvider}），区别是 DTO 没有 Spring 构造型注解，
 * 故关掉默认过滤器改用类名正则做 include filter。
 *
 * @author ruan
 */
class UpdateRequestNoInitializerTest {

    private static final String DTO_PACKAGE = "com.qingyu.care.dto";

    @Test
    @DisplayName("dto 包下所有 *UpdateRequest 类：无参构造后每个字段都是 null")
    void every_update_request_field_defaults_to_null() {
        List<Class<?>> classes = scanUpdateRequestClasses();

        // 扫描逻辑本身坏掉时（比如类名规则改了、包名改了），下面的循环会直接空转、
        // 所有字段断言变成「什么都没检查」的假绿——CardActionProtocolSyncTest 已经踩过这个坑，
        // 这里同样先把「扫到的类数非空」显式钉成一条断言。
        assertThat(classes)
                .as("%s 包下一个 *UpdateRequest 类都没扫到——扫描逻辑本身坏了，"
                        + "下面的字段断言会全部空转", DTO_PACKAGE)
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Class<?> clazz : classes) {
            Object instance;
            try {
                instance = clazz.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                violations.add(clazz.getSimpleName() + " 无法用无参构造实例化: " + e);
                continue;
            }
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object value;
                try {
                    value = field.get(instance);
                } catch (IllegalAccessException e) {
                    violations.add(clazz.getSimpleName() + "." + field.getName() + " 读取失败: " + e);
                    continue;
                }
                if (value != null) {
                    violations.add(clazz.getSimpleName() + "." + field.getName()
                            + " 无参构造后不是 null（实际值: " + value + "）");
                }
            }
        }

        assertThat(violations)
                .as("以下 *UpdateRequest 字段带了初始化器，违反「本类所有字段一律不写初始化器」这条"
                        + "铁律——请求体里没给的字段会被当成「传了这个默认值」，局部 PUT 时静默把"
                        + "库里的值覆盖掉")
                .isEmpty();
    }

    /** 反射扫描 {@link #DTO_PACKAGE} 包下所有类名以 {@code UpdateRequest} 结尾的类。 */
    private static List<Class<?>> scanUpdateRequestClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*UpdateRequest")));

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(DTO_PACKAGE)) {
            String className = definition.getBeanClassName();
            try {
                classes.add(Class.forName(className));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("扫描到的类无法加载: " + className, e);
            }
        }
        return classes;
    }
}
