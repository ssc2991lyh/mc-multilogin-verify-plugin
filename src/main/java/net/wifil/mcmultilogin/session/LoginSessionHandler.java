package net.wifil.mcmultilogin.session;

import net.wifil.mcmultilogin.MultiLoginPlugin;
import net.wifil.mcmultilogin.api.ErrorResponse;
import net.wifil.mcmultilogin.api.HasJoinedClient;
import net.wifil.mcmultilogin.api.LoginApiClient;
import net.wifil.mcmultilogin.compat.AuthlibBridge;
import net.wifil.mcmultilogin.config.PluginConfig;
import net.wifil.mcmultilogin.tracking.PlayerNameTracker;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Locale;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 登录会话拦截处理器，对应原 Fabric 模组中的
 * {@code net.wifil.mcmultilogin.mixin.YggdrasilSessionServiceMixin}
 * （注入 {@code YggdrasilMinecraftSessionService#hasJoinedServer} 的 HEAD）。
 *
 * <h2>为什么用 JDK 动态代理，而不是改字节码</h2>
 * <ul>
 *   <li>Bukkit 侧没有 Mixin/混淆映射基础设施，运行期改字节码（Instrumentation、ASM 重定义）
 *       需要 javaagent 或额外类加载器 hack，且极易被各服务端 fork 的类结构差异搞崩。</li>
 *   <li>{@code MinecraftSessionService} 本身就是一个<b>接口</b>，服务器只是持有它的一个实现实例，
 *       因此可以直接用 {@link java.lang.reflect.Proxy} 包一层，再把代理写回服务器字段 ——
 *       零字节码改动、零 agent，卸载时把原对象写回即可完全还原。</li>
 *   <li>更关键的一点：{@code hasJoinedServer} 的方法签名在 1.8 ~ 26.1+ 之间反复变化
 *       （参数有无 {@code InetAddress}、返回 {@code GameProfile} 还是 {@code ProfileResult}）。
 *       动态代理天然是按 {@link Method} 对象工作的，不依赖编译期签名，
 *       所以同一份代码可以通吃所有版本；这也是不写死签名、全部走
 *       {@link AuthlibBridge} 取参/造返回值的原因。</li>
 * </ul>
 *
 * <h2>失败策略：fail-open（宁可放过，不可误踢）</h2>
 * 除「认证服务明确拒绝」（HTTP 403 / 204）这两种情况会返回 {@code null} 让原版踢人之外，
 * 其余任何异常（插件未就绪、参数解析不出、网络错误、状态码意外、我们自己代码抛异常）
 * 都会转发给原始实现，即退回原版正版验证，行为与没装本插件时一致。
 */
public class LoginSessionHandler implements InvocationHandler {

    /** 认证服务没给出可读原因时的兜底提示文案。 */
    private static final String FALLBACK_ERROR_MESSAGE = "Login rejected by authentication service.";

    /** {@code pendingErrors} 的容量上限；登录失败原因是短命数据，超限直接清空防止无限堆积。 */
    private static final int MAX_PENDING_ERRORS = 512;

    /**
     * 内部哨兵：表示「本次拦截决定退回原版验证」。
     * <p>之所以用哨兵而不是在业务逻辑里直接调用原方法，是为了让「转发原方法」这一步
     * 发生在我们自己的 try/catch 之外 —— 否则原方法自己抛出的异常会被我们的
     * {@code catch (Throwable)} 吃掉，并触发第二次转发（对认证服务重复请求）。</p>
     */
    private static final Object FALL_BACK = new Object();

    private static final AtomicLong INTERCEPTED = new AtomicLong();
    private static final AtomicLong REJECTED = new AtomicLong();
    private static final AtomicLong RENAMED = new AtomicLong();
    private static final AtomicLong FALLBACK = new AtomicLong();

    /** 被代理的原始 {@code MinecraftSessionService} 实例，恒非 null。 */
    private final Object delegate;

    /**
     * 构造处理器。
     *
     * @param delegate 原始的 {@code MinecraftSessionService} 实例，不允许为 null
     * @throws IllegalArgumentException 当 {@code delegate} 为 null 时抛出
     */
    public LoginSessionHandler(Object delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate（原始 MinecraftSessionService）不能为 null");
        }
        this.delegate = delegate;
    }

    /**
     * 返回被代理的原始会话服务实例，便于诊断与卸载时还原。
     *
     * @return 原始 {@code MinecraftSessionService} 实例，恒非 null
     */
    public Object getDelegate() {
        return delegate;
    }

    /**
     * 代理入口：只接管 {@code hasJoinedServer}，其余方法（含 Object 的
     * {@code equals}/{@code hashCode}/{@code toString}）一律按原语义处理或转发。
     *
     * @param proxy  代理对象自身
     * @param method 被调用的方法
     * @param args   调用参数，可能为 null（无参方法）
     * @return 方法返回值；{@code null} 表示验证失败（原版会据此踢人）
     * @throws Throwable 转发原方法时对方抛出的原始异常
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // ---- 1. Object 自带的三个方法：代理会把它们也送进来，必须自己给出合理实现 ----
        String name = method.getName();
        Class<?>[] paramTypes = method.getParameterTypes();
        if ("equals".equals(name) && paramTypes.length == 1 && paramTypes[0] == Object.class) {
            Object other = (args != null && args.length > 0) ? args[0] : null;
            // 同一个代理，或者直接拿原对象来比，都算相等
            return Boolean.valueOf(other == proxy || other == delegate);
        }
        if ("hashCode".equals(name) && paramTypes.length == 0) {
            return Integer.valueOf(delegate.hashCode());
        }
        if ("toString".equals(name) && paramTypes.length == 0) {
            return "MultiLoginSessionServiceProxy[" + delegate.getClass().getName() + "]";
        }

        // ---- 2. 判断是否是 hasJoinedServer（跨版本签名差异由 AuthlibBridge 吸收） ----
        boolean hasJoinedServer;
        try {
            hasJoinedServer = AuthlibBridge.isHasJoinedServer(method);
        } catch (Throwable t) {
            // 连判断都失败，说明 Bridge 侧出了意外；宁可什么都不做
            hasJoinedServer = false;
        }
        if (!hasJoinedServer) {
            return forward(method, args);
        }

        // ---- 3. 接管登录校验 ----
        INTERCEPTED.incrementAndGet();
        Object decision;
        try {
            decision = decide(method, args);
        } catch (Throwable t) {
            // 兜住任何意外，绝不让异常逃逸到服务器登录线程
            logUnexpected(t);
            decision = FALL_BACK;
        }
        if (decision == FALL_BACK) {
            FALLBACK.incrementAndGet();
            // 注意：这一步在 try/catch 之外，原方法自己的异常按原样向上传播
            return forward(method, args);
        }
        return decision;
    }

    // ------------------------------------------------------------------ 核心业务

    /**
     * 复刻原 Mixin 的判定流程，只做决策、绝不调用原方法。
     *
     * @return {@link #FALL_BACK} 表示退回原版验证；{@code null} 表示验证失败（踢人）；
     *         其它对象表示直接采用的登录结果（GameProfile / ProfileResult）
     */
    private Object decide(Method method, Object[] args) {
        long startNanos = System.nanoTime();

        MultiLoginPlugin plugin = MultiLoginPlugin.instance();
        if (plugin == null) {
            return FALL_BACK;
        }
        PluginConfig config = plugin.config();
        // 统一客户端：优先内嵌自包含验证（合并后的 MC-MultiLogin-service），
        // 未配置则回退外部 LoginApiClient；两者都为 null 时等于插件没开工。
        HasJoinedClient client = plugin.resolveClient();
        if (config == null || client == null) {
            // 未配置任何验证客户端（外部 api-url 与内嵌验证都未启用）：退回原版验证
            return FALL_BACK;
        }

        String username = AuthlibBridge.usernameOf(method, args);
        String serverId = AuthlibBridge.serverIdOf(method, args);
        // 1.8 ~ 1.13 的 hasJoinedServer 没有 InetAddress 参数，这里允许为 null
        InetAddress address = AuthlibBridge.addressOf(method, args);
        if (username == null || serverId == null) {
            plugin.debug("[MultiLogin] 无法从 hasJoinedServer 参数解析 username/serverId，退回原版验证");
            return FALL_BACK;
        }

        LoginApiClient.ApiResult result;
        try {
            // detail=true：让服务端连带返回可读的失败原因
            result = client.hasJoined(username, serverId, address, true);
        } catch (Throwable t) {
            // 说明：这里统一用 Throwable + instanceof 分发，而不是 catch (IOException | InterruptedException)。
            // 原因是 HasJoinedClient 的 throws 声明可能随实现调整，写死 catch 某个受检异常
            // 会在「该异常在 try 块中从不抛出」时直接编译失败；语义完全等价。
            return logApiFailure(plugin, username, t);
        }
        if (result == null) {
            plugin.debug("[MultiLogin] 验证客户端返回空结果（玩家 '" + username + "'），退回原版验证");
            return FALL_BACK;
        }

        int status = result.statusCode();

        // ---- HTTP 200：认证服务认可，直接顶替原版结果 ----
        if (result.isSuccess()) {
            traceDebug(plugin, username, status, null, startNanos);
            Object joinResult = AuthlibBridge.buildJoinResult(method, result.body());
            if (joinResult != null) {
                return joinResult;
            }
            // 造不出当前版本要求的返回类型：退回原版而不是返回 null，避免把已通过认证的玩家误踢
            logger(plugin).warning("[MultiLogin] 无法为 '" + username
                    + "' 构造登录结果对象（authlib 结构不兼容？），退回原版验证");
            return FALL_BACK;
        }

        // ---- HTTP 403：明确拒绝，可能可以自动改名救回 ----
        if (result.isForbidden()) {
            ErrorResponse error = LoginApiClient.parseError(result.body());
            String cause = (error != null) ? error.getCause() : null;
            traceDebug(plugin, username, status, cause, startNanos);

            String errorMsg = (error != null && !isEmpty(error.getErrorMessage()))
                    ? error.getErrorMessage()
                    : FALLBACK_ERROR_MESSAGE;

            if (error != null && error.isDuplicateName() && config.autoRename()
                    && !isEmpty(error.getAvailableId())) {
                // 自动改名救回只在外部 LoginApiClient 路径有意义（内嵌 VerifyService 走自己的 204 处理）。
                if (client instanceof LoginApiClient) {
                    String availableId = error.getAvailableId();
                    logger(plugin).info("[MultiLogin] 玩家名 '" + username + "' 重复，尝试以 '"
                            + availableId + "' 重新校验。");
                    Object renamed = tryRename(plugin, (LoginApiClient) client, method,
                            username, serverId, address, availableId);
                    if (renamed != null) {
                        return renamed;
                    }
                    // 重试失败 -> 落到下面的常规报错流程
                }
            }

            reject(plugin, username, errorMsg, cause);
            return null;
        }

        // ---- HTTP 204：认证服务表示「不认识这个会话」，同样算验证失败 ----
        if (status == 204) {
            traceDebug(plugin, username, status, null, startNanos);
            REJECTED.incrementAndGet();
            // 无可读原因，不写 pendingErrors，客户端看到原版的笼统提示
            return null;
        }

        // ---- 其它状态码：服务端异常，fail-open ----
        traceDebug(plugin, username, status, null, startNanos);
        logger(plugin).warning("[MultiLogin] 认证服务返回非预期状态码 " + status
                + "（玩家 '" + username + "'），退回原版验证");
        return FALL_BACK;
    }

    /**
     * 用服务端建议的可用名字重试一次。
     *
     * @return 成功时返回可直接使用的登录结果；失败返回 {@code null}（调用方继续走报错流程）
     */
    private Object tryRename(MultiLoginPlugin plugin, LoginApiClient api, Method method,
                             String username, String serverId, InetAddress address, String availableId) {
        try {
            // 注意：重试时 detail=false，与原 Mixin 行为保持一致
            LoginApiClient.ApiResult retry = api.hasJoined(availableId, serverId, address, false);
            if (retry != null && retry.isSuccess()) {
                Object built = AuthlibBridge.buildJoinResult(method, retry.body());
                if (built != null) {
                    PlayerNameTracker tracker = plugin.tracker();
                    if (tracker != null) {
                        // 持久化 原名 -> 新名，供其它模块查询
                        tracker.track(username, availableId);
                    }
                    RENAMED.incrementAndGet();
                    logger(plugin).info("[MultiLogin] 自动改名：" + username + " -> " + availableId);
                    return built;
                }
            }
            logger(plugin).info("[MultiLogin] 以 '" + availableId + "' 重试也失败（状态码 "
                    + (retry != null ? String.valueOf(retry.statusCode()) : "无响应") + "），展示原始错误。");
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger(plugin).warning("[MultiLogin] 为 '" + username + "' 重试改名时出错："
                    + describe(t) + "，展示原始错误。");
        }
        return null;
    }

    /**
     * 记录拒绝原因并计数；写入 {@code pendingErrors} 后由 Netty 出站拦截模块替换踢出消息。
     */
    private void reject(MultiLoginPlugin plugin, String username, String errorMsg, String cause) {
        ConcurrentMap<String, String> pending = plugin.pendingErrors();
        if (pending != null) {
            if (pending.size() > MAX_PENDING_ERRORS) {
                // 正常情况下条目会被取走；超限说明有堆积（例如从没被消费），直接丢弃这批短命数据
                pending.clear();
                logger(plugin).warning("[MultiLogin] 待发送的登录失败原因超过 "
                        + MAX_PENDING_ERRORS + " 条，已清空以防内存堆积。");
            }
            // key 统一小写，避免客户端大小写差异导致取不到
            pending.put(username.toLowerCase(Locale.ROOT), errorMsg);
        }
        REJECTED.incrementAndGet();
        logger(plugin).info("[MultiLogin] 拒绝 '" + username + "' 登录（cause="
                + (cause != null ? cause : "未提供") + "）：" + errorMsg);
    }

    /**
     * 处理调用认证服务时的异常，统一 fail-open。
     *
     * @return 恒为 {@link #FALL_BACK}
     */
    private Object logApiFailure(MultiLoginPlugin plugin, String username, Throwable t) {
        if (t instanceof InterruptedException) {
            // 恢复中断标志，避免吞掉上层的中断语义
            Thread.currentThread().interrupt();
            logger(plugin).warning("[MultiLogin] 校验 '" + username
                    + "' 时线程被中断，退回原版验证。");
        } else if (t instanceof IOException) {
            logger(plugin).warning("[MultiLogin] 调用认证服务失败（玩家 '" + username + "'）："
                    + describe(t) + "，退回原版验证。");
        } else {
            logger(plugin).warning("[MultiLogin] 调用认证服务时发生意外错误（玩家 '" + username + "'）："
                    + describe(t) + "，退回原版验证。");
        }
        return FALL_BACK;
    }

    // ------------------------------------------------------------------ 工具方法

    /**
     * 把方法调用转发给原始实现。
     * <p>必须把 {@link InvocationTargetException} 拆包后重抛它的 cause，否则调用方看到的
     * 异常类型会变成反射包装类型，服务器的 catch 分支会失效（例如原版对
     * {@code AuthenticationUnavailableException} 的特殊处理）。</p>
     */
    private Object forward(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            throw (cause != null) ? cause : ite;
        } catch (IllegalAccessException iae) {
            // 少数 fork 的接口/方法不是 public，补一次可访问性后重试
            method.setAccessible(true);
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException ite2) {
                Throwable cause = ite2.getCause();
                throw (cause != null) ? cause : ite2;
            }
        }
    }

    /** debug 模式下输出一行拦截明细：玩家名、状态码、cause、耗时。 */
    private void traceDebug(MultiLoginPlugin plugin, String username, int status,
                            String cause, long startNanos) {
        try {
            PluginConfig config = plugin.config();
            if (config == null || !config.debug()) {
                return;
            }
            long costMs = (System.nanoTime() - startNanos) / 1000000L;
            plugin.debug("[MultiLogin] 拦截 hasJoinedServer：玩家=" + username
                    + " 状态码=" + status
                    + " cause=" + (cause != null ? cause : "-")
                    + " 耗时=" + costMs + "ms");
        } catch (Throwable ignored) {
            // 日志失败绝不能影响登录
        }
    }

    /** 我们自己代码出的意外，只记日志，不影响登录流程。 */
    private void logUnexpected(Throwable t) {
        try {
            MultiLoginPlugin plugin = MultiLoginPlugin.instance();
            logger(plugin).warning("[MultiLogin] 登录拦截逻辑发生意外错误："
                    + describe(t) + "，退回原版验证。");
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 取插件 Logger，插件未就绪时退回一个独立 Logger，保证日志调用不会 NPE。 */
    private static Logger logger(MultiLoginPlugin plugin) {
        if (plugin != null) {
            Logger pluginLogger = plugin.log();
            if (pluginLogger != null) {
                return pluginLogger;
            }
        }
        return Logger.getLogger("MultiLogin");
    }

    /** 异常的简短描述，避免整条堆栈刷屏。 */
    private static String describe(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    /** null / 空白串判断（Java 8 无 String#isBlank）。 */
    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ------------------------------------------------------------------ 统计计数

    /**
     * 累计拦截到的 {@code hasJoinedServer} 次数。
     *
     * @return 拦截次数
     */
    public static long interceptedCount() {
        return INTERCEPTED.get();
    }

    /**
     * 累计判定为验证失败（返回 null，玩家被踢）的次数。
     *
     * @return 拒绝次数
     */
    public static long rejectedCount() {
        return REJECTED.get();
    }

    /**
     * 累计自动改名成功的次数。
     *
     * @return 改名次数
     */
    public static long renamedCount() {
        return RENAMED.get();
    }

    /**
     * 累计退回原版验证（fail-open）的次数。
     *
     * @return 回退次数
     */
    public static long fallbackCount() {
        return FALLBACK.get();
    }
}
