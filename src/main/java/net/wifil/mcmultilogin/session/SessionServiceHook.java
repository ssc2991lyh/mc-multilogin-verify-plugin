package net.wifil.mcmultilogin.session;

import net.wifil.mcmultilogin.MultiLoginPlugin;
import net.wifil.mcmultilogin.compat.ServerBridge;

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 会话服务钩子的安装/卸载入口，负责把服务器持有的 {@code MinecraftSessionService}
 * 换成由 {@link LoginSessionHandler} 驱动的 JDK 动态代理。
 *
 * <p>它等价于原 Fabric 模组里 {@code YggdrasilSessionServiceMixin} 的「注入」动作本身：
 * Mixin 靠混淆映射 + 字节码注入把逻辑插进
 * {@code YggdrasilMinecraftSessionService#hasJoinedServer}；Bukkit 侧没有这套设施，
 * 于是改用「接口代理 + 字段替换」来达到同样效果 —— 不改一个字节码、不需要 javaagent，
 * 卸载时把原对象写回即可 100% 还原原版行为。</p>
 */
public final class SessionServiceHook {

    /** 是否已安装。 */
    private static volatile boolean installed;

    /** 当前生效的代理对象。 */
    private static volatile Object proxyInstance;

    /** 被代理的原始实现类全名，用于状态展示。 */
    private static volatile String proxiedImplName;

    /** 代理实现的接口数量，用于状态展示与诊断。 */
    private static volatile int proxiedInterfaceCount;

    private SessionServiceHook() {
    }

    /**
     * 安装代理。幂等：若已安装（或服务器持有的实例本来就是我们的代理）直接返回 true。
     *
     * @return 安装成功返回 true；环境异常、代理创建失败或写回失败返回 false
     */
    public static synchronized boolean install() {
        if (installed && proxyInstance != null) {
            return true;
        }

        // 1. authlib 的 MinecraftSessionService 接口
        Class<?> serviceClass = ServerBridge.sessionServiceClass();
        if (serviceClass == null) {
            logger().warning("[MultiLogin] 找不到 MinecraftSessionService 接口，authlib 结构异常，放弃安装登录拦截。"
                    + " 环境：" + describeEnvironmentSafely());
            return false;
        }

        // 2. 服务器当前持有的实例
        Object original = ServerBridge.currentSessionService();
        if (original == null) {
            logger().warning("[MultiLogin] 取不到服务器当前的 MinecraftSessionService 实例，放弃安装登录拦截。"
                    + " 环境：" + describeEnvironmentSafely());
            return false;
        }

        // 3. 已经是我们的代理（例如插件重载后重复调用），视为已安装，避免代理套代理
        if (isOurProxy(original)) {
            installed = true;
            proxyInstance = original;
            if (proxiedImplName == null) {
                proxiedImplName = unwrapDelegateName(original);
            }
            logger().info("[MultiLogin] 会话服务已被本插件代理，跳过重复安装。");
            return true;
        }

        // 4. 收集要实现的接口
        Class<?>[] interfaces = collectInterfaces(original.getClass(), serviceClass);

        // 5. 创建代理（三级降级）
        LoginSessionHandler handler = new LoginSessionHandler(original);
        Object proxy = createProxy(original, serviceClass, interfaces, handler);
        if (proxy == null) {
            logger().warning("[MultiLogin] 无法为 " + original.getClass().getName()
                    + " 创建动态代理，放弃安装登录拦截。");
            return false;
        }

        // 6. 写回服务器
        if (!ServerBridge.replaceSessionService(proxy)) {
            logger().warning("[MultiLogin] 把代理写回服务器失败，放弃安装登录拦截。"
                    + " 环境：" + describeEnvironmentSafely());
            return false;
        }

        installed = true;
        proxyInstance = proxy;
        proxiedImplName = original.getClass().getName();
        proxiedInterfaceCount = proxy.getClass().getInterfaces().length;
        logger().info("[MultiLogin] 登录拦截已安装：代理实现类 " + proxiedImplName
                + "，共实现 " + proxiedInterfaceCount + " 个接口。");
        return true;
    }

    /**
     * 卸载代理，把原始会话服务写回服务器并清理静态状态。
     *
     * @return 还原成功返回 true；未安装时也返回 true
     */
    public static synchronized boolean uninstall() {
        if (!installed && proxyInstance == null) {
            return true;
        }
        boolean restored;
        try {
            restored = ServerBridge.restoreSessionService();
        } catch (Throwable t) {
            restored = false;
            logger().warning("[MultiLogin] 还原会话服务时出错：" + t);
        }

        // 无论成功与否都清状态，保证下次 install() 能重新尝试
        installed = false;
        proxyInstance = null;
        proxiedImplName = null;
        proxiedInterfaceCount = 0;

        if (restored) {
            logger().info("[MultiLogin] 登录拦截已卸载，原始会话服务已还原。");
        } else {
            logger().warning("[MultiLogin] 登录拦截卸载失败：原始会话服务可能未被还原，建议重启服务器。");
        }
        return restored;
    }

    /**
     * 查询当前是否已安装代理。
     *
     * @return 已安装返回 true
     */
    public static boolean isInstalled() {
        return installed && proxyInstance != null;
    }

    /**
     * 返回当前生效的代理对象，仅用于诊断。
     *
     * @return 代理对象，未安装时为 null
     */
    public static Object activeProxy() {
        return proxyInstance;
    }

    /**
     * 返回一行可直接展示给管理员的状态文本。
     *
     * @return 例如 {@code "已安装 (代理 YggdrasilMinecraftSessionService)"} 或 {@code "未安装"}
     */
    public static String statusLine() {
        if (!isInstalled()) {
            return "未安装";
        }
        return "已安装 (代理 " + simpleName(proxiedImplName) + ")";
    }

    // ------------------------------------------------------------------ 内部实现

    /**
     * 创建动态代理，按「原实现类 ClassLoader + 全部接口」→「接口所在 ClassLoader + 全部接口」
     * →「接口所在 ClassLoader + 仅 MinecraftSessionService」三级降级。
     *
     * <p>降级的必要性：{@link Proxy#newProxyInstance} 要求传入的每个接口都能被指定
     * ClassLoader 看见，某些服务端 fork（或 Paper 的插件类加载隔离）会让这一条不成立，
     * 此时只能退到更保守的组合。</p>
     */
    private static Object createProxy(Object original, Class<?> serviceClass,
                                      Class<?>[] interfaces, LoginSessionHandler handler) {
        ClassLoader implLoader = original.getClass().getClassLoader();
        ClassLoader serviceLoader = serviceClass.getClassLoader();

        // 一级：原实现类的 ClassLoader，它一定能看见自己实现的全部接口
        if (implLoader != null) {
            try {
                return Proxy.newProxyInstance(implLoader, interfaces, handler);
            } catch (IllegalArgumentException e) {
                logger().warning("[MultiLogin] 用实现类 ClassLoader 创建代理失败（" + e.getMessage()
                        + "），尝试改用 authlib 的 ClassLoader。");
            }
        }

        // 二级：MinecraftSessionService 所在的 ClassLoader
        if (serviceLoader != null) {
            try {
                return Proxy.newProxyInstance(serviceLoader, interfaces, handler);
            } catch (IllegalArgumentException e) {
                logger().warning("[MultiLogin] 用 authlib ClassLoader 创建全接口代理失败（" + e.getMessage()
                        + "），退化为只代理 MinecraftSessionService。");
            }
        }

        // 三级：只代理 MinecraftSessionService 这一个接口（功能足够，但可能在别处被强转时出错）
        ClassLoader lastLoader = (serviceLoader != null) ? serviceLoader : implLoader;
        try {
            return Proxy.newProxyInstance(lastLoader, new Class<?>[]{serviceClass}, handler);
        } catch (Throwable t) {
            logger().warning("[MultiLogin] 最小化代理也创建失败：" + t);
            return null;
        }
    }

    /**
     * 收集原实例类链上实现的<b>全部</b> public 接口（去重，{@code MinecraftSessionService} 置首）。
     *
     * <p>为什么不能只代理 {@code MinecraftSessionService} 一个接口：某些服务端 fork
     * （以及 Mojang 自己的 {@code YggdrasilMinecraftSessionService}）的实现类还实现了别的接口，
     * 服务器其它位置可能把这个对象强转成那些接口（或调用它们的方法）。如果代理没实现，
     * 就会在毫不相关的地方抛 {@code ClassCastException}。所以逐层向上把接口全收进来，
     * 让代理在类型上与原对象尽可能等价。</p>
     *
     * <p>只收 public 接口的原因：JDK 要求所有非 public 接口必须与代理类同包，
     * 混入不同包的非 public 接口会让 {@code Proxy.newProxyInstance} 直接抛
     * {@code IllegalArgumentException}。</p>
     */
    private static Class<?>[] collectInterfaces(Class<?> implClass, Class<?> serviceClass) {
        Set<Class<?>> collected = new LinkedHashSet<Class<?>>();
        // 置首，确保无论如何都代理了目标接口
        collected.add(serviceClass);
        Class<?> current = implClass;
        while (current != null && current != Object.class) {
            addInterfaces(current.getInterfaces(), collected);
            current = current.getSuperclass();
        }
        return collected.toArray(new Class<?>[collected.size()]);
    }

    /** 递归收集接口及其父接口。 */
    private static void addInterfaces(Class<?>[] interfaces, Set<Class<?>> out) {
        if (interfaces == null) {
            return;
        }
        for (int i = 0; i < interfaces.length; i++) {
            Class<?> itf = interfaces[i];
            if (itf == null || !Modifier.isPublic(itf.getModifiers())) {
                continue;
            }
            if (out.add(itf)) {
                addInterfaces(itf.getInterfaces(), out);
            }
        }
    }

    /** 判断一个对象是否已经是本插件安装的代理。 */
    private static boolean isOurProxy(Object candidate) {
        try {
            if (candidate == null || !Proxy.isProxyClass(candidate.getClass())) {
                return false;
            }
            return Proxy.getInvocationHandler(candidate) instanceof LoginSessionHandler;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从我们的代理里取出被包装的原始实现类名，仅用于展示。 */
    private static String unwrapDelegateName(Object ourProxy) {
        try {
            Object handler = Proxy.getInvocationHandler(ourProxy);
            if (handler instanceof LoginSessionHandler) {
                return ((LoginSessionHandler) handler).getDelegate().getClass().getName();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return "未知实现";
    }

    private static String simpleName(String className) {
        if (className == null || className.isEmpty()) {
            return "未知实现";
        }
        int idx = className.lastIndexOf('.');
        return (idx >= 0 && idx < className.length() - 1) ? className.substring(idx + 1) : className;
    }

    private static String describeEnvironmentSafely() {
        try {
            String desc = ServerBridge.describeEnvironment();
            return (desc != null) ? desc : "未知";
        } catch (Throwable t) {
            return "未知";
        }
    }

    /** 取插件 Logger，插件未就绪时退回独立 Logger，保证日志调用不会 NPE。 */
    private static Logger logger() {
        try {
            MultiLoginPlugin plugin = MultiLoginPlugin.instance();
            if (plugin != null) {
                Logger pluginLogger = plugin.log();
                if (pluginLogger != null) {
                    return pluginLogger;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return Logger.getLogger("MultiLogin");
    }
}
