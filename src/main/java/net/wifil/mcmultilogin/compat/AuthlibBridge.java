package net.wifil.mcmultilogin.compat;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * authlib 适配层：把「不同 authlib 版本之间的形态差异」全部吸收在这里。
 *
 * <p>本类<b>刻意不 import 任何 com.mojang.authlib.* 类型</b>，全部通过反射访问。
 * 这是「单个 JAR 兼容全版本」的核心手段 —— authlib 从 1.x 到 7.x 之间发生过
 * 结构性变化（普通类改成 record、hasJoinedServer 返回值从 GameProfile 换成 ProfileResult、
 * 参数表增删 InetAddress），如果编译期就绑定某个版本的类型，产物只能在那一个大版本上跑。</p>
 *
 * <p>实测依据（Paper 26.1.2 自带 authlib 7.0.63，用 javap 逐个确认）：
 * <pre>
 * MinecraftSessionService#hasJoinedServer(String, String, InetAddress) -> yggdrasil.ProfileResult
 * ProfileResult  是 record，有 1 参构造 (GameProfile)
 * GameProfile    是 record，字段 (UUID id, String name, PropertyMap properties)
 *                构造器有 (UUID,String,PropertyMap) 和 (UUID,String) 两个
 * PropertyMap    只有 PropertyMap(Multimap) 一个构造器（老版本才有无参构造）
 * Property       是 record，构造器 (String,String) 与 (String,String,String)
 * </pre></p>
 */
public final class AuthlibBridge {

    /** authlib 里几个关键类的候选全名（不同版本包路径不同，逐个试）。 */
    private static final String[] GAME_PROFILE_NAMES = {
            "com.mojang.authlib.GameProfile"
    };
    private static final String[] PROPERTY_NAMES = {
            "com.mojang.authlib.properties.Property"
    };
    private static final String[] PROPERTY_MAP_NAMES = {
            "com.mojang.authlib.properties.PropertyMap"
    };

    /** 反射结果缓存：登录路径高频调用，绝不能每次都重新扫描。 */
    private static final ConcurrentMap<String, Object> CACHE = new ConcurrentHashMap<String, Object>();

    /** 同一类问题只警告一次，避免刷日志。 */
    private static final ConcurrentMap<String, Boolean> WARNED = new ConcurrentHashMap<String, Boolean>();

    private AuthlibBridge() {
    }

    // ------------------------------------------------------------------
    // hasJoinedServer 的识别与参数提取
    // ------------------------------------------------------------------

    /**
     * 判断一个被代理调用的方法是否是 {@code hasJoinedServer}。
     *
     * <p>只按「方法名 + 参数个数」判断，不比对参数类型：
     * 1.8~1.13 的签名是 {@code hasJoinedServer(GameProfile, String)}（2 参，无 IP），
     * 1.14~1.19 是 {@code hasJoinedServer(GameProfile, String, InetAddress)}（3 参），
     * 1.20+ 改成 {@code hasJoinedServer(String, String, InetAddress)}（3 参，首参变 String）。
     * 参数类型一路在变，唯一稳定的就是方法名，所以以名字为准。</p>
     */
    public static boolean isHasJoinedServer(Method method) {
        if (method == null) {
            return false;
        }
        return "hasJoinedServer".equals(method.getName()) && method.getParameterCount() >= 2;
    }

    /**
     * 取出玩家名。
     *
     * <p>两种形态都要覆盖：
     * <ul>
     *   <li>新版（1.20+）：args[0] 直接就是 String 玩家名；</li>
     *   <li>老版：args[0] 是 GameProfile，要从它身上读 name。</li>
     * </ul>
     * 所以先看 args[0] 是不是 String，不是就当 GameProfile 处理。</p>
     */
    public static String usernameOf(Method method, Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Object first = args[0];
        if (first instanceof String) {
            return (String) first;
        }
        if (first == null) {
            return null;
        }
        // 老版本：从 GameProfile 里取名字。record 用 name()，普通类用 getName()。
        String name = readStringProperty(first, "name", "getName");
        return name;
    }

    /**
     * 取出 serverId（服务端在握手时生成的验证串）。
     *
     * <p>各版本里它都是「第一个 String 参数之后的那个 String」。新版是 args[1]，
     * 老版 args[0] 是 GameProfile、args[1] 才是 serverId，两者恰好都落在 args[1]。
     * 为稳妥起见仍做一次类型检查，并在意外情况下扫描全部参数找 String。</p>
     */
    public static String serverIdOf(Method method, Object[] args) {
        if (args == null || args.length < 2) {
            return null;
        }
        if (args[1] instanceof String) {
            return (String) args[1];
        }
        // 兜底：从后往前找一个 String（serverId 一定不是首参的玩家名）。
        for (int i = args.length - 1; i >= 1; i--) {
            if (args[i] instanceof String) {
                return (String) args[i];
            }
        }
        return null;
    }

    /**
     * 取出客户端 IP。1.8~1.13 的签名里没有这个参数，此时返回 null（属正常情况，不是错误）。
     */
    public static InetAddress addressOf(Method method, Object[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof InetAddress) {
                return (InetAddress) args[i];
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 构造 hasJoinedServer 的返回值
    // ------------------------------------------------------------------

    /**
     * 把认证服务返回的 GameProfile JSON 组装成「当前版本 hasJoinedServer 所要求的返回类型」。
     *
     * <p>这是全类最关键的方法。返回类型在不同版本里不一样：
     * <ul>
     *   <li>1.19.3 及更早：直接返回 {@code GameProfile}；</li>
     *   <li>1.19.4+ / authlib 4.x+：返回 {@code ProfileResult}（内含 GameProfile）。</li>
     * </ul>
     * 处理办法是不去猜版本，而是<b>直接读 {@code method.getReturnType()}</b>：
     * 如果返回类型就是 GameProfile，构造完直接给；否则把 GameProfile 塞进返回类型的
     * 单参构造器里。这样以后 Mojang 再包一层，也只要它保留「单参构造」就能继续工作。</p>
     *
     * @return 可直接作为代理返回值的对象；无法构造时返回 null（调用方会退回原版验证，不会误踢人）
     */
    public static Object buildJoinResult(Method method, String json) {
        if (method == null || json == null || json.isEmpty()) {
            return null;
        }

        Object profile = parseGameProfile(json);
        if (profile == null) {
            return null;
        }

        Class<?> returnType = method.getReturnType();

        // 情况一：直接要 GameProfile（老版本）。
        if (returnType.isInstance(profile)) {
            return profile;
        }

        // 情况二：要一个包装类型（现代的 ProfileResult）。找能接受 GameProfile 的单参构造。
        Constructor<?> ctor = (Constructor<?>) CACHE.get("resultCtor:" + returnType.getName());
        if (ctor == null) {
            Constructor<?>[] all = returnType.getDeclaredConstructors();
            for (int i = 0; i < all.length; i++) {
                Class<?>[] params = all[i].getParameterTypes();
                if (params.length == 1 && params[0].isInstance(profile)) {
                    ctor = all[i];
                    break;
                }
            }
            if (ctor == null) {
                // 再放宽一次：参数类型名以 GameProfile 结尾也算（防御 isInstance 因类加载器不同而失败）。
                for (int i = 0; i < all.length; i++) {
                    Class<?>[] params = all[i].getParameterTypes();
                    if (params.length == 1 && params[0].getName().endsWith("GameProfile")) {
                        ctor = all[i];
                        break;
                    }
                }
            }
            if (ctor == null) {
                warnOnce("resultCtor", "找不到 " + returnType.getName()
                        + " 的单参构造器，无法构造 hasJoinedServer 返回值。");
                return null;
            }
            ctor.setAccessible(true);
            CACHE.put("resultCtor:" + returnType.getName(), ctor);
        }

        try {
            return Reflect.newInstance(ctor, profile);
        } catch (Throwable t) {
            warnOnce("resultNew", "构造 " + returnType.getName() + " 失败: " + t);
            return null;
        }
    }

    /**
     * 解析 Mojang 风格的 hasJoined 响应体，构造 GameProfile。
     *
     * <pre>
     * {
     *   "id": "0123456789abcdef0123456789abcdef",     // 无横线的 UUID
     *   "name": "Steve",
     *   "properties": [ { "name": "textures", "value": "...", "signature": "..." } ]
     * }
     * </pre>
     */
    private static Object parseGameProfile(String json) {
        try {
            // 注意：这里用 new JsonParser().parse(...) 这个已废弃的写法，而不是
            // JsonParser.parseString(...)。原因是后者是 Gson 2.8.6 才加入的静态方法，
            // 而 1.8 时代的服务端自带 Gson 2.2.4，调用它会在运行期抛 NoSuchMethodError。
            // 废弃写法从 Gson 2.x 最早版本一直保留到今天，是唯一的全版本安全选择。
            JsonElement root = new JsonParser().parse(json);
            if (root == null || !root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("id") || !obj.has("name")) {
                return null;
            }
            UUID uuid = parseUuid(obj.get("id").getAsString());
            String name = obj.get("name").getAsString();
            if (uuid == null || name == null) {
                return null;
            }
            JsonArray properties = obj.has("properties") && obj.get("properties").isJsonArray()
                    ? obj.getAsJsonArray("properties")
                    : null;
            return createGameProfile(uuid, name, properties);
        } catch (Throwable t) {
            warnOnce("parseProfile", "解析 GameProfile JSON 失败: " + t);
            return null;
        }
    }

    /**
     * 构造 GameProfile，并把 properties（皮肤材质等）一起填进去。
     *
     * <p>皮肤数据必须在<b>构造时</b>就填好，不能事后补 —— 因为 authlib 7.x 的
     * GameProfile 是 record，{@code properties} 是 final 字段，实测连 Unsafe 都写不进去。
     * 所以流程是：先建好 PropertyMap 并装满 Property，再交给 3 参构造器。</p>
     *
     * <p>老版本（authlib ≤ 6.x，GameProfile 是普通类）没有 3 参构造，
     * 那时才退回「2 参构造 + getProperties().put()」的老路子。</p>
     */
    public static Object createGameProfile(UUID uuid, String name, JsonArray properties) {
        Class<?> profileClass = Reflect.findClass(GAME_PROFILE_NAMES);
        if (profileClass == null) {
            warnOnce("gameProfileClass", "找不到 com.mojang.authlib.GameProfile。");
            return null;
        }

        // ---- 路线 A：3 参构造 (UUID, String, PropertyMap) ----
        Constructor<?> three = findCtor(profileClass, 3);
        if (three != null) {
            Class<?> propMapType = three.getParameterTypes()[2];
            Object propertyMap = buildPropertyMap(propMapType, properties);
            if (propertyMap != null) {
                try {
                    return Reflect.newInstance(three, uuid, name, propertyMap);
                } catch (Throwable t) {
                    warnOnce("profile3", "用 3 参构造 GameProfile 失败，改试 2 参: " + t);
                }
            }
        }

        // ---- 路线 B：2 参构造 + 事后填 properties（仅 authlib ≤ 6.x 的普通类形态可行） ----
        Constructor<?> two = findCtor(profileClass, 2);
        if (two == null) {
            warnOnce("profileCtor", "GameProfile 既没有 3 参也没有 2 参构造器，无法构造。");
            return null;
        }
        Object profile;
        try {
            profile = Reflect.newInstance(two, uuid, name);
        } catch (Throwable t) {
            warnOnce("profile2", "用 2 参构造 GameProfile 失败: " + t);
            return null;
        }
        if (properties != null && properties.size() > 0) {
            fillPropertiesAfterwards(profile, properties);
        }
        return profile;
    }

    /**
     * 建一个装好 Property 的 PropertyMap。
     *
     * <p>PropertyMap 在 authlib 7.0.63 上<b>只有</b> {@code PropertyMap(Multimap)} 一个构造器
     * （实测），老版本才有无参构造。所以两条路都要备着。</p>
     */
    private static Object buildPropertyMap(Class<?> propMapType, JsonArray properties) {
        Class<?> propertyClass = Reflect.findClass(PROPERTY_NAMES);
        Class<?> mapClass = (propMapType != null) ? propMapType : Reflect.findClass(PROPERTY_MAP_NAMES);
        if (mapClass == null) {
            return null;
        }

        // 先把 Property 对象都造出来，放进一个 guava Multimap。
        // 用 guava 而不是自己实现 Multimap：guava 从 Bukkit 1.8 起就是服务端自带依赖，
        // 任何版本都拿得到，不需要额外打包。
        Multimap<String, Object> backing = LinkedHashMultimap.create();
        if (properties != null && propertyClass != null) {
            for (int i = 0; i < properties.size(); i++) {
                JsonElement el = properties.get(i);
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject po = el.getAsJsonObject();
                if (!po.has("name") || !po.has("value")) {
                    continue;
                }
                String pName = po.get("name").getAsString();
                String pValue = po.get("value").getAsString();
                String pSig = po.has("signature") ? po.get("signature").getAsString() : null;
                Object property = createProperty(propertyClass, pName, pValue, pSig);
                if (property != null) {
                    backing.put(pName, property);
                }
            }
        }

        // 路线 1：Multimap 构造器（现代 authlib）。
        Constructor<?> withMultimap = null;
        Constructor<?>[] all = mapClass.getDeclaredConstructors();
        for (int i = 0; i < all.length; i++) {
            Class<?>[] p = all[i].getParameterTypes();
            if (p.length == 1 && p[0].isAssignableFrom(backing.getClass())) {
                withMultimap = all[i];
                break;
            }
        }
        if (withMultimap != null) {
            try {
                return Reflect.newInstance(withMultimap, backing);
            } catch (Throwable t) {
                warnOnce("propMapMultimap", "用 Multimap 构造 PropertyMap 失败: " + t);
            }
        }

        // 路线 2：无参构造 + 逐个 put（老 authlib）。
        Constructor<?> noArg = findCtor(mapClass, 0);
        if (noArg != null) {
            try {
                Object map = Reflect.newInstance(noArg);
                // PropertyMap 本身就是 Multimap，直接调它的 put(Object,Object)。
                Method put = Reflect.methodByName(mapClass, "put", 2);
                if (put != null) {
                    for (java.util.Map.Entry<String, Object> e : backing.entries()) {
                        Reflect.invoke(put, map, e.getKey(), e.getValue());
                    }
                }
                return map;
            } catch (Throwable t) {
                warnOnce("propMapNoArg", "用无参构造 PropertyMap 失败: " + t);
            }
        }
        return null;
    }

    /** 构造一个 Property，有签名用 3 参、没签名用 2 参。 */
    private static Object createProperty(Class<?> propertyClass, String name, String value, String signature) {
        try {
            if (signature != null && !signature.isEmpty()) {
                Constructor<?> three = findCtor(propertyClass, 3);
                if (three != null) {
                    return Reflect.newInstance(three, name, value, signature);
                }
            }
            Constructor<?> two = findCtor(propertyClass, 2);
            if (two != null) {
                return Reflect.newInstance(two, name, value);
            }
        } catch (Throwable t) {
            warnOnce("property", "构造 Property 失败: " + t);
        }
        return null;
    }

    /**
     * 老 authlib 形态下，构造完再往 GameProfile 里补 properties。
     * 现代 record 形态走不到这里（那边在构造时就填好了）。
     */
    private static void fillPropertiesAfterwards(Object profile, JsonArray properties) {
        try {
            Method getProperties = Reflect.methodByNames(profile.getClass(), 0,
                    "getProperties", "properties");
            if (getProperties == null) {
                return;
            }
            Object map = Reflect.invoke(getProperties, profile);
            if (map == null) {
                return;
            }
            Class<?> propertyClass = Reflect.findClass(PROPERTY_NAMES);
            Method put = Reflect.methodByName(map.getClass(), "put", 2);
            if (propertyClass == null || put == null) {
                return;
            }
            for (int i = 0; i < properties.size(); i++) {
                JsonElement el = properties.get(i);
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject po = el.getAsJsonObject();
                if (!po.has("name") || !po.has("value")) {
                    continue;
                }
                String pName = po.get("name").getAsString();
                String pValue = po.get("value").getAsString();
                String pSig = po.has("signature") ? po.get("signature").getAsString() : null;
                Object property = createProperty(propertyClass, pName, pValue, pSig);
                if (property != null) {
                    Reflect.invoke(put, map, pName, property);
                }
            }
        } catch (Throwable t) {
            warnOnce("fillProps", "补填 GameProfile properties 失败: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 解析 UUID。认证服务返回的是<b>不带横线</b>的 32 位十六进制串，
     * {@link UUID#fromString} 只认带横线的格式，所以要先补横线。
     * 两种格式都兼容，避免不同实现的服务端差异导致登录失败。
     */
    static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        try {
            if (s.indexOf('-') >= 0) {
                return UUID.fromString(s);
            }
            if (s.length() != 32) {
                return null;
            }
            StringBuilder sb = new StringBuilder(36);
            sb.append(s, 0, 8).append('-')
                    .append(s, 8, 12).append('-')
                    .append(s, 12, 16).append('-')
                    .append(s, 16, 20).append('-')
                    .append(s, 20, 32);
            return UUID.fromString(sb.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 按参数个数找构造器（带缓存）。 */
    private static Constructor<?> findCtor(Class<?> clazz, int arity) {
        if (clazz == null) {
            return null;
        }
        String key = "ctor:" + clazz.getName() + ":" + arity;
        Object cached = CACHE.get(key);
        if (cached instanceof Constructor) {
            return (Constructor<?>) cached;
        }
        Constructor<?> found = Reflect.constructorByArity(clazz, arity);
        if (found != null) {
            CACHE.put(key, found);
        }
        return found;
    }

    /** 读一个对象上的 String 属性，record 风格（name()）和 bean 风格（getName()）都试。 */
    private static String readStringProperty(Object target, String recordName, String beanName) {
        try {
            Method m = Reflect.methodByNames(target.getClass(), 0, recordName, beanName);
            if (m != null) {
                Object v = Reflect.invoke(m, target);
                if (v instanceof String) {
                    return (String) v;
                }
            }
            // 方法拿不到就直接读字段。
            java.lang.reflect.Field f = Reflect.fieldByName(target.getClass(), recordName);
            if (f != null) {
                Object v = Reflect.getFieldValue(f, target);
                if (v instanceof String) {
                    return (String) v;
                }
            }
        } catch (Throwable ignore) {
            // 读不到就返回 null，由调用方决定退回原版验证。
        }
        return null;
    }

    /** 描述当前 authlib 形态，用于启动日志与 /multilogin status。 */
    public static String describeAuthlib() {
        Class<?> profileClass = Reflect.findClass(GAME_PROFILE_NAMES);
        if (profileClass == null) {
            return "authlib=缺失";
        }
        boolean isRecord = Reflect.isRecordClass(profileClass);
        boolean has3 = findCtor(profileClass, 3) != null;
        return "authlib=" + (isRecord ? "record 形态(7.x)" : "普通类形态(≤6.x)")
                + (has3 ? ", GameProfile 3 参构造可用" : ", 仅 2 参构造");
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
}
