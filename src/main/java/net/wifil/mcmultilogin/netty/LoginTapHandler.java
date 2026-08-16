package net.wifil.mcmultilogin.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;

import net.wifil.mcmultilogin.MultiLoginPlugin;
import net.wifil.mcmultilogin.compat.Reflect;
import net.wifil.mcmultilogin.compat.ServerBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * 对应原 Fabric 模组的 Mixin：
 * {@code ServerLoginPacketListenerImpl#disconnect(Component)} 的 HEAD 注入。
 *
 * <p>原模组在那个 Mixin 里判断：若当前登录玩家名在 {@code PENDING_ERRORS} 中存有详细错误消息，
 * 就取消原版断开、改用详细消息断开，从而把笼统的 {@code "Failed to verify username!"} 替换成
 * 认证服务返回的真实原因（如「该名称已被占用」「账号被封禁」）。</p>
 *
 * <p>Bukkit 插件没有 Mixin，而且这个断开发生在<b>登录阶段</b>，早于任何 Bukkit 事件
 * （连 {@code AsyncPlayerPreLoginEvent} 都还没触发），因此 Bukkit API 层面完全没有钩子。
 * 唯一可行的干预点就是 Netty 管道：拦截<b>出站</b>的「登录阶段断开包」，把其中的聊天组件
 * 换成我们的详细消息。本 handler 被 {@link LoginPipelineHook} 挂到每个新连接的管道尾部，
 * 同时兼任入站监听（从 Hello 包里提取玩家名）与出站拦截（替换断开包）。</p>
 *
 * <p>所有自定义逻辑都被 try/catch 兜住：一旦出错就原样转发，绝不因为我们的代码让连接崩掉。</p>
 */
public class LoginTapHandler extends ChannelDuplexHandler {

    /**
     * 挂在 Channel 上的属性键，用来在「入站 Hello 包」与「出站断开包」之间传递玩家名。
     * 用 {@link AttributeKey#valueOf(String)} 且只创建一次（静态字段），避免重复注册抛异常。
     */
    public static final AttributeKey<String> USERNAME = AttributeKey.valueOf("mcmultilogin:username");

    /** handler 在子连接管道里的名字（由 LoginPipelineHook 引用）。 */
    static final String TAP_NAME = "mcmultilogin-tap";

    /** 已成功替换断开消息的次数，供状态命令展示。 */
    private static final AtomicLong REPLACED = new AtomicLong();

    /** 玩家名合法性：1~16 位，仅允许字母数字及下划线点号。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.]{1,16}$");

    /**
     * 反射结果缓存：按包 Class 缓存「登录断开包」的单参构造器。
     * 登录路径会被每个连接反复触发，不能每次都做全量反射扫描。
     */
    private static final Map<Class<?>, Constructor<?>> DISCONNECT_CTOR = new ConcurrentHashMap<>();
    /** 标记：某包类找不到可接受的单参构造器（避免重复扫描）。 */
    private static final Map<Class<?>, Boolean> DISCONNECT_NO_CTOR = new ConcurrentHashMap<>();

    /** 按包 Class 缓存「Hello 包里用于取玩家名的字段名」，进一步避免重复扫描。 */
    private static final Map<Class<?>, String> USERNAME_FIELD = new ConcurrentHashMap<>();

    /** 异常只记一次日志的标记，避免每个包都刷屏。 */
    private static volatile boolean loggedError = false;

    /**
     * 入站读取：如果是登录阶段的 Hello 包（客户端发来的第一个登录包，内含玩家名），
     * 就把玩家名存进 Channel 属性，供后续出站断开时用。无论如何都继续向下传递。
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            String simple = msg.getClass().getSimpleName();
            // 包类名各版本不同：现代是 ServerboundHelloPacket，老 Spigot 映射是
            // PacketLoginInStart。不按全限定名匹配，只看 simpleName 是否含关键字。
            if (simple.contains("Hello") || simple.contains("LoginInStart") || simple.contains("LoginStart")) {
                String name = extractUsername(msg);
                if (name != null) {
                    ctx.channel().attr(USERNAME).set(name);
                    debug("登录阶段记录玩家名: " + name);
                }
            }
        } catch (Throwable t) {
            logError(t, "解析登录 Hello 包失败，忽略本次提取");
        }
        // 务必继续传递，否则登录握手会被我们卡住。
        super.channelRead(ctx, msg);
    }

    /**
     * 出站写入拦截：如果是登录阶段的断开包，尝试用 pendingErrors 里的详细消息重建并替换。
     * 不替换则原样向下传递。
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        String simple = msg.getClass().getSimpleName();
        // 判定「登录阶段」的断开包：
        //   现代 ClientboundLoginDisconnectPacket -> simpleName 含 "LoginDisconnect"
        //   老 Spigot 映射 PacketLoginOutDisconnect  -> simpleName 含 "LoginOutDisconnect"
        // 注意：play 阶段的 ClientboundDisconnectPacket 其 simpleName 为
        // "ClientboundDisconnectPacket"，不含 "LoginDisconnect"，按上面的规则天然被排除，
        // 不会被误伤。
        boolean isLoginDisconnect = simple.contains("LoginDisconnect")
                || simple.contains("LoginOutDisconnect");

        if (isLoginDisconnect) {
            try {
                Object newPkt = tryReplace(ctx, msg);
                if (newPkt != null) {
                    // 替换成功：发出新包。promise 继续沿用，写完成状态仍由下游负责。
                    ctx.write(newPkt, promise);
                    return;
                }
            } catch (Throwable t) {
                logError(t, "登录断开包替换失败，改发原包");
            }
        }
        // 未命中 / 出错：原样传递，绝不让连接卡死。
        super.write(ctx, msg, promise);
    }

    /**
     * 尝试用详细消息重建登录断开包。返回新包对象；若没有详细消息或无法重建则返回 null
     * （调用方会原样发送原包）。
     */
    private Object tryReplace(ChannelHandlerContext ctx, Object msg) {
        MultiLoginPlugin plugin = MultiLoginPlugin.instance();
        if (plugin == null) {
            return null;
        }
        Map<String, String> pending = plugin.pendingErrors();
        if (pending == null || pending.isEmpty()) {
            return null;
        }

        String detail;
        String name = ctx.channel().attr(USERNAME).get();
        if (name != null) {
            // 命中：取出并消费这条详细消息（一次性）。
            detail = pending.remove(name.toLowerCase(Locale.ROOT));
        } else {
            // 兜底：attribute 里没有玩家名时，若 pendingErrors 恰好只剩一条记录，
            // 保守地使用它。理由：登录握手是一对一的短生命周期连接，绝大多数情况下
            // 同一条连接只对应一条 pending 错误。若超过一条则放弃，避免张冠李戴。
            if (pending.size() == 1) {
                Iterator<Map.Entry<String, String>> it = pending.entrySet().iterator();
                if (it.hasNext()) {
                    Map.Entry<String, String> e = it.next();
                    detail = e.getValue();
                    it.remove();
                } else {
                    detail = null;
                }
            } else {
                detail = null;
            }
        }

        if (detail == null) {
            return null;
        }

        // 造出 NMS 聊天组件；失败（返回 null）则退回原包。
        Object component = ServerBridge.literalComponent(detail);
        if (component == null) {
            return null;
        }

        Object newPkt = rebuildDisconnect(msg, component);
        if (newPkt == null) {
            return null;
        }

        REPLACED.incrementAndGet();
        plugin.log().info("已替换登录断开消息 [" + (name != null ? name : "?") + "]: " + detail);
        return newPkt;
    }

    /**
     * 重建一个同类型的登录断开包，把里面的聊天组件换成我们的详细消息。
     *
     * <p>必须<b>重新构造</b>而不是改字段：现代这些断开包是 record，字段是 final，
     * 连 {@code sun.misc.Unsafe} 都写不进去，改字段在工程上不可行。所以找一个<b>单参数</b>
     * 构造器，其参数类型能接受组件对象（{@code param.isAssignableFrom(component.getClass())}），
     * 用新组件 new 一个新包。</p>
     *
     * <p>若找不到合适的单参构造器，返回 null，让调用方原样发原包（宁可显示笼统消息，
     * 也不能让连接卡死或抛异常）。</p>
     */
    private Object rebuildDisconnect(Object msg, Object component) {
        Class<?> pktClass = msg.getClass();

        // 已确认该包类找不到可接受的构造器，直接放弃。
        if (DISCONNECT_NO_CTOR.containsKey(pktClass)) {
            return null;
        }
        Constructor<?> ctor = DISCONNECT_CTOR.get(pktClass);

        if (ctor == null) {
            Class<?> compClass = component.getClass();
            Constructor<?> found = null;
            for (Constructor<?> c : pktClass.getDeclaredConstructors()) {
                Class<?>[] params = c.getParameterTypes();
                if (params.length == 1 && params[0].isAssignableFrom(compClass)) {
                    found = c;
                    break;
                }
            }
            if (found == null) {
                DISCONNECT_NO_CTOR.put(pktClass, Boolean.TRUE);
                return null;
            }
            found.setAccessible(true);
            DISCONNECT_CTOR.put(pktClass, found);
            ctor = found;
        }

        try {
            return Reflect.newInstance(ctor, component);
        } catch (Throwable t) {
            // 构造失败：退回原包。
            return null;
        }
    }

    /**
     * 从 Hello 包里提取玩家名。优先试 {@code name()} 方法（现代 record），
     * 退而遍历（含父类链）所有非 static 的 String 字段，取第一个满足合法性的值。
     */
    private String extractUsername(Object packet) {
        Class<?> clazz = packet.getClass();

        // 1) 已缓存字段名，直接读，省去再次扫描。
        String cachedField = USERNAME_FIELD.get(clazz);
        if (cachedField != null) {
            try {
                Field f = clazz.getDeclaredField(cachedField);
                f.setAccessible(true);
                Object v = f.get(packet);
                if (v instanceof String) {
                    String s = (String) v;
                    if (isValidName(s)) {
                        return s;
                    }
                }
            } catch (Throwable ignore) {
                // 缓存失效，下面重新扫描。
            }
        }

        // 2) 试 name() 方法（现代版本这个包是 record）。
        try {
            java.lang.reflect.Method m = Reflect.methodByName(clazz, "name", 0);
            if (m != null) {
                Object r = Reflect.invoke(m, packet);
                if (r instanceof String) {
                    String s = (String) r;
                    if (isValidName(s)) {
                        // 通过 name() 方法取到，无需缓存字段名；下次仍走方法分支。
                        return s;
                    }
                }
            }
        } catch (Throwable ignore) {
            // 没有 name() 方法或无权限，继续字段扫描。
        }

        // 3) 遍历字段链（含父类）找合适的 String 字段。
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            Field[] fields = cur.getDeclaredFields();
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (f.getType() != String.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(packet);
                    if (v instanceof String) {
                        String s = (String) v;
                        if (isValidName(s)) {
                            USERNAME_FIELD.put(clazz, f.getName());
                            return s;
                        }
                    }
                } catch (Throwable ignore) {
                    // 单个字段读取失败，跳过。
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /** 玩家名合法性校验。 */
    private static boolean isValidName(String s) {
        if (s == null) {
            return false;
        }
        if (s.length() < 1 || s.length() > 16) {
            return false;
        }
        return NAME_PATTERN.matcher(s).matches();
    }

    /**
     * 捕获异常：记录一次后向下传递，不可吞掉（否则会掩盖真实问题并破坏连接）。
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logError(cause, "LoginTapHandler 捕获到异常，已向下传递");
        try {
            super.exceptionCaught(ctx, cause);
        } catch (Throwable ignore) {
            // 下游默认行为若抛异常，忽略，绝不能让处理线程崩。
        }
    }

    /** 已成功替换的断开消息总数。 */
    public static long replacedCount() {
        return REPLACED.get();
    }

    /** 异常只记一次：用单个 boolean 标记，避免每个包都刷屏。 */
    private static void logError(Throwable t, String msg) {
        if (loggedError) {
            return;
        }
        loggedError = true;
        MultiLoginPlugin plugin = MultiLoginPlugin.instance();
        if (plugin != null) {
            plugin.log().log(Level.WARNING, msg, t);
        }
    }

    /** 仅调试日志（config debug=true 时才输出）。 */
    private static void debug(String msg) {
        MultiLoginPlugin plugin = MultiLoginPlugin.instance();
        if (plugin != null) {
            plugin.debug(msg);
        }
    }
}
