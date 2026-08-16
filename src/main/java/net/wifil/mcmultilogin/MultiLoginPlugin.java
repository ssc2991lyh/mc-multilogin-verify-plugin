package net.wifil.mcmultilogin;

import net.wifil.mcmultilogin.api.HasJoinedClient;
import net.wifil.mcmultilogin.api.LoginApiClient;
import net.wifil.mcmultilogin.compat.ServerBridge;
import net.wifil.mcmultilogin.config.PluginConfig;
import net.wifil.mcmultilogin.config.ServiceConfig;
import net.wifil.mcmultilogin.netty.LoginPipelineHook;
import net.wifil.mcmultilogin.session.SessionServiceHook;
import net.wifil.mcmultilogin.service.VerifyService;
import net.wifil.mcmultilogin.tracking.PlayerNameTracker;
import net.wifil.mcmultilogin.verify.OneBotSender;
import net.wifil.mcmultilogin.verify.VerifyCallbackServer;
import net.wifil.mcmultilogin.verify.VerifyConfig;
import net.wifil.mcmultilogin.verify.VerifyGate;
import net.wifil.mcmultilogin.verify.VerifyState;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MCMultiLoginCompat 的 Bukkit 插件入口。
 *
 * <p>对应原 Fabric 模组的 {@code McMultiloginCompatMod}（一个
 * {@code DedicatedServerModInitializer}）。原模组靠 Mixin 在类加载期改字节码，
 * Bukkit 插件没有这个能力，因此改造为两条运行期挂钩：</p>
 *
 * <ol>
 *   <li><b>会话服务代理</b>（{@link SessionServiceHook}）：用 JDK 动态代理包装服务器持有的
 *       {@code MinecraftSessionService}，接管 {@code hasJoinedServer}
 *       —— 替代原 {@code YggdrasilSessionServiceMixin}。</li>
 *   <li><b>Netty 登录拦截</b>（{@link LoginPipelineHook}）：在网络层拦截「登录阶段断开包」，
 *       把笼统的验证失败提示换成认证服务返回的真实原因
 *       —— 替代原 {@code ServerLoginPacketListenerMixin}。</li>
 * </ol>
 *
 * <p><b>时序要点（踩过的坑）</b>：本插件用 {@code load: STARTUP} 尽早启用，
 * 目的是保证会话服务在服务器开始接受连接之前就被替换掉。但 Bukkit 的 STARTUP 阶段
 * {@code onEnable} 早于 {@code startTcpServerListener()}，此刻监听通道<b>还没绑定</b>，
 * Netty 注入必然拿不到通道。所以：会话代理立即装，Netty 注入交给调度器延后重试
 * （第一个 tick 时端口一定已绑定）。</p>
 */
public class MultiLoginPlugin extends JavaPlugin {

    /** 单例。Netty handler / 动态代理都在非 Bukkit 线程上跑，需要静态入口取上下文。 */
    private static volatile MultiLoginPlugin instance;

    /**
     * 待发送的详细错误消息：key = 玩家名小写，value = 详细原因。
     * 对应原模组 Mixin 里的静态 {@code PENDING_ERRORS}，但加了 TTL 清理（见 {@link PendingErrors}）。
     */
    private final PendingErrors pendingErrors = new PendingErrors();

    private PluginConfig config;
    private LoginApiClient api;
    private PlayerNameTracker tracker;

    // ---- 合并进来的 MC-MultiLogin-service（自包含验证） + mcverify 门禁 ----
    private ServiceConfig serviceConfig;
    private VerifyService verifyService;
    private VerifyConfig verifyConfig;
    private VerifyState verifyState;
    private OneBotSender onebot;
    private VerifyGate verifyGate;
    private VerifyCallbackServer callbackServer;
    private boolean verifyRegistered;

    private boolean sessionHooked;
    private boolean nettyHooked;

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        instance = this;

        // 1) 配置：首次启动会把 jar 内的 config.yml 释放到 plugins/MCMultiLoginCompat/
        saveDefaultConfig();
        this.config = PluginConfig.from(getConfig());

        // 2) 打印运行环境（服务端类型/版本/CraftBukkit 包名/authlib 形态），
        //    这几行日志在跨版本排错时价值极高。
        getLogger().info("运行环境: " + ServerBridge.describeEnvironment());

        // 2.5) 合并进来的 mcverify 门禁 + 自包含验证：无论是否配置外部 api-url 都初始化。
        //      这一块是「把 Node 版 MC-MultiLogin-service 与 mcverify 合并进插件」的落地，
        //      首次运行会自动生成 config.json / verifyconfig.json，缺失即自举。
        initVerifyFeature();

        // 3) 原多账户登录兼容（接管 hasJoinedServer）。
        //    接管触发条件（满足任一即安装会话代理）：
        //      - config.yml 配置了 api-url（对接外部 MC-MultiLogin-service），或
        //      - config.json 配置了至少一个 method（自包含内嵌验证，无需外部服务）。
        //    两者都没有才放弃接管；mcverify 门禁无论哪种情况都已在第 2.5 步初始化、照常生效。
        boolean selfContained = serviceConfig.hasAnyMethod();
        if (!config.isConfigured() && !selfContained) {
            getLogger().warning("尚未配置 api-url，且 config.json 未包含任何验证 method，"
                    + "多账户登录兼容（hasJoined 接管）不生效；mcverify 门禁仍可用。");
            getLogger().warning("如需启用：在 config.yml 填入 api-url（对接外部 MC-MultiLogin-service），"
                    + "或在 config.json 的 method[] 填入皮肤站 handles（自包含模式，无需外部服务）。");
            registerCommand();
            return;
        }

        if (config.isConfigured()) {
            this.api = new LoginApiClient(config.apiUrl(), config.timeoutSeconds());
        } else {
            this.api = null; // 自包含模式：验证走内嵌 VerifyService，不建外部客户端
        }
        this.tracker = new PlayerNameTracker(getDataFolder(), getLogger());

        // 4) 会话服务代理：必须尽早，且必须成功 —— 这是核心功能。
        try {
            this.sessionHooked = SessionServiceHook.install();
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "安装会话服务代理时抛出异常", t);
            this.sessionHooked = false;
        }

        if (!sessionHooked) {
            getLogger().severe("会话服务代理安装失败，多账户登录兼容功能不可用。");
            if (config.shutdownOnFailure()) {
                // 服主显式要求「宁可不开服，也不要在验证没生效的情况下裸奔」。
                getLogger().severe("shutdown-on-failure=true，正在关闭服务器。");
                getServer().shutdown();
                return;
            }
        } else {
            getLogger().info("会话服务代理已安装。");
        }

        // 5) Netty 注入：延后到通道绑定之后（见类注释的时序说明）。
        scheduleNettyInstall(0);

        registerCommand();
    }

    @Override
    public void onDisable() {
        if (this.callbackServer != null) {
            this.callbackServer.stop();
            this.callbackServer = null;
        }
        // 卸载顺序与安装相反，尽力而为，任何一步失败都不影响下一步。
        if (nettyHooked) {
            try {
                LoginPipelineHook.uninstall();
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "卸载 Netty 登录拦截失败", t);
            }
            nettyHooked = false;
        }
        if (sessionHooked) {
            try {
                // 把原始会话服务还原回去，避免热卸载后留下指向已卸载类加载器的代理，
                // 那会导致 /reload 之后所有玩家都进不来。
                SessionServiceHook.uninstall();
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "还原会话服务失败", t);
            }
            sessionHooked = false;
        }
        pendingErrors.clear();
        instance = null;
        getLogger().info("已卸载。");
    }

    /**
     * 安排 Netty 注入。首次在下一 tick 执行；失败则退避重试，最多 {@code MAX_ATTEMPT} 次。
     * 之所以要重试：极少数情况下（如自定义 IP 绑定、代理转发前置）通道注册会稍晚一点。
     */
    private void scheduleNettyInstall(final int attempt) {
        final int maxAttempt = 5;
        // 第 1 次延迟 1 tick，之后线性退避 20/40/60/80 tick，总共约 10 秒内完成。
        long delay = (attempt == 0) ? 1L : attempt * 20L;

        getServer().getScheduler().runTaskLater(this, new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    ok = LoginPipelineHook.install();
                } catch (Throwable t) {
                    getLogger().log(Level.WARNING, "注入 Netty 登录拦截时抛出异常", t);
                }
                if (ok) {
                    nettyHooked = true;
                    return;
                }
                if (attempt + 1 < maxAttempt) {
                    scheduleNettyInstall(attempt + 1);
                } else {
                    // 注入失败不是致命问题：验证逻辑（会话代理）仍然生效，
                    // 只是踢人时看到的是原版笼统提示，功能降级但可用。
                    getLogger().warning("Netty 登录拦截注入失败，"
                            + "详细踢出原因将无法显示（验证功能不受影响）。");
                }
            }
        }, delay);
    }

    private void registerCommand() {
        if (getCommand("multilogin") != null) {
            getCommand("multilogin").setExecutor(new MultiLoginCommand(this));
        }
    }

    // ------------------------------------------------------------------
    // 合并进来的 mcverify 门禁 + 自包含验证
    // ------------------------------------------------------------------

    /**
     * 初始化合并进来的功能：加载 config.json（自包含验证服务配置）、verifyconfig.json（门禁开关）、
     * 构建共享状态与 OneBot 发送器，并注册进服/出服监听。
     *
     * <p>缺失的配置文件会自动写出默认模板（首次自举）。监听只注册一次，
     * 热重载（{@link #reload()}）只重建配置对象、不重复注册监听器。</p>
     */
    private void initVerifyFeature() {
        // 热重载时先停掉旧入站监听，避免端口占用
        if (this.callbackServer != null) {
            this.callbackServer.stop();
            this.callbackServer = null;
        }
        this.serviceConfig = ServiceConfig.load(getDataFolder(), getLogger());
        this.verifyConfig = VerifyConfig.load(getDataFolder(), getLogger());
        this.verifyState = new VerifyState(getDataFolder(), serviceConfig.verifyDataFile(), getLogger());
        this.verifyState.setTtlSeconds(verifyConfig.codeTtlSeconds());

        // 验证通道：onebot/both 启用 OneBot 出站发送 + HTTP 入站监听；astrbot 仅记录 token
        if (verifyConfig.useOnebot()) {
            this.onebot = new OneBotSender(verifyConfig.onebotHttpUrl(), verifyConfig.onebotToken(),
                    getLogger(), 10);
            this.callbackServer = new VerifyCallbackServer(this);
            this.callbackServer.start(verifyConfig.verifyWebhookPort());
        } else {
            this.onebot = null;
            this.callbackServer = null;
        }
        if (verifyConfig.useAstrbot()) {
            String t = verifyConfig.astrbotToken();
            if (t == null || t.isEmpty()) {
                getLogger().warning("[Verify] astrbot 通道已启用，但 astrbottoken 为空！"
                        + " 请在 verifyconfig.json 填入 MC 服 plugins/AstrbotAdapter/config.yml 的自动生成 token。");
            }
        }

        int timeout = (config != null) ? config.timeoutSeconds() : 10;
        this.verifyService = serviceConfig.hasAnyMethod()
                ? new VerifyService(serviceConfig, getDataFolder(), getLogger(), timeout)
                : null;

        if (verifyService != null) {
            getLogger().info("[Verify] 已启用内嵌 MC-MultiLogin-service 验证（method 数="
                    + serviceConfig.methods().size() + "）。");
        }
        getLogger().info("[Verify] mcverify 门禁已加载，开关：enabled=" + verifyConfig.enabled()
                + " kick_unverified=" + verifyConfig.kickUnverified()
                + " join_broadcast=" + verifyConfig.joinBroadcast());

        if (!verifyRegistered) {
            this.verifyGate = new VerifyGate(this);
            getServer().getPluginManager().registerEvents(verifyGate, this);
            verifyRegistered = true;
        }
    }

    /**
     * 选择本次 hasJoined 验证客户端：优先内嵌自包含验证（合并后的 MC-MultiLogin-service），
     * 未配置时回退外部 HTTP 服务（LoginApiClient），两者都无则 null（调用方 fail-open 退回原版）。
     */
    public HasJoinedClient resolveClient() {
        if (verifyService != null) {
            return verifyService;
        }
        if (api != null) {
            return api;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 对外访问（供 session / netty 包在任意线程调用）
    // ------------------------------------------------------------------

    /** 单例；插件未启用时返回 null，调用方必须判空。 */
    public static MultiLoginPlugin instance() {
        return instance;
    }

    public Logger log() {
        return getLogger();
    }

    /** 仅在 config debug=true 时输出。 */
    public void debug(String msg) {
        if (config != null && config.debug()) {
            getLogger().info("[DEBUG] " + msg);
        }
    }

    public PluginConfig config() {
        return config;
    }

    public LoginApiClient api() {
        return api;
    }

    public PlayerNameTracker tracker() {
        return tracker;
    }

    /** 合并进来的配置：自包含验证服务（config.json）。 */
    public ServiceConfig serviceConfig() {
        return serviceConfig;
    }

    /** 合并进来的内嵌验证服务（MC-MultiLogin-service 移植）。可能为 null（未配置 method）。 */
    public VerifyService verifyService() {
        return verifyService;
    }

    /** mcverify 门禁开关配置（verifyconfig.json）。 */
    public VerifyConfig verifyConfig() {
        return verifyConfig;
    }

    /** 共享验证状态（verify.json，与 mcverify(Python) 共用）。 */
    public VerifyState verifyState() {
        return verifyState;
    }

    /** OneBot 群消息发送器。 */
    public OneBotSender onebot() {
        return onebot;
    }

    /** mcverify 门禁监听。 */
    public VerifyGate verifyGate() {
        return verifyGate;
    }

    /**
     * 待发送的详细错误表。
     *
     * <p>返回类型刻意声明为 {@link ConcurrentMap} 而不是 {@link Map}：
     * 会话代理侧（{@code LoginSessionHandler}）需要并发语义来做容量保护，
     * 而 Netty 侧只当普通 Map 用 —— {@code ConcurrentMap} 是 {@code Map} 的子接口，
     * 声明成前者可以同时满足两边，不必在任一侧做强转。</p>
     */
    public ConcurrentMap<String, String> pendingErrors() {
        return pendingErrors;
    }

    public boolean sessionHooked() {
        return sessionHooked;
    }

    public boolean nettyHooked() {
        return nettyHooked;
    }

    /** 热重载配置。会话代理与 Netty 注入不受影响（只有 api-url/超时会重建客户端）。
     *  mcverify 门禁配置与共享状态一并重建（监听器不再重复注册）。 */
    public void reload() {
        reloadConfig();
        this.config = PluginConfig.from(getConfig());
        if (config.isConfigured()) {
            this.api = new LoginApiClient(config.apiUrl(), config.timeoutSeconds());
        } else {
            this.api = null;
        }
        initVerifyFeature();
        getLogger().info("配置已重载: " + config);
    }

    // ------------------------------------------------------------------

    /**
     * 带 TTL 的待发错误表。
     *
     * <p>为什么需要 TTL：正常流程是「验证失败写入 → 断开包发出时取走」，一取即删。
     * 但如果某次登录在断开前就掉线（客户端主动断连、网络中断），条目就没人取走，
     * 原模组的静态 Map 会一直留着它，长期运行等于内存泄漏，还可能把过期消息
     * 错配给之后同名玩家的一次无关失败。所以在每次写入时顺手清掉超过 60 秒的旧条目。</p>
     *
     * <p>继承 {@link ConcurrentHashMap} 而不是包一层，是为了让
     * {@code pendingErrors()} 的使用方（Netty handler 直接 {@code remove}、
     * 会话代理直接 {@code put}）无需知道 TTL 的存在。</p>
     */
    private static final class PendingErrors extends ConcurrentHashMap<String, String> {

        private static final long serialVersionUID = 1L;
        private static final long TTL_MILLIS = 60_000L;

        private final ConcurrentHashMap<String, Long> stamps = new ConcurrentHashMap<String, Long>();

        @Override
        public String put(String key, String value) {
            purgeExpired();
            stamps.put(key, Long.valueOf(System.currentTimeMillis()));
            return super.put(key, value);
        }

        @Override
        public String remove(Object key) {
            stamps.remove(key);
            return super.remove(key);
        }

        @Override
        public void clear() {
            stamps.clear();
            super.clear();
        }

        private void purgeExpired() {
            if (stamps.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> e : stamps.entrySet()) {
                Long at = e.getValue();
                if (at != null && now - at.longValue() > TTL_MILLIS) {
                    String k = e.getKey();
                    stamps.remove(k);
                    super.remove(k);
                }
            }
        }
    }
}
