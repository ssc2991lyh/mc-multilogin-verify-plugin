package net.wifil.mcmultilogin.compat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import org.bukkit.Bukkit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 服务端内部结构适配层：所有「碰 NMS / CraftBukkit 内部」的动作都收在这里。
 *
 * <p>本类<b>不 import 任何 net.minecraft.* 或 org.bukkit.craftbukkit.* 类型</b>，
 * 全部走反射。这样产物就不绑定某一个服务端版本的类名与混淆映射，
 * 一个 JAR 才可能同时在 1.8 的 Spigot 和 26.1 的 Purpur 上加载。
 * （只 import 了 netty 和 org.bukkit.Bukkit —— 前者是服务端自带且包名从未变过，
 * 后者是稳定公开 API。）</p>
 *
 * <p>实测依据（Paper 26.1.2 用 javap 确认）：
 * <pre>
 * MinecraftServer:
 *   protected final net.minecraft.server.Services services;   // 普通类的 final 字段，可反射写
 * Services（是 record！6 个实例字段）:
 *   sessionService / servicesKeySet / profileRepository / nameToIdCache / profileResolver / paper
 *   构造器：5 参（无 paper）与 6 参（含 paper）
 * ServerConnectionListener:
 *   private final List&lt;io.netty.channel.ChannelFuture&gt; channels;   // 注意是 ChannelFuture
 *   private final List&lt;net.minecraft.network.Connection&gt; connections;
 * CraftChatMessage#fromStringOrNull(String) -&gt; Component
 * </pre></p>
 */
public final class ServerBridge {

    private static final String SESSION_SERVICE_CLASS = "com.mojang.authlib.minecraft.MinecraftSessionService";

    private static final ConcurrentMap<String, Object> CACHE = new ConcurrentHashMap<String, Object>();
    private static final ConcurrentMap<String, Boolean> WARNED = new ConcurrentHashMap<String, Boolean>();

    /** 会话服务所在的「槽位」，安装/卸载都靠它。 */
    private static volatile Slot slot;

    /** 安装前的原始会话服务，用于卸载时还原。 */
    private static volatile Object originalService;

    private ServerBridge() {
    }

    // ------------------------------------------------------------------
    // 基础定位
    // ------------------------------------------------------------------

    /**
     * CraftBukkit 的包名。
     *
     * <p>1.8~1.20.4 形如 {@code org.bukkit.craftbukkit.v1_20_R1}（带版本段），
     * 1.20.5 之后 Paper 去掉了版本段，变成 {@code org.bukkit.craftbukkit}。
     * 拼 CraftBukkit 内部类名时两种都要能应付，所以这里直接从实际的
     * CraftServer 实例上取包名，而不是自己猜。</p>
     */
    public static String craftBukkitPackage() {
        Object cached = CACHE.get("cbPackage");
        if (cached instanceof String) {
            return (String) cached;
        }
        try {
            String pkg = Bukkit.getServer().getClass().getPackage().getName();
            CACHE.put("cbPackage", pkg);
            return pkg;
        } catch (Throwable t) {
            return "org.bukkit.craftbukkit";
        }
    }

    /**
     * 取 MinecraftServer 实例（NMS 的服务器主对象）。
     *
     * <p>通过 {@code CraftServer#getServer()} 拿 —— 这个方法从 1.8 至今一直存在、
     * 名字也从没变过，是进入 NMS 世界最稳的一道门。</p>
     */
    public static Object minecraftServer() {
        Object cached = CACHE.get("nmsServer");
        if (cached != null) {
            return cached;
        }
        try {
            Object craftServer = Bukkit.getServer();
            if (craftServer == null) {
                return null;
            }
            Method getServer = Reflect.methodByName(craftServer.getClass(), "getServer", 0);
            if (getServer == null) {
                warnOnce("getServer", "CraftServer 上找不到 getServer()，无法进入 NMS。");
                return null;
            }
            Object nms = Reflect.invoke(getServer, craftServer);
            if (nms != null) {
                CACHE.put("nmsServer", nms);
            }
            return nms;
        } catch (Throwable t) {
            warnOnce("nmsServer", "获取 MinecraftServer 失败: " + t);
            return null;
        }
    }

    /** authlib 的 {@code MinecraftSessionService} 接口。 */
    public static Class<?> sessionServiceClass() {
        Object cached = CACHE.get("serviceClass");
        if (cached instanceof Class) {
            return (Class<?>) cached;
        }
        Class<?> c = Reflect.findClass(SESSION_SERVICE_CLASS);
        if (c != null) {
            CACHE.put("serviceClass", c);
        } else {
            warnOnce("serviceClass", "找不到 " + SESSION_SERVICE_CLASS + "，authlib 结构异常。");
        }
        return c;
    }

    // ------------------------------------------------------------------
    // 会话服务的读取 / 替换 / 还原
    // ------------------------------------------------------------------

    /** 服务器当前持有的会话服务实例。 */
    public static Object currentSessionService() {
        Slot s = resolveSlot();
        if (s == null) {
            return null;
        }
        try {
            return Reflect.getFieldValue(s.serviceField, s.holder);
        } catch (Throwable t) {
            warnOnce("readService", "读取当前会话服务失败: " + t);
            return null;
        }
    }

    /**
     * 把服务器持有的会话服务换成我们的代理。
     *
     * <p>两种写入路径，取决于持有者是不是 record：
     * <ul>
     *   <li><b>普通类持有</b>（老版本 MinecraftServer 直接持有该字段）：
     *       {@code Field.setAccessible(true)} + {@code Field.set} 就能改，即使字段是 final
     *       —— 实测在 JDK 25 上对普通类的 final 实例字段依然有效。</li>
     *   <li><b>record 持有</b>（现代的 {@code Services} record）：record 的 final 字段
     *       <b>写不进去</b>，实测连 {@code sun.misc.Unsafe} 也失败。所以只能
     *       「读出全部字段值 → 换掉 sessionService 那一格 → 用全字段构造器 new 一个新 record
     *       → 把新 record 写回它的持有者（MinecraftServer.services 是普通 final 字段，可写）」。</li>
     * </ul></p>
     */
    public static boolean replaceSessionService(Object proxy) {
        if (proxy == null) {
            return false;
        }
        Slot s = resolveSlot();
        if (s == null) {
            return false;
        }
        try {
            Object current = Reflect.getFieldValue(s.serviceField, s.holder);
            if (originalService == null) {
                // 只在第一次安装时记录原始对象，避免重复安装后把代理当成"原始值"。
                originalService = current;
            }
            return writeService(s, proxy);
        } catch (Throwable t) {
            warnOnce("replaceService", "替换会话服务失败: " + t);
            return false;
        }
    }

    /** 把安装前的原始会话服务写回去。 */
    public static boolean restoreSessionService() {
        Slot s = slot;
        if (s == null || originalService == null) {
            // 没安装过，视为已还原。
            return true;
        }
        try {
            boolean ok = writeService(s, originalService);
            if (ok) {
                originalService = null;
            }
            return ok;
        } catch (Throwable t) {
            warnOnce("restoreService", "还原会话服务失败: " + t);
            return false;
        }
    }

    /** 实际写入动作：普通类直接写字段，record 则整体重建。 */
    private static boolean writeService(Slot s, Object value) {
        if (!s.holderIsRecord) {
            boolean ok = Reflect.setFieldValue(s.serviceField, s.holder, value);
            if (!ok) {
                warnOnce("writeField", "写入 " + s.holder.getClass().getName()
                        + "." + s.serviceField.getName() + " 失败。");
            }
            return ok;
        }
        return rebuildRecordHolder(s, value);
    }

    /**
     * 重建持有会话服务的 record，并把新实例写回它的父对象。
     *
     * <p>关键点：构造器要按<b>实例字段个数</b>来选。实测 Paper 的 {@code Services} record
     * 有 6 个实例字段（比 Spigot 多一个 Paper 专有的 {@code paper} 组件），
     * 同时提供了 5 参和 6 参两个构造器。如果图省事挑 5 参的，Paper 专有组件就丢了，
     * 会在后续用到它时炸。按「参数个数 == 实例字段个数」匹配，5 字段的 Spigot 和
     * 6 字段的 Paper 都能自动选对。</p>
     *
     * <p>另外要小心 record 类里可能有 static 字段（{@code Services} 里就有
     * {@code static final String USERID_CACHE_FILE}），必须排除，否则字段数算多了、
     * 匹配不到构造器。{@link Reflect#instanceFields} 已经做了这个过滤。</p>
     */
    private static boolean rebuildRecordHolder(Slot s, Object value) {
        try {
            Class<?> recordClass = s.holder.getClass();
            List<Field> fields = Reflect.instanceFields(recordClass);
            if (fields.isEmpty()) {
                warnOnce("recordFields", "record " + recordClass.getName() + " 没有实例字段，无法重建。");
                return false;
            }

            Object[] values = new Object[fields.size()];
            int targetIndex = -1;
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                values[i] = Reflect.getFieldValue(f, s.holder);
                if (f.getName().equals(s.serviceField.getName())) {
                    targetIndex = i;
                }
            }
            if (targetIndex < 0) {
                // 名字没对上时退回按类型匹配（混淆过的字段名可能不一致）。
                Class<?> serviceClass = sessionServiceClass();
                for (int i = 0; i < fields.size(); i++) {
                    if (serviceClass != null && serviceClass.isAssignableFrom(fields.get(i).getType())) {
                        targetIndex = i;
                        break;
                    }
                }
            }
            if (targetIndex < 0) {
                warnOnce("recordTarget", "在 " + recordClass.getName() + " 里定位不到会话服务字段。");
                return false;
            }
            values[targetIndex] = value;

            Constructor<?> ctor = Reflect.constructorByArity(recordClass, fields.size());
            if (ctor == null) {
                warnOnce("recordCtor", "找不到 " + recordClass.getName()
                        + " 的 " + fields.size() + " 参构造器，无法重建 record。");
                return false;
            }

            Object newRecord = Reflect.newInstance(ctor, values);
            if (newRecord == null) {
                return false;
            }

            // 把新 record 写回父对象（MinecraftServer.services 是普通类的 final 字段，可写）。
            if (!Reflect.setFieldValue(s.holderField, s.parent, newRecord)) {
                warnOnce("recordWriteBack", "把重建后的 " + recordClass.getSimpleName()
                        + " 写回 " + s.parent.getClass().getSimpleName() + " 失败。");
                return false;
            }

            // 槽位里的 holder 已经换成新实例，后续读写都要指向它。
            s.holder = newRecord;
            return true;
        } catch (Throwable t) {
            warnOnce("recordRebuild", "重建 record 失败: " + t);
            return false;
        }
    }

    /**
     * 定位会话服务所在的槽位。两种布局都支持：
     * <ol>
     *   <li>MinecraftServer 上直接有一个 {@code MinecraftSessionService} 字段（老版本）；</li>
     *   <li>MinecraftServer 持有一个容器对象（现代的 {@code Services} record），
     *       会话服务在容器里（需要两级定位）。</li>
     * </ol>
     * 全程按<b>字段类型</b>查找而不是按字段名 —— 老版本 Spigot 的字段名是混淆后的
     * 单字母（如 {@code Y}、{@code aa}），按名字根本找不到。
     */
    private static Slot resolveSlot() {
        Slot cached = slot;
        if (cached != null) {
            return cached;
        }
        Object nms = minecraftServer();
        Class<?> serviceClass = sessionServiceClass();
        if (nms == null || serviceClass == null) {
            return null;
        }

        // 布局一：MinecraftServer 直接持有。
        List<Field> direct = Reflect.fieldsOfType(nms.getClass(), serviceClass);
        if (!direct.isEmpty()) {
            Slot s = new Slot();
            s.holder = nms;
            s.serviceField = direct.get(0);
            s.holderIsRecord = Reflect.isRecordClass(nms.getClass()); // MinecraftServer 不是 record，恒 false
            slot = s;
            return s;
        }

        // 布局二：在 MinecraftServer 的各个字段里找「内部含有会话服务字段」的容器。
        List<Field> candidates = Reflect.instanceFields(nms.getClass());
        for (int i = 0; i < candidates.size(); i++) {
            Field holderField = candidates.get(i);
            Object holder;
            try {
                holder = Reflect.getFieldValue(holderField, nms);
            } catch (Throwable ignore) {
                continue;
            }
            if (holder == null) {
                continue;
            }
            // 跳过基本类型包装、字符串、集合这些明显不可能的容器，减少无谓扫描。
            if (holder instanceof CharSequence || holder instanceof Number
                    || holder instanceof Boolean || holder instanceof java.util.Collection
                    || holder instanceof java.util.Map) {
                continue;
            }
            List<Field> inner = Reflect.fieldsOfType(holder.getClass(), serviceClass);
            if (inner.isEmpty()) {
                continue;
            }
            Slot s = new Slot();
            s.parent = nms;
            s.holderField = holderField;
            s.holder = holder;
            s.serviceField = inner.get(0);
            s.holderIsRecord = Reflect.isRecordClass(holder.getClass());
            slot = s;
            return s;
        }

        warnOnce("resolveSlot", "在 MinecraftServer 上定位不到 MinecraftSessionService，"
                + "服务端结构可能不受支持。");
        return null;
    }

    // ------------------------------------------------------------------
    // Netty 监听通道
    // ------------------------------------------------------------------

    /**
     * 取服务器所有监听通道（用于给它们的 pipeline 注入登录拦截）。
     *
     * <p><b>返回的元素一定是 {@link Channel}，不是 {@link ChannelFuture}。</b>
     * 实测 {@code ServerConnectionListener.channels} 的声明类型是
     * {@code List<ChannelFuture>}，如果原样返回，调用方的
     * {@code instanceof Channel} 判断会全部落空、注入静默失效且日志上看不出异常。
     * 所以在这里就把 future 解包成 channel。</p>
     */
    public static List<Object> serverChannels() {
        List<Object> out = new ArrayList<Object>();
        Object connection = serverConnection();
        if (connection == null) {
            return out;
        }
        try {
            List<Field> listFields = Reflect.fieldsOfType(connection.getClass(), List.class);
            for (int i = 0; i < listFields.size(); i++) {
                Object raw = Reflect.getFieldValue(listFields.get(i), connection);
                if (!(raw instanceof List)) {
                    continue;
                }
                // channels 是活列表，服务器线程可能同时改它 —— 先拷快照再遍历，
                // 否则可能撞上 ConcurrentModificationException。
                List<?> snapshot;
                try {
                    snapshot = new ArrayList<Object>((List<?>) raw);
                } catch (Throwable ignore) {
                    continue;
                }
                for (int j = 0; j < snapshot.size(); j++) {
                    Object item = snapshot.get(j);
                    if (item instanceof Channel) {
                        out.add(item);
                    } else if (item instanceof ChannelFuture) {
                        // 关键的一步解包。
                        Channel ch = ((ChannelFuture) item).channel();
                        if (ch != null) {
                            out.add(ch);
                        }
                    }
                    // 元素是 Connection（另一个 List 字段）时什么都不做，自然被过滤掉。
                }
            }
        } catch (Throwable t) {
            warnOnce("serverChannels", "获取监听通道失败: " + t);
        }
        return out;
    }

    /** 取 ServerConnectionListener（网络监听器）。优先用 getter，其次按字段类型名匹配。 */
    private static Object serverConnection() {
        Object nms = minecraftServer();
        if (nms == null) {
            return null;
        }
        // 现代 Paper 有 getConnection()，老版本叫 getServerConnection()/ao() 之类。
        Method getter = Reflect.methodByNames(nms.getClass(), 0,
                "getConnection", "getServerConnection", "connection");
        if (getter != null) {
            try {
                Object conn = Reflect.invoke(getter, nms);
                if (conn != null && looksLikeConnectionListener(conn.getClass())) {
                    return conn;
                }
            } catch (Throwable ignore) {
                // getter 不通就往下走字段扫描。
            }
        }
        // 字段扫描：按类型名匹配（混淆版本下 getter 名字不可靠，但类名通常仍含关键字；
        // 若类名也被混淆，则退化为「字段里含 List 且元素是 ChannelFuture」的结构判断）。
        List<Field> fields = Reflect.instanceFields(nms.getClass());
        for (int i = 0; i < fields.size(); i++) {
            Object v;
            try {
                v = Reflect.getFieldValue(fields.get(i), nms);
            } catch (Throwable ignore) {
                continue;
            }
            if (v == null) {
                continue;
            }
            if (looksLikeConnectionListener(v.getClass()) || hasChannelFutureList(v)) {
                return v;
            }
        }
        warnOnce("serverConnection", "定位不到网络监听器（ServerConnectionListener）。");
        return null;
    }

    private static boolean looksLikeConnectionListener(Class<?> c) {
        String n = c.getSimpleName();
        return n.contains("ServerConnection") || n.contains("ServerConnectionListener");
    }

    /** 结构判断：某对象是否含有「元素为 ChannelFuture / Channel」的 List 字段。 */
    private static boolean hasChannelFutureList(Object candidate) {
        try {
            List<Field> listFields = Reflect.fieldsOfType(candidate.getClass(), List.class);
            for (int i = 0; i < listFields.size(); i++) {
                Object raw = Reflect.getFieldValue(listFields.get(i), candidate);
                if (!(raw instanceof List)) {
                    continue;
                }
                List<?> list = (List<?>) raw;
                if (list.isEmpty()) {
                    continue;
                }
                Object first = list.get(0);
                if (first instanceof ChannelFuture || first instanceof Channel) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
            // 判断失败就当"不像"，交给其它候选。
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 聊天组件
    // ------------------------------------------------------------------

    /**
     * 把纯文本做成 NMS 的聊天组件（用于重建登录断开包）。
     *
     * <p>按三条路依次尝试，覆盖从 1.8 到最新的写法：
     * <ol>
     *   <li>{@code CraftChatMessage.fromStringOrNull(String)} —— 现代 Paper/Spigot 都有，
     *       实测 26.1.2 存在；</li>
     *   <li>{@code CraftChatMessage.fromString(String)} —— 老版本，返回 Component 数组，取首个；</li>
     *   <li>{@code Component.literal(String)} / 老映射的
     *       {@code ChatComponentText} 构造器 —— 直接走 NMS。</li>
     * </ol>
     * 全失败返回 null，调用方会放弃替换、原样发送原版断开包（功能降级但不影响连接）。</p>
     */
    public static Object literalComponent(String text) {
        if (text == null) {
            return null;
        }

        // 路线 1 / 2：CraftChatMessage
        Class<?> ccm = (Class<?>) CACHE.get("craftChatMessage");
        if (ccm == null) {
            String pkg = craftBukkitPackage();
            ccm = Reflect.findClass(
                    pkg + ".util.CraftChatMessage",
                    "org.bukkit.craftbukkit.util.CraftChatMessage");
            if (ccm != null) {
                CACHE.put("craftChatMessage", ccm);
            }
        }
        if (ccm != null) {
            try {
                Method fromStringOrNull = Reflect.methodByName(ccm, "fromStringOrNull", 1);
                if (fromStringOrNull != null) {
                    Object c = Reflect.invoke(fromStringOrNull, null, text);
                    if (c != null) {
                        return c;
                    }
                }
                Method fromString = Reflect.methodByName(ccm, "fromString", 1);
                if (fromString != null) {
                    Object arr = Reflect.invoke(fromString, null, text);
                    if (arr instanceof Object[] && ((Object[]) arr).length > 0) {
                        return ((Object[]) arr)[0];
                    }
                    if (arr != null && !(arr instanceof Object[])) {
                        return arr;
                    }
                }
            } catch (Throwable ignore) {
                // 继续走 NMS 兜底。
            }
        }

        // 路线 3：直接用 NMS 的 Component
        try {
            Class<?> component = Reflect.findClass(
                    "net.minecraft.network.chat.Component",
                    "net.minecraft.network.chat.IChatBaseComponent");
            if (component != null) {
                Method literal = Reflect.methodByNames(component, 1, "literal", "b");
                if (literal != null) {
                    Object c = Reflect.invoke(literal, null, text);
                    if (c != null) {
                        return c;
                    }
                }
            }
            Class<?> textComponent = Reflect.findClass(
                    "net.minecraft.network.chat.TextComponent",
                    "net.minecraft.network.chat.ChatComponentText",
                    "net.minecraft.server.v1_8_R3.ChatComponentText");
            if (textComponent != null) {
                Constructor<?> ctor = Reflect.constructorByTypes(textComponent, String.class);
                if (ctor != null) {
                    return Reflect.newInstance(ctor, text);
                }
            }
        } catch (Throwable t) {
            warnOnce("literalComponent", "构造聊天组件失败: " + t);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 环境描述
    // ------------------------------------------------------------------

    /**
     * 一行环境描述，写在启动日志和 {@code /multilogin status} 里。
     * 跨版本排错时这行信息价值极高 —— 出问题时一眼能看出是什么服务端、什么 authlib 形态。
     */
    public static String describeEnvironment() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(Bukkit.getName()).append(' ').append(Bukkit.getVersion());
            sb.append(" | API ").append(Bukkit.getBukkitVersion());
        } catch (Throwable t) {
            sb.append("未知服务端");
        }
        try {
            sb.append(" | CB 包 ").append(craftBukkitPackage());
        } catch (Throwable ignore) {
            // 忽略
        }
        try {
            sb.append(" | ").append(AuthlibBridge.describeAuthlib());
        } catch (Throwable ignore) {
            // 忽略
        }
        try {
            Slot s = resolveSlot();
            if (s != null) {
                sb.append(" | 会话服务位于 ")
                        .append(s.holder.getClass().getSimpleName())
                        .append(s.holderIsRecord ? "(record，需整体重建)" : "(普通类，可直接改字段)");
            }
        } catch (Throwable ignore) {
            // 忽略
        }
        sb.append(" | Java ").append(System.getProperty("java.version", "?"));
        return sb.toString();
    }

    /** 仅测试/诊断用：清掉缓存，让下次调用重新定位。 */
    static void resetCache() {
        CACHE.clear();
        WARNED.clear();
        slot = null;
        originalService = null;
    }

    private static void warnOnce(String key, String msg) {
        if (WARNED.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        net.wifil.mcmultilogin.MultiLoginPlugin plugin = net.wifil.mcmultilogin.MultiLoginPlugin.instance();
        if (plugin != null) {
            plugin.log().warning("[MultiLogin] " + msg);
        }
    }

    /**
     * 会话服务所在的位置。
     *
     * <p>为什么要记这么多字段：现代布局下会话服务藏在一个 record 里，
     * 而 record 不可改，必须知道「这个 record 是被谁的哪个字段持有的」
     * 才能把重建后的新实例写回去。老布局（MinecraftServer 直接持有）时
     * {@code parent}/{@code holderField} 为 null，走简单路径。</p>
     */
    private static final class Slot {
        /** 直接持有会话服务字段的对象（可能是 MinecraftServer 或 Services record）。 */
        Object holder;
        /** holder 上类型为 MinecraftSessionService 的字段。 */
        Field serviceField;
        /** holder 的持有者，仅当 holder 是 record 时需要。 */
        Object parent;
        /** parent 上指向 holder 的字段，仅当 holder 是 record 时需要。 */
        Field holderField;
        /** holder 是否为 record（决定写入策略）。 */
        boolean holderIsRecord;
    }
}
