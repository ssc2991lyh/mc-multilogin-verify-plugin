package net.wifil.mcmultilogin.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;

import net.wifil.mcmultilogin.MultiLoginPlugin;
import net.wifil.mcmultilogin.compat.ServerBridge;

import java.util.List;
import java.util.logging.Level;

/**
 * 把 {@link LoginTapHandler} 注入到服务器的 Netty 管道里。
 *
 * <p>对应原 Fabric 模组里对 {@code ServerLoginPacketListenerImpl#disconnect} 的 Mixin 注入，
 * 但 Bukkit 侧没有 Mixin、也没有任何事件能覆盖登录阶段的断开，所以只能下沉到 Netty 层：
 * 在<b>监听 Channel</b>的管道最前面插一个 acceptor，拿到每一个新接受的子连接，再给子连接
 * 管道挂上我们的 tap handler，由它在出站时把登录断开包替换成详细消息。</p>
 *
 * <p>设计要点（详见各处注释）：
 * <ul>
 *   <li>为什么 {@code addFirst} 到监听通道：监听 Channel 的管道里，{@code channelRead} 传递的
 *       消息本身就是「刚接受的子连接 Channel」，我们在末尾的 {@code ServerBootstrapAcceptor}
 *       之前就能拿到它，完全无需去碰 Netty 内部 private final 的 childHandler 字段。</li>
 *   <li>为什么延迟一个事件循环：拿到子 Channel 时原版 encoder/decoder 还没装上，必须等原版
 *       childHandler 就位后再 addLast 我们的 handler。</li>
 *   <li>为什么 {@code addLast} 能拿到包对象：出站数据从 tail 往 head 流
 *       （{@code tail -> ... -> encoder -> head}），addLast 即尾部、最先看到尚未被序列化
 *       的包对象，而非字节。我们不依赖 "encoder"/"packet_handler" 等各版本不一致的 handler 名。</li>
 * </ul>
 */
public final class LoginPipelineHook {

    /** 注入到监听管道最前面的 acceptor 名字。 */
    public static final String ACCEPTOR_NAME = "mcmultilogin-acceptor";
    /** 注入到子连接管道里的 tap handler 名字（LoginTapHandler 也会引用）。 */
    public static final String TAP_NAME = "mcmultilogin-tap";

    /**
     * 业务 handler 的候选名字，作为插入锚点。
     *
     * <p>原版从 1.8 到现在，Netty 管道里那个真正处理数据包的 handler 一直叫
     * {@code packet_handler}（{@code Connection}/{@code NetworkManager} 本体），
     * 这是整条管道上最稳定的名字之一。</p>
     */
    private static final String[] ANCHOR_NAMES = {"packet_handler", "packet handler"};

    private static volatile boolean installed = false;
    private static int injectedChannels = 0;

    private LoginPipelineHook() {
        // 工具类，禁止实例化。
    }

    /**
     * 注入。幂等：已注入则直接返回 true。
     *
     * @return 是否至少成功注入一个监听通道。
     */
    public static synchronized boolean install() {
        if (installed) {
            return true;
        }

        List<Object> channels = ServerBridge.serverChannels();
        if (channels == null || channels.isEmpty()) {
            MultiLoginPlugin plugin = MultiLoginPlugin.instance();
            if (plugin != null) {
                plugin.log().warning("未能获取监听通道，Netty 登录拦截注入跳过。"
                        + "可能服务器尚未开始监听，或使用了非 Netty 传输。");
            }
            return false;
        }

        int ok = 0;
        for (Object obj : channels) {
            if (!(obj instanceof Channel)) {
                continue;
            }
            Channel ch = (Channel) obj;
            try {
                // 已注入则跳过，保证幂等、避免重复挂 handler。
                if (ch.pipeline().get(ACCEPTOR_NAME) != null) {
                    ok++;
                    continue;
                }
                // addFirst：在我们的 acceptor 之前没有其他 handler 处理子连接，
                // 确保我们能最先、且一定拿到每个新连接。
                ch.pipeline().addFirst(ACCEPTOR_NAME, new Acceptor());
                ok++;
            } catch (Throwable t) {
                // 单个通道失败不影响其它通道，尽力而为。
                MultiLoginPlugin plugin = MultiLoginPlugin.instance();
                if (plugin != null) {
                    plugin.log().log(Level.WARNING, "注入监听通道失败: " + ch, t);
                }
            }
        }

        injectedChannels = ok;
        installed = ok > 0;
        MultiLoginPlugin plugin = MultiLoginPlugin.instance();
        if (plugin != null) {
            plugin.log().info("Netty 登录拦截已注入 " + ok + " 个监听通道。");
        }
        return installed;
    }

    /**
     * 尽最大努力卸载：移除监听管道里的 acceptor。已建立的子连接上的 tap handler 不做遍历移除
     * （登录连接生命周期短，且卸载通常伴随服务器关闭，逐连接清理收益极低、风险更高）。
     */
    public static synchronized boolean uninstall() {
        if (!installed) {
            return true;
        }
        List<Object> channels = ServerBridge.serverChannels();
        if (channels != null) {
            for (Object obj : channels) {
                if (!(obj instanceof Channel)) {
                    continue;
                }
                Channel ch = (Channel) obj;
                try {
                    if (ch.pipeline().get(ACCEPTOR_NAME) != null) {
                        ch.pipeline().remove(ACCEPTOR_NAME);
                    }
                } catch (Throwable ignore) {
                    // 移除失败忽略：尽力而为。
                }
            }
        }
        installed = false;
        injectedChannels = 0;
        MultiLoginPlugin plugin = MultiLoginPlugin.instance();
        if (plugin != null) {
            plugin.log().info("Netty 登录拦截已卸载。");
        }
        return true;
    }

    /** 是否已注入。 */
    public static boolean isInstalled() {
        return installed;
    }

    /** 状态行，供命令展示。 */
    public static String statusLine() {
        return installed ? ("已注入 " + injectedChannels + " 个监听通道") : "未注入";
    }

    /**
     * 挂在监听 Channel 管道最前面的 acceptor。
     * 它收到的 {@code channelRead} 消息就是服务端刚接受的子连接 Channel，
     * 我们给这个子连接挂上初始化器，延迟到下一个事件循环再 addLast 真正的 tap handler。
     */
    private static final class Acceptor extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // 监听管道里 channelRead 传递的就是子连接 Channel。
            if (msg instanceof Channel) {
                final Channel child = (Channel) msg;
                try {
                    // 给子连接加一个 ChannelInitializer：它的 initChannel 会在子连接注册时触发，
                    // 我们在其中再延迟一个事件循环再 addLast，确保原版 encoder/decoder 已就位。
                    child.pipeline().addLast(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            // 延迟到下一个事件循环：此时原版的 childHandler 已经把
                            // encoder/decoder/packet_handler 等装好，我们才能找到锚点。
                            ch.eventLoop().execute(new Runnable() {
                                @Override
                                public void run() {
                                    installTap(ch);
                                }
                            });
                        }
                    });
                } catch (Throwable ignore) {
                    // 加初始化器失败也不能阻断连接。
                }
            }
            // 务必继续向下传递子连接，否则原版 ServerBootstrapAcceptor 收不到它，
            // 所有玩家都连不上。这一句绝对不能漏。
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * 把 tap handler 挂到子连接管道上。
     *
     * <p><b>位置至关重要，这里踩过一个实测出来的坑。</b>最初的写法是 {@code addLast}，
     * 当时的判断是「出站数据从 tail 往 head 流，addLast 即尾部，能最先看到还没被序列化的
     * 包对象」—— 这对<b>出站</b>是对的，但<b>入站彻底失效</b>：入站方向是 head → tail，
     * 业务 handler {@code packet_handler} 排在我们前面，它消费掉数据包后就不会再
     * {@code fireChannelRead} 往下传，所以挂在尾部的我们永远收不到入站包，
     * 从 Login Start 里提取玩家名的逻辑等于没跑（实测：发登录包后 debug 日志里
     * 完全没有「登录阶段记录玩家名」）。</p>
     *
     * <p>正确位置是 {@code packet_handler} <b>之前</b>，这个位置对两个方向同时成立：
     * <pre>
     * 管道顺序(head→tail):  head ... splitter, decoder, encoder, ..., [我们], packet_handler, tail
     * 入站(head→tail):      decoder 已解码成包对象 → 到我们 → 再到 packet_handler   ✔ 拿得到包
     * 出站(tail→head):      packet_handler → 到我们 → 再到 encoder 序列化           ✔ 仍是包对象
     * </pre>
     * 找不到锚点时退回 {@code addLast}：那样入站提取会失效，但出站替换仍然有效，
     * 而且 {@code LoginTapHandler} 里有「pendingErrors 只剩一条就用它」的兜底逻辑，
     * 功能降级而不是完全失效。</p>
     */
    private static void installTap(Channel ch) {
        try {
            if (ch.pipeline().get(TAP_NAME) != null) {
                return;
            }
            for (int i = 0; i < ANCHOR_NAMES.length; i++) {
                if (ch.pipeline().get(ANCHOR_NAMES[i]) != null) {
                    ch.pipeline().addBefore(ANCHOR_NAMES[i], TAP_NAME, new LoginTapHandler());
                    return;
                }
            }
            // 没找到锚点：退回尾部（出站可用、入站降级）。
            ch.pipeline().addLast(TAP_NAME, new LoginTapHandler());
            MultiLoginPlugin plugin = MultiLoginPlugin.instance();
            if (plugin != null) {
                plugin.debug("管道里找不到 packet_handler 锚点，tap 已挂到尾部（入站提取降级）。");
            }
        } catch (Throwable ignore) {
            // 任何异常都不能影响连接建立，原样放行即可。
        }
    }
}
