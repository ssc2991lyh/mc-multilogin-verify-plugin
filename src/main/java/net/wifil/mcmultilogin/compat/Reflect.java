package net.wifil.mcmultilogin.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 反射工具箱 —— 整个跨版本兼容方案的地基。
 *
 * <p>设计原则：<b>不信任任何名字</b>。Minecraft 的 NMS 类名、字段名会随版本
 * 甚至随混淆映射（Spigot 映射 / Mojang 映射）变化，所以定位内部对象时优先
 * 「按类型搜索」，其次才「按候选名列表搜索」。authlib（{@code com.mojang.authlib})
 * 不被混淆，类名从 1.7 到今天都稳定，因此它是我们唯一敢按名字找的东西。</p>
 *
 * <p>关于写入 {@code final} 字段，这里有两条经过实测确认的结论（JDK 25 实测）：</p>
 * <ol>
 *   <li>普通类的 <b>final 实例字段</b>，{@code setAccessible(true)} 之后
 *       {@code Field.set} <b>可以</b>写成功 —— 这是主路径。</li>
 *   <li><b>record 的字段无法写入</b>，即使用 {@code sun.misc.Unsafe} 也会失败。
 *       所以碰到 record 必须「重建整个实例」，不能改它的字段。
 *       {@link #setFieldValue} 对 record 字段会如实返回 {@code false}，
 *       由调用方（{@link ServerBridge}）走重建逻辑。</li>
 * </ol>
 *
 * <p>本类刻意只依赖 {@code java.*}，不引用任何服务器类型，因此在任何
 * Bukkit 衍生服务端（Bukkit/Spigot/Paper/Purpur/Folia…）上行为一致。</p>
 */
public final class Reflect {

    private Reflect() {
    }

    // =======================================================================
    // 异常
    // =======================================================================

    /** 反射操作失败时抛出，统一包装受检异常，避免调用方到处 try/catch。 */
    public static class ReflectException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ReflectException(String message) {
            super(message);
        }

        public ReflectException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // =======================================================================
    // 类查找
    // =======================================================================

    /**
     * 依次尝试候选全限定类名，返回第一个能加载的。
     *
     * <p>用于「同一个类在不同版本叫不同名字」的场景，例如聊天组件在
     * Mojang 映射下叫 {@code net.minecraft.network.chat.Component}，
     * 在老 Spigot 映射下叫 {@code net.minecraft.server.v1_16_R3.IChatBaseComponent}。</p>
     *
     * @return 命中的类；全部失败返回 {@code null}
     */
    public static Class<?> findClass(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (int i = 0; i < candidates.length; i++) {
            String name = candidates[i];
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            Class<?> found = loadClass(name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 判断某个类当前运行时是否存在（用于版本能力探测）。 */
    public static boolean isPresent(String className) {
        return loadClass(className) != null;
    }

    /**
     * 多 ClassLoader 尝试加载。插件的 ClassLoader 一般能看到服务器类，
     * 但在某些 fork / 插件加载器实现下需要退回上下文 ClassLoader。
     */
    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            // 继续尝试其它 ClassLoader
        }
        try {
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null) {
                return Class.forName(name, false, ctx);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            ClassLoader own = Reflect.class.getClassLoader();
            if (own != null) {
                return Class.forName(name, false, own);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    // =======================================================================
    // 字段
    // =======================================================================

    /**
     * 在 {@code owner} 及其所有父类中，按「字段声明类型可以装下 {@code type}」
     * 的规则查找字段，按「子类 -> 父类、类内按声明顺序」返回全部命中项。
     *
     * <p>这是本方案定位 NMS 内部对象的主力手段：字段名会混淆，但字段的
     * <b>类型</b>（尤其是 authlib 这种不混淆的类型）不会变。</p>
     *
     * @return 已 {@code setAccessible} 的字段列表，永不为 {@code null}
     */
    public static List<Field> fieldsOfType(Class<?> owner, Class<?> type) {
        List<Field> result = new ArrayList<Field>();
        if (owner == null || type == null) {
            return result;
        }
        Class<?> current = owner;
        while (current != null && current != Object.class) {
            Field[] declared;
            try {
                declared = current.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }
            for (int i = 0; i < declared.length; i++) {
                Field f = declared[i];
                if (type.isAssignableFrom(f.getType())) {
                    if (makeAccessible(f)) {
                        result.add(f);
                    }
                }
            }
            current = current.getSuperclass();
        }
        return result;
    }

    /**
     * 列出 {@code owner} 及其父类中所有<b>非静态</b>字段（已 setAccessible）。
     *
     * <p>供「运行时值扫描」使用：当目标对象藏在某个中间持有者里（例如现代
     * Minecraft 把 sessionService 放进 {@code Services} record），只能把每个
     * 字段的值取出来逐个探查。</p>
     */
    public static List<Field> instanceFields(Class<?> owner) {
        List<Field> result = new ArrayList<Field>();
        if (owner == null) {
            return result;
        }
        Class<?> current = owner;
        while (current != null && current != Object.class) {
            Field[] declared;
            try {
                declared = current.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }
            for (int i = 0; i < declared.length; i++) {
                Field f = declared[i];
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (makeAccessible(f)) {
                    result.add(f);
                }
            }
            current = current.getSuperclass();
        }
        return result;
    }

    /**
     * 在 {@code owner} 及其父类中按候选名字查找字段。
     *
     * @return 已 {@code setAccessible} 的字段；找不到返回 {@code null}
     */
    public static Field fieldByName(Class<?> owner, String... names) {
        if (owner == null || names == null) {
            return null;
        }
        for (int n = 0; n < names.length; n++) {
            String name = names[n];
            if (name == null) {
                continue;
            }
            Class<?> current = owner;
            while (current != null) {
                try {
                    Field f = current.getDeclaredField(name);
                    if (makeAccessible(f)) {
                        return f;
                    }
                } catch (NoSuchFieldException ignored) {
                    // 继续往父类找
                } catch (Throwable ignored) {
                    // 忽略安全限制等问题
                }
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /** 读字段值。{@code instance} 为 {@code null} 表示读静态字段。 */
    public static Object getFieldValue(Field field, Object instance) {
        if (field == null) {
            throw new ReflectException("字段为 null，无法读取");
        }
        try {
            makeAccessible(field);
            return field.get(instance);
        } catch (Throwable t) {
            throw new ReflectException("读取字段失败: " + field, t);
        }
    }

    /**
     * 写字段值，含 final 字段处理的级联降级：
     * <ol>
     *   <li>{@code Field.set} —— 普通类的 final 实例字段这一步就能成功（实测）；</li>
     *   <li>{@code sun.misc.Unsafe.putObject} —— 兜底，用于个别更严格的场景；</li>
     * </ol>
     *
     * <p><b>record 字段两条路都会失败</b>，此时如实返回 {@code false}，
     * 请调用方改用「重建实例」策略。</p>
     *
     * @return 是否写入成功
     */
    public static boolean setFieldValue(Field field, Object instance, Object value) {
        if (field == null) {
            return false;
        }
        makeAccessible(field);

        // 第 1 步：常规反射写入。
        try {
            field.set(instance, value);
            return true;
        } catch (Throwable ignored) {
            // 落到 Unsafe
        }

        // 第 2 步：Unsafe 兜底。
        return unsafePut(field, instance, value);
    }

    private static boolean makeAccessible(Field f) {
        try {
            f.setAccessible(true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // =======================================================================
    // 方法
    // =======================================================================

    /**
     * 按名字和参数个数查找方法（含父类链）。
     *
     * @param arity 期望参数个数；传负数表示不限
     * @return 已 {@code setAccessible} 的方法；找不到返回 {@code null}
     */
    public static Method methodByName(Class<?> owner, String name, int arity) {
        if (owner == null || name == null) {
            return null;
        }
        Class<?> current = owner;
        while (current != null) {
            Method[] declared;
            try {
                declared = current.getDeclaredMethods();
            } catch (Throwable t) {
                break;
            }
            for (int i = 0; i < declared.length; i++) {
                Method m = declared[i];
                if (!m.getName().equals(name)) {
                    continue;
                }
                if (arity >= 0 && m.getParameterTypes().length != arity) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                    // 即便设置失败也返回，public 方法照样能调
                }
                return m;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /** 按多个候选名字查找方法，返回第一个命中项。 */
    public static Method methodByNames(Class<?> owner, int arity, String... names) {
        if (names == null) {
            return null;
        }
        for (int i = 0; i < names.length; i++) {
            Method m = methodByName(owner, names[i], arity);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    /** 调用方法；目标方法自身抛出的异常会被拆包后作为 cause 包进 {@link ReflectException}。 */
    public static Object invoke(Method method, Object instance, Object... args) {
        if (method == null) {
            throw new ReflectException("方法为 null，无法调用");
        }
        try {
            return method.invoke(instance, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ReflectException("调用 " + method.getName() + " 时目标方法抛出异常", cause);
        } catch (Throwable t) {
            throw new ReflectException("调用方法失败: " + method, t);
        }
    }

    // =======================================================================
    // 构造器
    // =======================================================================

    /**
     * 按参数个数查找构造器。
     *
     * <p>对 record 来说，「参数个数等于组件个数」的那个构造器就是规范构造器，
     * 这是重建 record 实例的关键入口。</p>
     */
    public static Constructor<?> constructorByArity(Class<?> owner, int arity) {
        if (owner == null) {
            return null;
        }
        Constructor<?>[] all;
        try {
            all = owner.getDeclaredConstructors();
        } catch (Throwable t) {
            return null;
        }
        for (int i = 0; i < all.length; i++) {
            if (all[i].getParameterTypes().length == arity) {
                try {
                    all[i].setAccessible(true);
                } catch (Throwable ignored) {
                    // 忽略
                }
                return all[i];
            }
        }
        return null;
    }

    /** 按精确参数类型查找构造器。 */
    public static Constructor<?> constructorByTypes(Class<?> owner, Class<?>... types) {
        if (owner == null) {
            return null;
        }
        try {
            Constructor<?> c = owner.getDeclaredConstructor(types);
            try {
                c.setAccessible(true);
            } catch (Throwable ignored) {
                // 忽略
            }
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 用构造器创建实例。 */
    public static Object newInstance(Constructor<?> constructor, Object... args) {
        if (constructor == null) {
            throw new ReflectException("构造器为 null，无法实例化");
        }
        try {
            constructor.setAccessible(true);
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            return constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ReflectException("构造实例时抛出异常: " + constructor.getDeclaringClass().getName(), cause);
        } catch (Throwable t) {
            throw new ReflectException("构造实例失败: " + constructor.getDeclaringClass().getName(), t);
        }
    }

    // =======================================================================
    // 杂项
    // =======================================================================

    /**
     * 判断是否是 record 类型。
     *
     * <p>不能用 {@code Class#isRecord()}（Java 16+ 才有，本插件编译到 Java 8），
     * 所以退一步看父类是不是 {@code java.lang.Record}，效果等价。</p>
     */
    public static boolean isRecordClass(Class<?> c) {
        if (c == null) {
            return false;
        }
        Class<?> sup = c.getSuperclass();
        return sup != null && "java.lang.Record".equals(sup.getName());
    }

    /** {@code sun.misc.Unsafe} 兜底路径是否可用（仅用于诊断输出）。 */
    public static boolean unsafeAvailable() {
        return UNSAFE != null && UNSAFE_OBJECT_FIELD_OFFSET != null && UNSAFE_PUT_OBJECT != null;
    }

    // -----------------------------------------------------------------------
    // Unsafe 兜底实现
    // -----------------------------------------------------------------------

    private static final Object UNSAFE;
    private static final Method UNSAFE_OBJECT_FIELD_OFFSET;
    private static final Method UNSAFE_STATIC_FIELD_OFFSET;
    private static final Method UNSAFE_STATIC_FIELD_BASE;
    private static final Method UNSAFE_PUT_OBJECT;

    static {
        Object unsafe = null;
        Method objectOffset = null;
        Method staticOffset = null;
        Method staticBase = null;
        Method put = null;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = theUnsafe.get(null);

            objectOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
            staticOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
            staticBase = unsafeClass.getMethod("staticFieldBase", Field.class);
            // sun.misc.Unsafe 用 putObject；个别 JDK 内部实现改名为 putReference。
            try {
                put = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
            } catch (NoSuchMethodException e) {
                put = unsafeClass.getMethod("putReference", Object.class, long.class, Object.class);
            }
        } catch (Throwable ignored) {
            unsafe = null;
            objectOffset = null;
            staticOffset = null;
            staticBase = null;
            put = null;
        }
        UNSAFE = unsafe;
        UNSAFE_OBJECT_FIELD_OFFSET = objectOffset;
        UNSAFE_STATIC_FIELD_OFFSET = staticOffset;
        UNSAFE_STATIC_FIELD_BASE = staticBase;
        UNSAFE_PUT_OBJECT = put;
    }

    /**
     * 通过 Unsafe 直接按内存偏移写引用字段。
     *
     * <p>只在常规反射失败时调用。注意 record 字段在这里也会失败（JVM 层面
     * 对 record 的 final 字段有额外保护），这是预期行为。</p>
     */
    private static boolean unsafePut(Field field, Object instance, Object value) {
        if (!unsafeAvailable()) {
            return false;
        }
        try {
            boolean isStatic = Modifier.isStatic(field.getModifiers());
            Object base;
            long offset;
            if (isStatic) {
                if (UNSAFE_STATIC_FIELD_BASE == null || UNSAFE_STATIC_FIELD_OFFSET == null) {
                    return false;
                }
                base = UNSAFE_STATIC_FIELD_BASE.invoke(UNSAFE, field);
                offset = ((Long) UNSAFE_STATIC_FIELD_OFFSET.invoke(UNSAFE, field)).longValue();
            } else {
                if (instance == null) {
                    return false;
                }
                base = instance;
                offset = ((Long) UNSAFE_OBJECT_FIELD_OFFSET.invoke(UNSAFE, field)).longValue();
            }
            UNSAFE_PUT_OBJECT.invoke(UNSAFE, base, Long.valueOf(offset), value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
