package net.wifil.mcmultilogin.verify;

import net.wifil.mcmultilogin.MultiLoginPlugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * mcverify 门禁：监听玩家进服 / 出服，落实「未验证踢出 + 验证码」「已验证欢迎 + 群播报」。
 *
 * <h2>门禁流程（合并两套现成方案后的最终形态）</h2>
 * <ol>
 *   <li>玩家进服：查共享 {@link VerifyState}（verify.json）。
 *     <ul>
 *       <li><b>已验证</b>：游戏内 {@code 欢迎回来}（开关 {@code welcome_back}），
 *           游戏内进服播报（开关 {@code in_game_join_msg}），
 *           QQ 群「XXX 进入了服务器」（开关 {@code join_broadcast}）。</li>
 *       <li><b>未验证</b>：生成 / 复用验证码写入共享 verify.json，
 *           下一 tick 用 {@code /kick player <原因>} 把玩家踢出，
 *           原因含 {@code 验证 XXXXXX}（开关 {@code kick_unverified} / {@code show_code_in_kick}）。
 *           玩家去 QQ 群发「验证 XXXXXX」后由 mcverify(Python) 置为已验证。</li>
 *     </ul>
 *   </li>
 *   <li>玩家出服：仅当本次进服时是「已验证」状态，才发 QQ 群「XXX 离开了服务器」
 *     （开关 {@code quit_broadcast}）+ 游戏内出服播报（开关 {@code in_game_quit_msg}）。
 *     未验证被踢的玩家不发退服播报，避免「进了又走」的刷屏。</li>
 * </ol>
 *
 * <p>所有行为均受 {@link VerifyConfig} 的 true/false 开关控制；{@code enabled=false} 时整体不介入。</p>
 */
public final class VerifyGate implements Listener {

    private final MultiLoginPlugin plugin;
    private final Logger logger;

    /** 本次会话进服时是否为已验证（用于退服播报去重）。 */
    private final ConcurrentHashMap<UUID, Boolean> joinedVerified = new ConcurrentHashMap<UUID, Boolean>();

    /**
     * 只持有插件引用：config / state / onebot 每次事件都从插件取最新实例，
     * 这样 {@code /multilogin reload} 重建配置后无需重新注册监听器即可生效。
     */
    public VerifyGate(MultiLoginPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    // ------------------------------------------------------------------ 进服

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        VerifyConfig config = plugin.verifyConfig();
        VerifyState state = plugin.verifyState();
        if (config == null || state == null || !config.enabled()) {
            return;
        }
        Player p = event.getPlayer();
        String uuid = p.getUniqueId().toString();
        String name = p.getName();
        boolean bedrock = name.startsWith(".");

        if (state.isVerified(uuid)) {
            joinedVerified.put(p.getUniqueId(), Boolean.TRUE);
            handleVerifiedJoin(p, name);
        } else {
            joinedVerified.put(p.getUniqueId(), Boolean.FALSE);
            handleUnverifiedJoin(p, uuid, name, bedrock);
        }
    }

    private void handleVerifiedJoin(Player p, String name) {
        VerifyConfig config = plugin.verifyConfig();
        if (config.welcomeBack()) {
            p.sendMessage("§a欢迎回来，§e" + name + "§a！");
        }
        if (config.inGameJoinMsg()) {
            Bukkit.broadcastMessage("§e" + name + " §a进入了服务器");
        }
        if (config.joinBroadcast()) {
            OneBotSender onebot = plugin.onebot();
            if (onebot != null) {
                onebot.sendGroupMessage(config.broadcastGroupId(), name + " 进入了服务器");
            }
        }
    }

    private void handleUnverifiedJoin(final Player p, final String uuid, final String name, final boolean bedrock) {
        VerifyConfig config = plugin.verifyConfig();
        VerifyState state = plugin.verifyState();
        if (!config.kickUnverified()) {
            // 开关关了：不踢，当作普通玩家放行（但仍可在下次进服时补验证）。
            return;
        }
        com.google.gson.JsonObject rec = state.ensurePending(uuid, name, bedrock, config.codeTtlSeconds());
        final String code = (rec != null && rec.has("code")) ? rec.get("code").getAsString() : "";

        // 把「踢出」推迟到下一个 tick：确保进服流程（世界/背包加载）先走完，
        // 也避免某些服务端在 PlayerJoinEvent 内直接踢会导致玩家卡在「登录中」状态。
        final String reason = buildKickReason(code);
        final String targetName = name;
        plugin.getServer().getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                Player online = Bukkit.getPlayerExact(targetName);
                if (online != null && online.isOnline()) {
                    online.kickPlayer(reason);
                    logger.info("[VerifyGate] 未验证玩家 " + targetName
                            + " 已被请离（验证码 " + (config.showCodeInKick() ? code : "已隐藏") + "）");
                }
            }
        }, 1L);

        // 群内也提示一下管理员/群友有人来验号（不带码，避免泄露）
        if (config.joinBroadcast()) {
            OneBotSender onebot = plugin.onebot();
            if (onebot != null) {
                onebot.sendGroupMessage(config.broadcastGroupId(), name + " 进入了服务器（待验证）");
            }
        }
    }

    /** 构造踢出原因：含「验证 XXXXXX」。 */
    private String buildKickReason(String code) {
        VerifyConfig config = plugin.verifyConfig();
        if (config.showCodeInKick() && code != null && !code.isEmpty()) {
            return "验证 " + code + " （将本验证码发送到绑定的QQ群完成绑定后重进）";
        }
        return "请先到QQ群发送「验证」完成绑定后重进服务器";
    }

    // ------------------------------------------------------------------ 出服

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        VerifyConfig config = plugin.verifyConfig();
        if (config == null || !config.enabled()) {
            return;
        }
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        Boolean wasVerified = joinedVerified.remove(id);
        if (wasVerified == null || !wasVerified.booleanValue()) {
            // 未验证（被踢）的玩家不发退服播报，避免「进了又走」刷屏。
            return;
        }
        String name = p.getName();
        if (config.inGameQuitMsg()) {
            Bukkit.broadcastMessage("§e" + name + " §c离开了服务器");
        }
        if (config.quitBroadcast()) {
            OneBotSender onebot = plugin.onebot();
            if (onebot != null) {
                onebot.sendGroupMessage(config.broadcastGroupId(), name + " 离开了服务器");
            }
        }
    }
}
