package net.wifil.mcmultilogin.verify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.wifil.mcmultilogin.MultiLoginPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OneBot 入站监听（验证通道 = onebot / both 时启用）。
 *
 * <p>跨机部署下 verify.json 只留在 MC 服本地，因此 QQ 群的「验证 XXXX」必须由 bot 端
 * 转发到 MC 服。OneBot 直连模式下：OneBot（NapCat / Lagrange）把群消息以 HTTP webhook
 * 推到本服务，本服务解析出「验证 XXXX」→ 在本地 verify.json 按码标记已验证 →
 * 经 {@link OneBotSender} 回群「验证成功」。</p>
 *
 * <p><b>为何不用 {@code com.sun.net.httpserver}？</b> 该包在 Java 9+ 模块系统下默认不对
 * 插件类加载器开放，MC 服 JVM 启动不会带 {@code --add-exports}，运行期会炸。
 * 故本类用裸 {@link ServerSocket} 实现一个零依赖的最小 HTTP/1.1 服务，任何 JVM（含 Java 8）
 * 都能直接跑。</p>
 */
public final class VerifyCallbackServer {

    /** 群消息「验证 XXXX」正则（大小写不敏感，码 4~8 位，与 mcverify 一致）。 */
    private static final Pattern CODE_RE =
            Pattern.compile("^\\s*验证\\s*([A-Za-z0-9]{4,8})\\s*$", Pattern.CASE_INSENSITIVE);

    private final MultiLoginPlugin plugin;
    private final Logger logger;
    private ServerSocket ss;
    private Thread acceptThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public VerifyCallbackServer(MultiLoginPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /** 启动入站监听；成功返回 true。 */
    public boolean start(int port) {
        if (running.get()) {
            return true;
        }
        try {
            ss = new ServerSocket(port, 0, InetAddress.getByAddress(new byte[]{0, 0, 0, 0}));
            acceptThread = new Thread(new AcceptLoop(), "MCMultiLogin-VerifyCallback");
            acceptThread.setDaemon(true);
            acceptThread.start();
            running.set(true);
            logger.info("[VerifyCallback] OneBot 入站监听已启动：http://0.0.0.0:" + port
                    + "/  （接收群消息「验证 XXXX」；请在 OneBot 侧把 webhook 指向此地址）");
            return true;
        } catch (IOException e) {
            logger.warning("[VerifyCallback] 启动 OneBot 入站监听失败（端口 " + port + "）：" + e.getMessage());
            return false;
        }
    }

    /** 停止监听。 */
    public void stop() {
        running.set(false);
        try {
            if (ss != null) {
                ss.close();
            }
        } catch (IOException ignore) {
            // 忽略
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        ss = null;
        acceptThread = null;
    }

    public boolean isRunning() {
        return running.get();
    }

    // ------------------------------------------------------------------ 监听循环

    private final class AcceptLoop implements Runnable {
        @Override
        public void run() {
            while (running.get() && ss != null && !ss.isClosed()) {
                try {
                    final Socket socket = ss.accept();
                    new Thread(new ConnHandler(socket), "MCMultiLogin-VerifyCbConn").start();
                } catch (IOException e) {
                    if (running.get()) {
                        logger.warning("[VerifyCallback] 接受连接异常：" + e.getMessage());
                    }
                }
            }
        }
    }

    private final class ConnHandler implements Runnable {
        private final Socket socket;

        ConnHandler(Socket s) {
            this.socket = s;
        }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(10000);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                // 请求行（如 POST / HTTP/1.1）
                String requestLine = reader.readLine();
                if (requestLine == null || requestLine.trim().isEmpty()) {
                    return;
                }

                // 头部，找 Content-Length
                int contentLength = 0;
                while (true) {
                    String header = reader.readLine();
                    if (header == null || header.isEmpty()) {
                        break;
                    }
                    if (header.toLowerCase().startsWith("content-length:")) {
                        try {
                            contentLength = Integer.parseInt(header.substring(15).trim());
                        } catch (NumberFormatException ignore) {
                            contentLength = 0;
                        }
                    }
                }

                // 读取 body
                StringBuilder body = new StringBuilder();
                if (contentLength > 0) {
                    char[] buf = new char[Math.min(contentLength, 8192)];
                    int total = 0;
                    while (total < contentLength) {
                        int n = reader.read(buf, 0, Math.min(buf.length, contentLength - total));
                        if (n < 0) {
                            break;
                        }
                        body.append(buf, 0, n);
                        total += n;
                    }
                }

                String response = handleBody(body.toString());
                writeResponse(response);
            } catch (Exception ignore) {
                // 任何异常都静默处理，不让 OneBot 重试轰炸
            } finally {
                try {
                    socket.close();
                } catch (IOException ignore) {
                    // 忽略
                }
            }
        }

        private void writeResponse(String resp) throws IOException {
            byte[] data = resp.getBytes(StandardCharsets.UTF_8);
            StringBuilder http = new StringBuilder();
            http.append("HTTP/1.1 200 OK\r\n");
            http.append("Content-Type: application/json; charset=utf-8\r\n");
            http.append("Content-Length: ").append(data.length).append("\r\n");
            http.append("Connection: close\r\n");
            http.append("\r\n");
            OutputStream os = socket.getOutputStream();
            os.write(http.toString().getBytes(StandardCharsets.UTF_8));
            os.write(data);
            os.flush();
        }
    }

    // ------------------------------------------------------------------ 业务

    private String handleBody(String body) {
        JsonObject o;
        try {
            o = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException re) {
            return "{}";
        }

        String postType = optStr(o, "post_type", "");
        String msgType = optStr(o, "message_type", "");
        if (!"message".equals(postType) || !"group".equals(msgType)) {
            return "{}";
        }

        VerifyConfig cfg = plugin.verifyConfig();
        if (cfg != null && !cfg.groupVerifyListen()) {
            return "{}";
        }

        String raw = optStr(o, "raw_message", "");
        String groupId = optStr(o, "group_id", "");
        Matcher m = CODE_RE.matcher(raw);
        if (!m.find()) {
            return "{}";
        }
        String code = m.group(1).toUpperCase();

        VerifyState state = plugin.verifyState();
        if (state == null) {
            return "{\"ok\":false}";
        }
        String name = state.markVerifiedByCode(code, "");
        if (name != null) {
            logger.info("[VerifyCallback] 群 " + groupId + " 验证码 " + code
                    + " 匹配玩家 " + name + "，已置为已验证");
            OneBotSender onebot = plugin.onebot();
            if (onebot != null && !groupId.isEmpty()) {
                onebot.sendGroupMessage(groupId,
                        "✅ 验证成功：玩家 " + name + " 已绑定 QQ，可重新进服！");
            }
            return "{\"ok\":true}";
        }
        logger.info("[VerifyCallback] 群 " + groupId + " 验证码 " + code + " 无效或已过期");
        return "{\"ok\":false}";
    }

    private static String optStr(JsonObject o, String k, String def) {
        if (o == null || !o.has(k)) {
            return def;
        }
        com.google.gson.JsonElement e = o.get(k);
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : def;
    }
}
