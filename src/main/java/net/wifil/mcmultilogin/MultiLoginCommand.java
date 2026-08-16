package net.wifil.mcmultilogin;

import net.wifil.mcmultilogin.api.LoginApiClient;
import net.wifil.mcmultilogin.compat.ServerBridge;
import net.wifil.mcmultilogin.netty.LoginPipelineHook;
import net.wifil.mcmultilogin.netty.LoginTapHandler;
import net.wifil.mcmultilogin.session.SessionServiceHook;
import net.wifil.mcmultilogin.tracking.PlayerNameTracker;
import net.wifil.mcmultilogin.verify.VerifyState;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@code /multilogin <status|reload|renames>} 的实现。
 *
 * <p>原 Fabric 模组没有任何指令（Mod 端排错只能看日志）。Bukkit 侧加上它有实际价值：
 * 挂钩是运行期反射完成的，服主需要一个直接手段确认「到底钩上了没有」，
 * 而不是靠翻启动日志。</p>
 *
 * <p>刻意只用 {@link ChatColor} + {@code sendMessage(String)} 这套从 1.8 起就存在的老 API，
 * 不用 Paper 的 Adventure {@code Component} —— 后者在老版本和 Spigot 上不存在，
 * 会直接破坏「单 JAR 全版本兼容」这个目标。</p>
 */
public class MultiLoginCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB = new ArrayList<String>();

    static {
        SUB.add("status");
        SUB.add("reload");
        SUB.add("renames");
        SUB.add("verify");
    }

    private final MultiLoginPlugin plugin;

    public MultiLoginCommand(MultiLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = (args.length == 0) ? "status" : args[0].toLowerCase();

        if ("reload".equals(sub)) {
            plugin.reload();
            sender.sendMessage(ChatColor.GREEN + "[MultiLogin] 配置已重载。"
                    + ChatColor.GRAY + "（挂钩状态不变，如需重新挂钩请重启服务器）");
            return true;
        }

        if ("renames".equals(sub)) {
            sendRenames(sender);
            return true;
        }

        if ("status".equals(sub)) {
            sendStatus(sender);
            return true;
        }

        if ("verify".equals(sub)) {
            handleVerify(sender, args);
            return true;
        }

        sender.sendMessage(ChatColor.RED + "用法: /" + label + " <status|reload|renames>");
        return true;
    }

    /**
     * 处理 {@code /multilogin verify <验证码>}。
     *
     * <p>这是 astrbot 验证通道的 server 端入口：mcverify(AstrBot 插件) 收到 QQ 群「验证 XXXX」
     * 后，经 AstrBotAdapter REST {@code command/execute} 以控制台身份执行本指令，
     * 由 MC 服在本地 verify.json 按码标记已验证。指令输出里的「验证成功」字样会被
     * mcverify 当作回调成功标志回群提示。</p>
     */
    private void handleVerify(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /" + "multilogin" + " verify <验证码>");
            return;
        }
        String code = args[1].trim().toUpperCase();
        VerifyState state = plugin.verifyState();
        if (state == null) {
            sender.sendMessage(ChatColor.RED + "[MultiLogin] 验证模块未初始化。");
            return;
        }
        String name = state.markVerifiedByCode(code, "");
        if (name != null) {
            sender.sendMessage(ChatColor.GREEN + "[MultiLogin] 验证成功：玩家 " + ChatColor.YELLOW + name
                    + ChatColor.GREEN + " 已绑定，可重新进服。");
        } else {
            sender.sendMessage(ChatColor.RED + "[MultiLogin] 无效或已过期的验证码：" + code);
        }
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "===== MCMultiLoginCompat 状态 =====");
        sender.sendMessage(ChatColor.GRAY + "版本: " + ChatColor.WHITE
                + plugin.getDescription().getVersion());
        sender.sendMessage(ChatColor.GRAY + "运行环境: " + ChatColor.WHITE
                + ServerBridge.describeEnvironment());

        boolean configured = plugin.config() != null && plugin.config().isConfigured();
        sender.sendMessage(ChatColor.GRAY + "认证地址: "
                + (configured
                ? ChatColor.WHITE + plugin.config().apiUrl()
                : ChatColor.RED + "未配置"));

        if (configured) {
            sender.sendMessage(ChatColor.GRAY + "自动改名: " + flag(plugin.config().autoRename())
                    + ChatColor.GRAY + "  超时: " + ChatColor.WHITE
                    + plugin.config().timeoutSeconds() + "s"
                    + ChatColor.GRAY + "  调试: " + flag(plugin.config().debug()));
        }

        sender.sendMessage(ChatColor.GRAY + "会话服务代理: "
                + (plugin.sessionHooked()
                ? ChatColor.GREEN + "已安装 " + ChatColor.GRAY + "(" + SessionServiceHook.statusLine() + ")"
                : ChatColor.RED + "未安装"));

        sender.sendMessage(ChatColor.GRAY + "Netty 登录拦截: "
                + (LoginPipelineHook.isInstalled()
                ? ChatColor.GREEN + LoginPipelineHook.statusLine()
                : ChatColor.RED + "未注入"));

        sender.sendMessage(ChatColor.GRAY + "已替换踢出消息: " + ChatColor.WHITE
                + LoginTapHandler.replacedCount() + " 次");

        if (plugin.api() != null) {
            sender.sendMessage(ChatColor.GRAY + "认证请求统计: " + ChatColor.WHITE
                    + LoginApiClient.statsLine());
        }

        sender.sendMessage(ChatColor.GRAY + "待发详细错误: " + ChatColor.WHITE
                + plugin.pendingErrors().size() + " 条");

        PlayerNameTracker tracker = plugin.tracker();
        sender.sendMessage(ChatColor.GRAY + "改名记录: " + ChatColor.WHITE
                + (tracker == null ? 0 : tracker.size()) + " 条");
    }

    private void sendRenames(CommandSender sender) {
        PlayerNameTracker tracker = plugin.tracker();
        if (tracker == null) {
            sender.sendMessage(ChatColor.RED + "[MultiLogin] 插件未完成初始化（认证地址未配置）。");
            return;
        }
        Map<String, String> all = tracker.getAllRenames();
        if (all.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "[MultiLogin] 暂无自动改名记录。");
            return;
        }
        sender.sendMessage(ChatColor.AQUA + "===== 自动改名记录 (" + all.size() + ") =====");
        for (Map.Entry<String, String> e : all.entrySet()) {
            sender.sendMessage(ChatColor.WHITE + e.getKey()
                    + ChatColor.GRAY + " -> " + ChatColor.YELLOW + e.getValue());
        }
        sender.sendMessage(ChatColor.DARK_GRAY + "文件: " + tracker.file().getPath());
    }

    private static String flag(boolean b) {
        return (b ? ChatColor.GREEN + "开" : ChatColor.RED + "关").toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> out = new ArrayList<String>();
            for (String s : SUB) {
                if (s.startsWith(prefix)) {
                    out.add(s);
                }
            }
            return out;
        }
        return Collections.emptyList();
    }
}
